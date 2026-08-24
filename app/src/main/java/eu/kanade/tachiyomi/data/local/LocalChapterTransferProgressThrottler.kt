package eu.kanade.tachiyomi.data.local

internal class LocalChapterTransferProgressThrottler(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
    private val onEmit: (LocalChapterTransferService.Progress) -> Unit,
) {
    private var latest: LocalChapterTransferService.Progress? = null
    private var lastEmitted: LocalChapterTransferService.Progress? = null
    private var lastEmittedAt: Long? = null

    fun update(progress: LocalChapterTransferService.Progress) {
        latest = progress
        val now = elapsedRealtimeMillis()
        val completedChanged = lastEmitted?.completed != progress.completed
        val intervalElapsed = lastEmittedAt?.let { now - it >= intervalMillis } ?: true
        if (completedChanged || intervalElapsed) {
            emitNow(progress, now)
        }
    }

    fun flush() {
        latest?.let { emitNow(it, elapsedRealtimeMillis()) }
    }

    private fun emitNow(progress: LocalChapterTransferService.Progress, now: Long) {
        onEmit(progress)
        lastEmitted = progress
        lastEmittedAt = now
    }

    private companion object {
        const val DEFAULT_INTERVAL_MILLIS = 300L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
