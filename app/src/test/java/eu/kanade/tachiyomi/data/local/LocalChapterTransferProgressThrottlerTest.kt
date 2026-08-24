package eu.kanade.tachiyomi.data.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalChapterTransferProgressThrottlerTest {

    @Test
    fun `rapid byte updates are emitted at most once per interval`() {
        var now = 0L
        val emitted = mutableListOf<LocalChapterTransferService.Progress>()
        val throttler = LocalChapterTransferProgressThrottler(
            intervalMillis = 300L,
            elapsedRealtimeMillis = { now },
            onEmit = emitted::add,
        )

        throttler.update(progress(completed = 0, copiedBytes = 8_192L))
        now = 100L
        throttler.update(progress(completed = 0, copiedBytes = 16_384L))
        now = 299L
        throttler.update(progress(completed = 0, copiedBytes = 24_576L))
        now = 300L
        throttler.update(progress(completed = 0, copiedBytes = 32_768L))

        assertEquals(listOf(8_192L, 32_768L), emitted.map { it.copiedBytes })
    }

    @Test
    fun `completing a chapter bypasses the time interval`() {
        var now = 0L
        val emitted = mutableListOf<LocalChapterTransferService.Progress>()
        val throttler = LocalChapterTransferProgressThrottler(
            intervalMillis = 300L,
            elapsedRealtimeMillis = { now },
            onEmit = emitted::add,
        )

        throttler.update(progress(completed = 0, copiedBytes = 8_192L))
        now = 10L
        throttler.update(progress(completed = 1, copiedBytes = 16_384L))

        assertEquals(listOf(0, 1), emitted.map { it.completed })
    }

    @Test
    fun `flush forces the latest exact progress at task completion`() {
        var now = 0L
        val emitted = mutableListOf<LocalChapterTransferService.Progress>()
        val throttler = LocalChapterTransferProgressThrottler(
            intervalMillis = 300L,
            elapsedRealtimeMillis = { now },
            onEmit = emitted::add,
        )

        throttler.update(progress(completed = 0, copiedBytes = 8_192L))
        now = 50L
        val latest = progress(completed = 0, copiedBytes = 40_960L)
        throttler.update(latest)
        throttler.flush()

        assertEquals(listOf(8_192L, 40_960L), emitted.map { it.copiedBytes })
        assertEquals(latest, emitted.last())
    }

    private fun progress(completed: Int, copiedBytes: Long) = LocalChapterTransferService.Progress(
        completed = completed,
        total = 2,
        currentName = "chapter.cbz",
        copiedBytes = copiedBytes,
        totalBytes = 81_920L,
    )
}
