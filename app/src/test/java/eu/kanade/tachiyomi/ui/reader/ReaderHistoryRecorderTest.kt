package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.HistoryUpdate

class ReaderHistoryRecorderTest {

    @Test
    fun `overlapping saves consume duration before progress suspends`() = runTest {
        var time = 100L
        val saved = mutableListOf<HistoryUpdate>()
        val progress = CompletableDeferred<Unit>()
        val recorder = ReaderHistoryRecorder({ saved.add(it) }, { throw it }, backgroundScope) { time }
        recorder.restartTimer()
        time = 300
        recorder.save(1, false) { progress.await() }
        runCurrent()
        time = 400
        recorder.save(1, false)
        runCurrent()
        assertTrue(saved.isEmpty())

        progress.complete(Unit)
        runCurrent()

        assertEquals(listOf(200L, 0L), saved.map { it.sessionReadDuration })
        assertEquals(listOf(300L, 400L), saved.map { it.readAt.time })
    }

    @Test
    fun `old save cannot clear the next timer or change its chapter`() = runTest {
        var time = 100L
        val saved = mutableListOf<HistoryUpdate>()
        val progress = CompletableDeferred<Unit>()
        val recorder = ReaderHistoryRecorder({ saved.add(it) }, { throw it }, backgroundScope) { time }
        recorder.restartTimer()
        time = 200
        recorder.save(1, false) { progress.await() }
        recorder.restartTimer()
        time = 500
        recorder.save(2, false)
        recorder.restartTimer()
        progress.complete(Unit)
        runCurrent()
        time = 700
        recorder.save(3, false)
        runCurrent()

        assertEquals(listOf(1L, 2L, 3L), saved.map { it.chapterId })
        assertEquals(listOf(100L, 300L, 200L), saved.map { it.sessionReadDuration })
    }

    @Test
    fun `confirmed saves finish after reader lifecycle is cancelled`() = runTest {
        val lifecycle = Job()
        val saved = mutableListOf<HistoryUpdate>()
        val progress = CompletableDeferred<Unit>()
        val recorder = ReaderHistoryRecorder({ saved.add(it) }, { throw it }, backgroundScope) { 100 }
        val caller = kotlinx.coroutines.CoroutineScope(coroutineContext + lifecycle)
        caller.launch { recorder.save(1, false) { progress.await() } }
        runCurrent()
        lifecycle.cancel()
        progress.complete(Unit)
        runCurrent()

        assertEquals(listOf(1L), saved.map { it.chapterId })
    }

    @Test
    fun `incognito consumes its timer without saving progress or history`() = runTest {
        var time = 100L
        val saved = mutableListOf<HistoryUpdate>()
        val recorder = ReaderHistoryRecorder({ saved.add(it) }, { throw it }, backgroundScope) { time }
        recorder.restartTimer()
        time = 200
        assertNull(recorder.save(1, true) { error("Incognito progress must not be saved") })
        recorder.save(2, false)
        runCurrent()

        assertEquals(listOf(2L), saved.map { it.chapterId })
        assertEquals(0L, saved.single().sessionReadDuration)
    }

    @Test
    fun `failed progress flush does not discard history or block later saves`() = runTest {
        val saved = mutableListOf<HistoryUpdate>()
        val errors = mutableListOf<Throwable>()
        val recorder = ReaderHistoryRecorder({ saved.add(it) }, { errors.add(it) }, backgroundScope) { 100 }
        recorder.save(1, false) { error("Progress failed") }
        recorder.save(2, false)
        runCurrent()

        assertEquals(listOf(1L, 2L), saved.map { it.chapterId })
        assertEquals("Progress failed", errors.single().message)
    }

    @Test
    fun `failed history save does not block the next chapter`() = runTest {
        val saved = mutableListOf<HistoryUpdate>()
        val errors = mutableListOf<Throwable>()
        val recorder = ReaderHistoryRecorder(
            upsert = { if (it.chapterId == 1L) error("History failed") else saved.add(it) },
            onError = { errors.add(it) },
            scope = backgroundScope,
        )
        recorder.save(1, false)
        recorder.save(2, false)
        runCurrent()

        assertEquals(listOf(2L), saved.map { it.chapterId })
        assertEquals("History failed", errors.single().message)
    }

    @Test
    fun `clock correction cannot subtract reading time`() = runTest {
        var time = 200L
        val saved = mutableListOf<HistoryUpdate>()
        val recorder = ReaderHistoryRecorder({ saved.add(it) }, { throw it }, backgroundScope) { time }
        recorder.restartTimer()
        time = 100
        recorder.save(1, false)
        runCurrent()

        assertEquals(0L, saved.single().sessionReadDuration)
    }
}
