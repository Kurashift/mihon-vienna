package eu.kanade.tachiyomi.data.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadRunTest {

    @Test
    fun `restart waits for canceled writer cleanup`() = runTest {
        val run = DownloadRun(backgroundScope)
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        run.start {
            try {
                events.add("old writing")
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { release.await() }
                events.add("old closed")
            }
        }
        runCurrent()
        run.cancel()
        run.start { events.add("new writing") }
        runCurrent()
        assertEquals(listOf("old writing"), events)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("old writing", "old closed", "new writing"), events)
    }

    @Test
    fun `canceling an intermediate restart cannot bypass the oldest writer`() = runTest {
        val run = DownloadRun(backgroundScope)
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        run.start {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { release.await() }
                events.add("old closed")
            }
        }
        runCurrent()
        run.cancel()
        run.start { events.add("intermediate") }
        runCurrent()
        run.cancel()
        run.start { events.add("last") }
        runCurrent()
        assertTrue(events.isEmpty())
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("old closed", "last"), events)
    }

    @Test
    fun `restart canceled before dispatch still waits for the oldest writer`() = runTest {
        val run = DownloadRun(backgroundScope)
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        run.start {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { release.await() }
                events.add("old closed")
            }
        }
        runCurrent()
        run.cancel()
        run.start { events.add("intermediate") }
        run.cancel()
        run.start { events.add("last") }
        runCurrent()
        assertTrue(events.isEmpty())
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("old closed", "last"), events)
    }

    @Test
    fun `only one active run starts and old owner cannot finish the new run`() = runTest {
        val run = DownloadRun(backgroundScope)
        var first: Job? = null
        var second: Job? = null
        var starts = 0
        run.start { owner ->
            first = owner
            starts++
            awaitCancellation()
        }
        run.start { starts++ }
        runCurrent()
        assertEquals(1, starts)
        assertTrue(run.isCurrent(first!!))
        run.cancel()
        run.start { owner ->
            second = owner
            starts++
            awaitCancellation()
        }
        runCurrent()
        assertFalse(run.isCurrent(first!!))
        assertTrue(run.isCurrent(second!!))
        assertEquals(2, starts)
        run.cancel()
        runCurrent()
        assertFalse(run.isRunning)
    }

    @Test
    fun `paused waiting run never writes after old writer closes`() = runTest {
        val run = DownloadRun(backgroundScope)
        val release = CompletableDeferred<Unit>()
        var restarted = false
        run.start {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { release.await() }
            }
        }
        runCurrent()
        run.cancel()
        run.start { restarted = true }
        runCurrent()
        run.cancel()
        release.complete(Unit)
        runCurrent()
        assertFalse(restarted)
        assertFalse(run.isRunning)
    }
}
