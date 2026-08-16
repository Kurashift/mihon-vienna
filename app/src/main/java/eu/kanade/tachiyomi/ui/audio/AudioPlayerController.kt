package eu.kanade.tachiyomi.ui.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.audio.AudioHistoryEntry
import eu.kanade.tachiyomi.data.audio.AudioHistoryStore
import eu.kanade.tachiyomi.data.audio.AudioAccountProgress
import eu.kanade.tachiyomi.data.audio.AudioAccountSync
import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import eu.kanade.tachiyomi.data.audio.AudioQualityMode
import eu.kanade.tachiyomi.data.audio.KikoeruApi
import eu.kanade.tachiyomi.data.audio.buildAudioTrackCatalog
import eu.kanade.tachiyomi.data.audio.toWorkSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.OkHttpClient
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.File
import java.io.IOException

data class AudioPlayerState(
    val item: AudioPlayItem? = null,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val index: Int = 0,
    val totalCount: Int = 0,
    val isLooping: Boolean = false,
    val playbackSpeed: Float = 1f,
    val sleepTimerRemainingMs: Long = 0,
    val mediaVolume: Int = 0,
    val maxMediaVolume: Int = 1,
    val isMediaVolumeFixed: Boolean = false,
    val audioQuality: AudioQualityMode = AudioQualityMode.FLUENT_FIRST,
) {
    val isBuffering: Boolean
        get() = isLoading && !isPlaying
}

/**
 * App-wide streaming audio player. Media3 reads through the app's OkHttp stack and keeps a
 * bounded on-disk cache, so playback can start after buffering a small prefix instead of waiting
 * for an entire track to download.
 */
@UnstableApi
class AudioPlayerController(
    private val context: Context,
    private val historyStore: AudioHistoryStore,
    client: OkHttpClient,
    private val api: KikoeruApi,
    private val accountSync: AudioAccountSync,
    private val basePreferences: BasePreferences,
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    var state by mutableStateOf(
        AudioPlayerState(
            audioQuality = AudioQualityMode.fromPreference(basePreferences.audioQuality.get()),
        ),
    )
        private set

    var readerControlsVisible by mutableStateOf(false)
        private set

    /** Invoked on playback-state changes so the foreground service can refresh its notification. */
    var onStateChanged: ((AudioPlayerState) -> Unit)? = null

    init {
        refreshSystemVolume(notifyService = false)
    }

    private var items: List<AudioPlayItem> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null
    private var bufferingStartedAt = 0L
    private var playbackRetryJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var volumeProtectionVerificationJob: Job? = null
    private var playbackRetryCount = 0
    private var sleepTimerEndsAt = 0L
    private var lastPeriodicHistoryAt = 0L
    private var headphonesWarningToast: Toast? = null
    private var startGeneration = 0
    private var completionCandidateWorkId: Long? = null

    private val cache = SimpleCache(
        File(context.cacheDir, AUDIO_CACHE_DIRECTORY),
        LeastRecentlyUsedCacheEvictor(AUDIO_CACHE_MAX_BYTES),
        StandaloneDatabaseProvider(context),
    )

    private val playbackClient = client.newBuilder()
        .addInterceptor { chain ->
            val token = basePreferences.audioAuthToken.get()
            val request = if (token.isBlank()) {
                chain.request()
            } else {
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            }
            var response = chain.proceed(request)
            var retryCount = 0
            while (response.code in RETRYABLE_HTTP_CODES && retryCount < MAX_INLINE_HTTP_RETRIES) {
                response.close()
                Thread.sleep(INLINE_HTTP_RETRY_BACKOFF_MS shl retryCount)
                retryCount++
                response = chain.proceed(request)
            }
            response
        }
        .build()
    private val upstreamFactory = OkHttpDataSource.Factory(playbackClient)
        .setUserAgent(USER_AGENT)
    private val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    private val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .build(),
        )
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            addListener(PlayerListener())
        }

    fun start(
        newItems: List<AudioPlayItem>,
        startIndex: Int,
        startPositionMs: Long,
        playWhenReady: Boolean = true,
    ) {
        if (newItems.isEmpty()) return
        if (state.item == null) readerControlsVisible = true
        val requestedItems = newItems.distinctBy { it.mediaStreamUrl }
        val targetIndex = startIndex.coerceIn(requestedItems.indices)
        val generation = ++startGeneration
        stopPlaybackRetry()
        playbackRetryCount = 0
        recordHistory()

        if (
            requestedItems[targetIndex].mediaStreamUrl.isLegacyRawStream() ||
            requestedItems[targetIndex].mediaStreamUrl.isLowQualityStream() ||
            requestedItems[targetIndex].subtitleUrl.isLegacySubtitleDownload()
        ) {
            player.stop()
            player.clearMediaItems()
            items = requestedItems
            updateCurrentItem(targetIndex, loading = true)
            startProgressLoop()
            scope.launch {
                val resolvedItems = runCatching {
                    resolveLegacyWorkStreams(requestedItems, targetIndex)
                }.getOrDefault(requestedItems)
                if (generation != startGeneration) return@launch
                beginPlayback(resolvedItems, targetIndex, startPositionMs, playWhenReady)
            }
            return
        }

        beginPlayback(requestedItems, targetIndex, startPositionMs, playWhenReady)
    }

    fun cycleAudioQuality() {
        setAudioQuality(state.audioQuality.next())
    }

    fun setAudioQuality(quality: AudioQualityMode) {
        if (quality == state.audioQuality) return
        basePreferences.audioQuality.set(quality.preferenceValue)
        publish(state.copy(audioQuality = quality))

        val current = state.item ?: return
        val workId = current.workId
        val work = current.toWorkSnapshot()
        val oldIndex = player.currentMediaItemIndex
        val oldPosition = player.currentPosition.coerceAtLeast(0)
        val playWhenReady = player.playWhenReady

        scope.launch {
            val nodes = api.fetchTracks(workId)
            val catalog = nodes.buildAudioTrackCatalog(work, quality)
            val replacements = catalog.tracks
                .groupBy { it.trackTitle }
                .mapValues { (_, tracks) -> ArrayDeque(tracks) }
            val rebuiltItems = items.map { item ->
                if (item.workId == workId) {
                    replacements[item.trackTitle]?.removeFirstOrNull() ?: item
                } else {
                    item
                }
            }
            if (rebuiltItems.isEmpty()) return@launch
            start(
                newItems = rebuiltItems,
                startIndex = oldIndex.coerceIn(rebuiltItems.indices),
                startPositionMs = oldPosition,
                playWhenReady = playWhenReady,
            )
        }
    }

    private fun beginPlayback(
        newItems: List<AudioPlayItem>,
        startIndex: Int,
        startPositionMs: Long,
        playWhenReady: Boolean = true,
    ) {
        items = newItems
        completionCandidateWorkId = newItems[startIndex].workId.takeIf { workId ->
            startIndex == newItems.indexOfFirst { it.workId == workId }
        }
        applyVolumeProtection()
        player.repeatMode = if (state.isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.setMediaItems(
            items.map { MediaItem.fromUri(it.mediaStreamUrl) },
            startIndex,
            startPositionMs.coerceAtLeast(0),
        )
        updateCurrentItem(startIndex, loading = true)
        player.prepare()
        player.playWhenReady = playWhenReady
        markBufferingStarted()
        startProgressLoop()
    }

    private suspend fun resolveLegacyWorkStreams(
        requestedItems: List<AudioPlayItem>,
        targetIndex: Int,
    ): List<AudioPlayItem> {
        val target = requestedItems[targetIndex]
        val nodes = api.fetchTracks(target.workId)
        return withContext(Dispatchers.Default) {
            val refreshedTracks = nodes.buildAudioTrackCatalog(target.toWorkSnapshot(), state.audioQuality).tracks
            val replacements = refreshedTracks.groupBy { it.trackTitle }
                .mapValues { (_, tracks) -> ArrayDeque(tracks) }

            requestedItems.map { item ->
                if (item.workId != target.workId) {
                    item
                } else {
                    replacements[item.trackTitle]?.removeFirstOrNull() ?: item
                }
            }
        }
    }

    fun togglePlay() {
        if (player.mediaItemCount == 0) return
        if (player.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        if (player.mediaItemCount == 0 || player.isPlaying) return
        applyVolumeProtection()
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun pause() {
        if (player.isPlaying) player.pause()
    }

    fun seekTo(ms: Long) {
        val duration = currentDuration()
        player.seekTo(ms.coerceIn(0, duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
        publish(state.copy(positionMs = player.currentPosition.coerceAtLeast(0)))
        recordHistory()
    }

    fun seekBy(deltaMs: Long) {
        seekTo(player.currentPosition + deltaMs)
    }

    fun next() {
        if (player.hasNextMediaItem()) {
            completionCandidateWorkId = null
            applyVolumeProtection()
            player.seekToNextMediaItem()
            player.play()
        }
    }

    fun previous() {
        completionCandidateWorkId = null
        when {
            player.currentPosition >= RESTART_TRACK_THRESHOLD_MS -> player.seekTo(0)
            player.hasPreviousMediaItem() -> {
                applyVolumeProtection()
                player.seekToPreviousMediaItem()
                player.play()
            }
            else -> player.seekTo(0)
        }
    }

    fun random() {
        if (items.size <= 1) return
        completionCandidateWorkId = null
        applyVolumeProtection()
        val current = player.currentMediaItemIndex
        val target = items.indices.filterNot { it == current }.random()
        player.seekTo(target, 0)
        player.play()
    }

    fun toggleLoop() {
        val looping = !state.isLooping
        player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        publish(state.copy(isLooping = looping))
    }

    fun cyclePlaybackSpeed() {
        val currentIndex = PLAYBACK_SPEEDS.indexOfFirst { it == state.playbackSpeed }
        val speed = PLAYBACK_SPEEDS[(currentIndex + 1).mod(PLAYBACK_SPEEDS.size)]
        player.setPlaybackSpeed(speed)
        publish(state.copy(playbackSpeed = speed))
    }

    fun setSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndsAt = 0L
        if (minutes == null) {
            publish(state.copy(sleepTimerRemainingMs = 0))
            return
        }
        val durationMs = minutes * 60_000L
        sleepTimerEndsAt = SystemClock.elapsedRealtime() + durationMs
        publish(state.copy(sleepTimerRemainingMs = durationMs))
        sleepTimerJob = scope.launch {
            delay(durationMs)
            player.pause()
            sleepTimerEndsAt = 0L
            publish(state.copy(sleepTimerRemainingMs = 0))
        }
    }

    fun setMediaVolume(volume: Int) {
        val manager = audioManager ?: return
        val snapshot = readSystemVolume()
        if (snapshot.isFixed) return
        val target = volume.coerceIn(0, snapshot.maximum)
        volumeProtectionVerificationJob?.cancel()
        volumeProtectionVerificationJob = null
        runCatching {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
        player.volume = 1f
        if (player.mediaItemCount > 0) {
            volumeProtectionApplied = true
            lastActiveAt = SystemClock.elapsedRealtime()
        } else {
            volumeProtectionApplied = false
            lastActiveAt = 0L
        }
        refreshSystemVolume()
    }

    fun refreshSystemVolume() {
        refreshSystemVolume(notifyService = false)
    }

    fun showReaderControls() {
        if (state.item != null) readerControlsVisible = true
    }

    fun hideReaderControls() {
        readerControlsVisible = false
    }

    fun release() {
        startGeneration++
        stopProgressLoop()
        clearBufferingState()
        stopPlaybackRetry()
        recordHistory()
        player.stop()
        player.clearMediaItems()
        player.volume = 1f
        player.setPlaybackSpeed(1f)
        items = emptyList()
        completionCandidateWorkId = null
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndsAt = 0L
        lastPeriodicHistoryAt = 0L
        volumeProtectionApplied = false
        lastActiveAt = 0L
        readerControlsVisible = false
        publish(AudioPlayerState())
    }

    private fun updateCurrentItem(index: Int, loading: Boolean = state.isLoading) {
        val item = items.getOrNull(index) ?: return
        publish(
            state.copy(
                item = item,
                isLoading = loading,
                positionMs = player.currentPosition.coerceAtLeast(0),
                bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0),
                durationMs = currentDuration().takeIf { it > 0 } ?: item.durationMs,
                error = null,
                hasPrevious = index > 0,
                hasNext = index < items.lastIndex,
                index = index,
                totalCount = items.size,
            ),
        )
    }

    private fun currentDuration(): Long {
        return player.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0) ?: 0L
    }

    private fun publish(newState: AudioPlayerState, notifyService: Boolean = true) {
        state = newState
        if (notifyService) onStateChanged?.invoke(newState)
    }

    private fun startProgressLoop() {
        stopProgressLoop()
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                if (player.mediaItemCount == 0) continue
                val duration = currentDuration()
                val volume = readSystemVolume()
                syncPlayerVolumeWithSystem(volume)
                publish(
                    state.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0),
                        bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0),
                        durationMs = duration.takeIf { it > 0 } ?: state.item?.durationMs ?: 0,
                        sleepTimerRemainingMs = if (sleepTimerEndsAt > 0) {
                            (sleepTimerEndsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                        } else {
                            0
                        },
                        mediaVolume = volume.current,
                        maxMediaVolume = volume.maximum,
                        isMediaVolumeFixed = volume.isFixed,
                    ),
                    notifyService = false,
                )
                checkBufferingTimeout(SystemClock.elapsedRealtime())
                if (player.isPlaying) {
                    val now = SystemClock.elapsedRealtime()
                    lastActiveAt = now
                    if (now - lastPeriodicHistoryAt >= PERIODIC_HISTORY_INTERVAL_MS) {
                        recordHistory()
                        lastPeriodicHistoryAt = now
                    }
                }
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun markBufferingStarted() {
        if (bufferingStartedAt == 0L) bufferingStartedAt = SystemClock.elapsedRealtime()
    }

    private fun clearBufferingState() {
        bufferingStartedAt = 0L
    }

    private fun checkBufferingTimeout(now: Long) {
        if (
            bufferingStartedAt == 0L ||
            player.playbackState != Player.STATE_BUFFERING ||
            now - bufferingStartedAt < BUFFERING_TIMEOUT_MS
        ) {
            return
        }

        clearBufferingState()
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.play()
            markBufferingStarted()
        } else {
            player.stop()
            publish(
                state.copy(
                    isLoading = false,
                    isPlaying = false,
                    error = context.stringResource(MR.strings.audio_buffer_timeout),
                ),
            )
        }
    }

    private fun retryOrSkipPlayback(error: PlaybackException) {
        val expectedUri = player.currentMediaItem?.localConfiguration?.uri
        if (error.isTransientNetworkError() && playbackRetryCount < MAX_PLAYBACK_RETRIES) {
            playbackRetryCount++
            val retryDelayMs = PLAYBACK_RETRY_BASE_DELAY_MS * (1L shl (playbackRetryCount - 1))
            publish(state.copy(isLoading = true, isPlaying = false, error = null))
            stopPlaybackRetry()
            playbackRetryJob = scope.launch {
                delay(retryDelayMs)
                if (player.currentMediaItem?.localConfiguration?.uri != expectedUri) return@launch
                player.prepare()
                player.play()
                markBufferingStarted()
            }
            return
        }

        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
            markBufferingStarted()
            return
        }

        publish(
            state.copy(
                isLoading = false,
                isPlaying = false,
                error = error.errorCodeName,
            ),
        )
    }

    private fun stopPlaybackRetry() {
        playbackRetryJob?.cancel()
        playbackRetryJob = null
    }

    private fun PlaybackException.isTransientNetworkError(): Boolean {
        var current: Throwable? = cause
        var hasIoCause = false
        while (current != null) {
            if (current is HttpDataSource.InvalidResponseCodeException) {
                return current.responseCode == 408 || current.responseCode == 429 || current.responseCode in 500..599
            }
            if (current is IOException) hasIoCause = true
            current = current.cause
        }
        return hasIoCause
    }

    private fun String.isLegacyRawStream(): Boolean {
        return startsWith(LEGACY_RAW_STREAM_PREFIX, ignoreCase = true)
    }

    private fun String.isLowQualityStream(): Boolean {
        return startsWith(LOW_QUALITY_STREAM_PREFIX, ignoreCase = true)
    }

    private fun String?.isLegacySubtitleDownload(): Boolean {
        return this != null &&
            startsWith(LEGACY_RAW_STREAM_PREFIX, ignoreCase = true) &&
            contains("/media/download/", ignoreCase = true)
    }

    private fun applyVolumeProtection() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastActiveAt >= VOLUME_PROTECTION_IDLE_WINDOW_MS) {
            volumeProtectionApplied = false
        }
        if (hasHeadphones()) {
            volumeProtectionVerificationJob?.cancel()
            volumeProtectionVerificationJob = null
            player.volume = 1f
            volumeProtectionApplied = false
        } else if (!volumeProtectionApplied) {
            val systemVolumeLowered = lowerSystemMediaVolumeForSafety()
            player.volume = if (systemVolumeLowered) 1f else SAFE_FALLBACK_PLAYER_VOLUME
            volumeProtectionApplied = true
            showHeadphonesWarning()
            scheduleVolumeProtectionVerification()
        }
        lastActiveAt = now
        refreshSystemVolume(notifyService = false)
    }

    private fun lowerSystemMediaVolumeForSafety(): Boolean {
        val audioManager = audioManager ?: return false
        return runCatching {
            val minimumVolume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            } else {
                0
            }
            val safeVolume = maxOf(minimumVolume, 1)
                .coerceAtMost(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (currentVolume > safeVolume) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, safeVolume, 0)
            }
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) <= safeVolume
        }.getOrDefault(false)
    }

    private fun scheduleVolumeProtectionVerification() {
        volumeProtectionVerificationJob?.cancel()
        volumeProtectionVerificationJob = scope.launch {
            VOLUME_PROTECTION_VERIFY_DELAYS_MS.forEach { delayMs ->
                delay(delayMs)
                if (hasHeadphones()) {
                    player.volume = 1f
                    volumeProtectionApplied = false
                    return@launch
                }
                val systemVolumeLowered = lowerSystemMediaVolumeForSafety()
                player.volume = if (systemVolumeLowered) 1f else SAFE_FALLBACK_PLAYER_VOLUME
                volumeProtectionApplied = true
            }
        }
    }

    private fun hasHeadphones(): Boolean {
        val audioManager = audioManager ?: return false
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY,
                    -> true
                    else -> false
                }
            }
        }.getOrDefault(false)
    }

    private fun showHeadphonesWarning() {
        headphonesWarningToast?.cancel()
        headphonesWarningToast = Toast.makeText(
            context,
            context.stringResource(MR.strings.audio_headphones_warning),
            Toast.LENGTH_SHORT,
        ).also { it.show() }
    }

    private fun refreshSystemVolume(notifyService: Boolean) {
        val volume = readSystemVolume()
        publish(
            state.copy(
                mediaVolume = volume.current,
                maxMediaVolume = volume.maximum,
                isMediaVolumeFixed = volume.isFixed,
            ),
            notifyService = notifyService,
        )
    }

    private fun readSystemVolume(): SystemVolume {
        val manager = audioManager ?: return SystemVolume(current = 0, maximum = 1, isFixed = true)
        return runCatching {
            val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            SystemVolume(
                current = manager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maximum),
                maximum = maximum,
                isFixed = manager.isVolumeFixed,
            )
        }.getOrDefault(SystemVolume(current = 0, maximum = 1, isFixed = true))
    }

    private fun syncPlayerVolumeWithSystem(volume: SystemVolume) {
        if (player.volume >= 1f) return
        val headphonesConnected = hasHeadphones()
        val userChangedSystemVolume = volume.current != state.mediaVolume
        if (!headphonesConnected && !userChangedSystemVolume) return
        volumeProtectionVerificationJob?.cancel()
        volumeProtectionVerificationJob = null
        player.volume = 1f
        volumeProtectionApplied = !headphonesConnected
    }

    private fun recordHistory() {
        val item = state.item ?: return
        historyStore.upsert(
            AudioHistoryEntry(
                item = item,
                positionMs = state.positionMs,
                lastPlayedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun syncAccountProgress(item: AudioPlayItem?, progress: AudioAccountProgress) {
        val workId = item?.workId ?: return
        scope.launch { accountSync.updateProgress(workId, progress) }
    }

    private inner class PlayerListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previousItem = state.item
            val previousIndex = state.index
            if (
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                previousItem != null &&
                completionCandidateWorkId == previousItem.workId &&
                previousIndex == items.indexOfLast { it.workId == previousItem.workId }
            ) {
                syncAccountProgress(previousItem, AudioAccountProgress.LISTENED)
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                completionCandidateWorkId = null
            }
            stopPlaybackRetry()
            playbackRetryCount = 0
            recordHistory()
            updateCurrentItem(
                player.currentMediaItemIndex,
                loading = player.playbackState == Player.STATE_IDLE ||
                    player.playbackState == Player.STATE_BUFFERING,
            )
            val currentItem = state.item
            if (
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                currentItem != null &&
                state.index == items.indexOfFirst { it.workId == currentItem.workId }
            ) {
                completionCandidateWorkId = currentItem.workId
            }
            markBufferingStarted()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    publish(state.copy(isLoading = true, error = null))
                    markBufferingStarted()
                }
                Player.STATE_READY -> {
                    clearBufferingState()
                    stopPlaybackRetry()
                    playbackRetryCount = 0
                    updateCurrentItem(player.currentMediaItemIndex, loading = false)
                    recordHistory()
                }
                Player.STATE_ENDED -> {
                    clearBufferingState()
                    publish(
                        state.copy(
                            isLoading = false,
                            isPlaying = false,
                            positionMs = state.durationMs,
                        ),
                    )
                    recordHistory()
                    val item = state.item
                    if (
                        item != null &&
                        completionCandidateWorkId == item.workId &&
                        state.index == items.indexOfLast { it.workId == item.workId }
                    ) {
                        syncAccountProgress(item, AudioAccountProgress.LISTENED)
                    }
                }
                Player.STATE_IDLE -> clearBufferingState()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publish(
                state.copy(
                    isPlaying = isPlaying,
                    isLoading = state.isLoading && !isPlaying,
                ),
            )
            recordHistory()
            if (isPlaying) syncAccountProgress(state.item, AudioAccountProgress.LISTENING)
        }

        override fun onPlayerError(error: PlaybackException) {
            clearBufferingState()
            logcat(LogPriority.ERROR, error) { "Audio playback failed for ${state.item?.trackTitle}" }
            retryOrSkipPlayback(error)
        }
    }

    private data class SystemVolume(
        val current: Int,
        val maximum: Int,
        val isFixed: Boolean,
    )

    private companion object {
        const val AUDIO_CACHE_DIRECTORY = "audio_media"
        const val AUDIO_CACHE_MAX_BYTES = 512L * 1024 * 1024
        const val MIN_BUFFER_MS = 10_000
        const val MAX_BUFFER_MS = 60_000
        const val BUFFER_FOR_PLAYBACK_MS = 750
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_500
        const val BUFFERING_TIMEOUT_MS = 20_000L
        const val MAX_PLAYBACK_RETRIES = 3
        const val PLAYBACK_RETRY_BASE_DELAY_MS = 500L
        const val MAX_INLINE_HTTP_RETRIES = 2
        const val INLINE_HTTP_RETRY_BACKOFF_MS = 120L
        const val LEGACY_RAW_STREAM_PREFIX = "https://raw.kiko-play-niptan.one/"
        const val LOW_QUALITY_STREAM_PREFIX = "https://fast.kiko-play-niptan.one/"
        const val PROGRESS_INTERVAL_MS = 500L
        const val PERIODIC_HISTORY_INTERVAL_MS = 15_000L
        const val RESTART_TRACK_THRESHOLD_MS = 5_000L
        const val SAFE_FALLBACK_PLAYER_VOLUME = 0.08f
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        const val VOLUME_PROTECTION_IDLE_WINDOW_MS = 10 * 60 * 1000L
        val VOLUME_PROTECTION_VERIFY_DELAYS_MS = listOf(150L, 500L, 1_000L)
        val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        val RETRYABLE_HTTP_CODES = setOf(408, 429) + (500..599)

        private var volumeProtectionApplied = false
        private var lastActiveAt = 0L
    }
}
