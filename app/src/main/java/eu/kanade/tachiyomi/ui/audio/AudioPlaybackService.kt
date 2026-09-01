package eu.kanade.tachiyomi.ui.audio

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.Constants
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Keeps audio alive outside the player screen and exposes standard system media controls. */
class AudioPlaybackService : Service() {

    private val controller: AudioPlayerController by lazy { Injekt.get() }
    private lateinit var mediaSession: MediaSessionCompat

    /**
     * Floating subtitles are part of playback rather than of a screen: they have to stay on screen
     * once the app is in the background, which is exactly when this service is the only thing left
     * running. It attaches here and is removed in [onDestroy] so the window can never outlive it.
     */
    private val floatingSubtitles: AudioFloatingSubtitleOverlay by lazy {
        AudioFloatingSubtitleOverlay(this, Injekt.get(), controller)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Cover currently shown in the notification, kept so it survives frequent state updates. */
    private var coverBitmap: Bitmap? = null

    /**
     * Cover address the notification is built for. It doubles as the key that keeps continuous
     * playback-state updates from refetching the same image, and as the artwork URI handed to the
     * media session so the lock screen can load its own full-size copy.
     */
    private var coverArtUri: String? = null
    private var coverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val mediaButtonReceiver = ComponentName(this, MediaButtonReceiver::class.java)
        mediaSession = MediaSessionCompat(
            this,
            "MihonAudio",
            mediaButtonReceiver,
            // Lets a media button revive playback after the session has gone idle, instead of
            // only reaching us while the service happens to be running.
            PendingIntent.getBroadcast(
                this,
                MEDIA_BUTTON_REQUEST_CODE,
                Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(mediaButtonReceiver),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).apply {
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() = this@AudioPlaybackService.controller.play()
                    override fun onPause() = this@AudioPlaybackService.controller.pause()
                    override fun onSkipToNext() = this@AudioPlaybackService.controller.next()
                    override fun onSkipToPrevious() = this@AudioPlaybackService.controller.previous()
                    override fun onSeekTo(pos: Long) = this@AudioPlaybackService.controller.seekTo(pos)
                    override fun onStop() {
                        this@AudioPlaybackService.controller.release()
                        stopSelf()
                    }
                },
            )
            isActive = true
        }
        controller.onStateChanged = ::updateNotification
        floatingSubtitles.attach()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        when (intent?.action) {
            ACTION_TOGGLE -> controller.togglePlay()
            ACTION_PREVIOUS -> controller.previous()
            ACTION_NEXT -> controller.next()
            ACTION_STOP -> {
                controller.release()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val state = controller.state
        val item = state.item
        if (item == null) {
            // A media button can reach MediaButtonReceiver, which starts us with
            // startForegroundService() even when there is nothing to play. The foreground
            // promise has to be honoured before stopping or Android 8+ kills the app.
            startForeground(Notifications.ID_AUDIO_PLAYBACK, buildNotification(state))
            stopSelf()
        } else {
            // The first notification is posted before any art exists, so start the fetch now
            // instead of waiting for the next playback-state update.
            requestCoverArt(item)
            updateMediaSession(state)
            startForeground(Notifications.ID_AUDIO_PLAYBACK, buildNotification(state))
        }
        return START_NOT_STICKY
    }

    /**
     * Passes rotations on to the floating subtitles.
     *
     * The service survives them while the windows it owns are sized in pixels taken from the screen
     * they first appeared on, so without this the card keeps the width it had in the orientation it
     * started in.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        floatingSubtitles.onConfigurationChanged()
    }

    override fun onDestroy() {
        controller.onStateChanged = null
        floatingSubtitles.detach()
        clearCover()
        scope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    private fun updateNotification(state: AudioPlayerState) {
        val item = state.item
        if (item == null) {
            clearCover()
            updateMediaSession(state)
            NotificationManagerCompat.from(this).cancel(Notifications.ID_AUDIO_PLAYBACK)
            return
        }
        // Resolve the art before building, so a track change never reuses the previous cover.
        requestCoverArt(item)
        updateMediaSession(state)
        NotificationManagerCompat.from(this)
            .notify(Notifications.ID_AUDIO_PLAYBACK, buildNotification(state))
    }

    /**
     * Loads the work cover for the notification. Playback state changes arrive continuously, so
     * the image is only fetched once per track and dropped silently when it can't be read; the
     * notification still shows the text metadata in that case.
     */
    private fun requestCoverArt(item: AudioPlayItem) {
        val url = item.coverUrl?.takeIf { it.isNotBlank() }
        if (url == null) {
            clearCover()
            return
        }
        if (url == coverArtUri) return

        coverJob?.cancel()
        // Drop the previous track's art right away; a stale cover is worse than a blank one.
        coverBitmap = null
        coverArtUri = url
        coverJob = scope.launch {
            val bitmap = loadCoverBitmap(url)
            // The track may have changed while the image was in flight.
            if (coverArtUri != url) return@launch
            if (bitmap != null) {
                coverBitmap = bitmap
                refreshNotification()
            }
        }
    }

    /** Drops any loaded cover so the next notification never shows another track's art. */
    private fun clearCover() {
        coverJob?.cancel()
        coverBitmap = null
        coverArtUri = null
    }

    private suspend fun loadCoverBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(this@AudioPlaybackService)
            .data(url)
            .size(NOTIF_COVER_SIZE)
            .build()
        val drawable = this@AudioPlaybackService.imageLoader.execute(request).image
            ?.asDrawable(resources)
        drawable?.getBitmapOrNull()
    }

    private fun refreshNotification() {
        val state = controller.state
        if (state.item == null) return
        updateMediaSession(state)
        NotificationManagerCompat.from(this)
            .notify(Notifications.ID_AUDIO_PLAYBACK, buildNotification(state))
    }

    private fun updateMediaSession(state: AudioPlayerState) {
        val playbackState = when {
            state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            state.isBuffering -> PlaybackStateCompat.STATE_BUFFERING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP or
                        // Advertise skipping only when there is somewhere to skip to, so the
                        // lock screen does not offer a control that does nothing.
                        (if (state.hasPrevious) PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS else 0L) or
                        (if (state.hasNext) PlaybackStateCompat.ACTION_SKIP_TO_NEXT else 0L),
                )
                .setState(playbackState, state.positionMs, if (state.isPlaying) 1f else 0f)
                .build(),
        )
        state.item?.let { item ->
            mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item.trackTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, item.circleName)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, item.workTitle)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
                    .apply {
                        coverBitmap?.let { bitmap ->
                            putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                            putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                        }
                        // Let the lock screen and SystemUI load their own size: the bitmap above
                        // is only 256px because it is sized for the notification's large icon.
                        coverArtUri?.let { uri ->
                            putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, uri)
                            putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, uri)
                        }
                    }
                    .build(),
            )
        }
    }

    private fun buildNotification(state: AudioPlayerState): Notification {
        val item = state.item
        val togglePending = servicePendingIntent(0, ACTION_TOGGLE)
        val stopPending = servicePendingIntent(2, ACTION_STOP)
        val contentPending = PendingIntent.getActivity(
            this,
            4,
            Intent(this, MainActivity::class.java).apply {
                action = Constants.SHOW_AUDIO_PLAYER
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleIcon = if (state.isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        // Only the actions that can actually do something: a permanently dead button in a
        // notification is indistinguishable from a broken one. Play/pause is always there and
        // sits in the middle, so the skip buttons stay on the outside as before.
        val actions = buildList {
            if (state.hasPrevious) {
                add(
                    NotificationCompat.Action(
                        android.R.drawable.ic_media_previous,
                        "上一首",
                        servicePendingIntent(3, ACTION_PREVIOUS),
                    ),
                )
            }
            add(NotificationCompat.Action(toggleIcon, "播放/暂停", togglePending))
            if (state.hasNext) {
                add(
                    NotificationCompat.Action(
                        android.R.drawable.ic_media_next,
                        "下一首",
                        servicePendingIntent(1, ACTION_NEXT),
                    ),
                )
            }
        }
        val compactIndices = actions.indices.toList().toIntArray()

        return notificationBuilder(Notifications.CHANNEL_AUDIO_PLAYBACK) {
            setSmallIcon(R.drawable.ic_mihon)
            coverBitmap?.let { setLargeIcon(it) }
            setContentTitle(item?.trackTitle.orEmpty())
            setContentText(item?.workTitle.orEmpty())
            setContentIntent(contentPending)
            setOngoing(false)
            setDeleteIntent(stopPending)
            setShowWhen(false)
            setOnlyAlertOnce(true)
            setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            actions.forEach { addAction(it) }
            setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    // Indices follow the list that was actually built: hard-coding 0, 1, 2 would
                    // point past the end once a skip button is left out.
                    .setShowActionsInCompactView(*compactIndices),
            )
        }.build()
    }

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AudioPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val ACTION_TOGGLE = "action_toggle"
        private const val ACTION_PREVIOUS = "action_previous"
        private const val ACTION_NEXT = "action_next"
        private const val ACTION_STOP = "action_stop"

        /** Notification artwork is small on every launcher; decoding more is wasted memory. */
        private const val NOTIF_COVER_SIZE = 256

        private const val MEDIA_BUTTON_REQUEST_CODE = 5

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AudioPlaybackService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AudioPlaybackService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
