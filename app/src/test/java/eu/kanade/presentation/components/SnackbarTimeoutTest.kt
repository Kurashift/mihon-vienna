package eu.kanade.presentation.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.presentation.core.components.material.SNACKBAR_ACTION_TIMEOUT_MILLIS
import tachiyomi.presentation.core.components.material.SnackbarTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class SnackbarTimeoutTest {

    @Test
    fun `holding near expiry pauses commit and release gives five full seconds`() = runTest {
        var commits = 0
        val timer = SnackbarTimeout(this, SNACKBAR_ACTION_TIMEOUT_MILLIS, { testScheduler.currentTime }) {
            commits++
        }
        timer.start()
        advanceTimeBy(4_900)
        timer.setPressed(true)
        advanceTimeBy(20_000)
        assertEquals(0, commits)

        timer.setPressed(false)
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(0, commits)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, commits)
    }

    @Test
    fun `a new deletion restarts the batch timeout`() = runTest {
        var commits = 0
        val timer = SnackbarTimeout(this, 5_000, { testScheduler.currentTime }) { commits++ }
        timer.start()
        advanceTimeBy(4_000)
        timer.start()
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(0, commits)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, commits)
    }

    @Test
    fun `new deletions cannot resume a held batch`() = runTest {
        var commits = 0
        val timer = SnackbarTimeout(this, 5_000, { testScheduler.currentTime }) { commits++ }
        timer.start()
        timer.setPressed(true)
        timer.start()
        advanceTimeBy(10_000)
        assertEquals(0, commits)
        timer.setPressed(false)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, commits)
    }

    @Test
    fun `undo or swipe cancels the timeout even if pointer up arrives later`() = runTest {
        var commits = 0
        val timer = SnackbarTimeout(this, 5_000, { testScheduler.currentTime }) { commits++ }
        timer.start()
        timer.setPressed(true)
        timer.cancel()
        timer.setPressed(false)
        advanceTimeBy(10_000)
        assertEquals(0, commits)

        timer.start()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, commits)
    }

    @Test
    fun `leaving the reader preserves the remaining timeout`() = runTest {
        var commits = 0
        val reader = SnackbarTimeout(this, 5_000, { testScheduler.currentTime }) { commits++ }
        val background = SnackbarTimeout(this, 5_000, { testScheduler.currentTime }) { commits++ }
        reader.start()
        advanceTimeBy(3_000)
        background.start(reader.deadlineMillis)
        reader.cancel()

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(0, commits)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, commits)
    }

    @Test
    fun `a held snackbar gets five seconds on reader exit and can be held again`() = runTest {
        var commits = 0
        val reader = SnackbarTimeout(this, 5_000, { testScheduler.currentTime }) { commits++ }
        val background = SnackbarTimeout(this, 5_000, { testScheduler.currentTime }) { commits++ }
        reader.start()
        advanceTimeBy(4_000)
        reader.setPressed(true)
        advanceTimeBy(10_000)
        background.start(reader.deadlineMillis)
        reader.cancel()
        advanceTimeBy(4_000)
        background.setPressed(true)
        advanceTimeBy(10_000)
        assertEquals(0, commits)
        background.setPressed(false)
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(0, commits)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, commits)
    }

    @Test
    fun `accessibility timeout can be indefinite without overflowing`() = runTest {
        var commits = 0
        val timer = SnackbarTimeout(this, Long.MAX_VALUE, { testScheduler.currentTime }) { commits++ }
        advanceTimeBy(100)
        timer.start()
        runCurrent()
        assertEquals(Long.MAX_VALUE, timer.deadlineMillis)
        advanceTimeBy(60_000)
        assertEquals(0, commits)
        timer.cancel()
    }
}
