package eu.kanade.tachiyomi.ui.manga

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.SubscriberGate
import tachiyomi.core.common.util.lang.pausedWhileUnsubscribed
import kotlin.time.Duration.Companion.seconds

class SubscriberGateTest {

    @Test
    fun `five second grace period is cancelled when the screen returns`() = runTest {
        val gate = SubscriberGate()
        val subscribers = MutableStateFlow(1)
        backgroundScope.launch { gate.observeSubscriptions(subscribers, 5.seconds) }
        runCurrent()
        subscribers.value = 0
        runCurrent()
        advanceTimeBy(4_999)
        assertTrue(gate.isOpen)
        subscribers.value = 1
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(gate.isOpen)
        subscribers.value = 0
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(gate.isOpen)
    }

    @Test
    fun `hidden notifications only rebuild the latest large chapter state on return`() = runTest {
        val gate = SubscriberGate()
        val subscribers = MutableStateFlow(1)
        val revisions = MutableStateFlow(0)
        val chapters = List(3_001) { chapter -> List(20) { page -> chapter * 20 + page } }
        var rebuilds = 0
        var displayedRevision = -1
        backgroundScope.launch { gate.observeSubscriptions(subscribers, 5.seconds) }
        backgroundScope.launch(gate) {
            revisions.pausedWhileUnsubscribed()
                .map { revision ->
                    rebuilds++
                    chapters.map { it.sum() }
                    revision
                }
                .collect { displayedRevision = it }
        }
        runCurrent()
        assertEquals(1, rebuilds)
        subscribers.value = 0
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        repeat(3_001) { revision ->
            revisions.value = revision + 1
            runCurrent()
        }
        assertEquals(1, rebuilds)
        assertEquals(0, displayedRevision)

        subscribers.value = 1
        runCurrent()

        assertEquals(2, rebuilds)
        assertEquals(3_001, displayedRevision)
    }

    @Test
    fun `repeated hiding keeps exactly one upstream subscription`() = runTest {
        val gate = SubscriberGate()
        val subscribers = MutableStateFlow(1)
        val revisions = MutableStateFlow(0)
        var starts = 0
        var active = 0
        val upstream = flow {
            starts++
            active++
            try {
                emitAll(revisions)
            } finally {
                active--
            }
        }
        backgroundScope.launch { gate.observeSubscriptions(subscribers, 5.seconds) }
        backgroundScope.launch(gate) { upstream.pausedWhileUnsubscribed().collect {} }
        runCurrent()
        repeat(10) {
            subscribers.value = 0
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            revisions.value++
            subscribers.value = 1
            runCurrent()
        }

        assertEquals(1, starts)
        assertEquals(1, active)
    }

    @Test
    fun `shared callers without a gate receive every notification`() = runTest {
        val revisions = MutableSharedFlow<Int>()
        val received = mutableListOf<Int>()
        backgroundScope.launch { revisions.pausedWhileUnsubscribed().collect { received.add(it) } }
        runCurrent()
        repeat(10) {
            revisions.emit(it)
            runCurrent()
        }
        assertEquals((0 until 10).toList(), received)
    }
}
