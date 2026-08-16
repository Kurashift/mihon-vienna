package eu.kanade.tachiyomi.ui.audio

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.notificationBuilder
import tachiyomi.core.common.Constants
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Keeps audio alive outside the player screen and exposes standard system media controls. */
class AudioPlaybackService : Service() {

    private val controller: AudioPlayerController by lazy { Injekt.get() }
    private lateinit var mediaSession: MediaSessionCompat

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "MihonAudio").apply {
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
        if (state.item == null) {
            stopSelf()
        } else {
            updateMediaSession(state)
            startForeground(Notifications.ID_AUDIO_PLAYBACK, buildNotification(state))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller.onStateChanged = null
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    private fun updateNotification(state: AudioPlayerState) {
        updateMediaSession(state)
        if (state.item == null) {
            NotificationManagerCompat.from(this).cancel(Notifications.ID_AUDIO_PLAYBACK)
            return
        }
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
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP,
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
                    .build(),
            )
        }
    }

    private fun buildNotification(state: AudioPlayerState): Notification {
        val item = state.item
        val previousPending = servicePendingIntent(3, ACTION_PREVIOUS)
        val togglePending = servicePendingIntent(0, ACTION_TOGGLE)
        val nextPending = servicePendingIntent(1, ACTION_NEXT)
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

        return notificationBuilder(Notifications.CHANNEL_AUDIO_PLAYBACK) {
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(item?.trackTitle.orEmpty())
            setContentText(item?.workTitle.orEmpty())
            setContentIntent(contentPending)
            setOngoing(false)
            setDeleteIntent(stopPending)
            setShowWhen(false)
            setOnlyAlertOnce(true)
            setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            addAction(android.R.drawable.ic_media_previous, "上一首", previousPending)
            addAction(toggleIcon, "播放/暂停", togglePending)
            addAction(android.R.drawable.ic_media_next, "下一首", nextPending)
            setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
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
