package eu.kanade.tachiyomi.data.audio

import eu.kanade.tachiyomi.data.audio.RecommenderFeedback.LISTEN_30_PERCENT
import eu.kanade.tachiyomi.data.audio.RecommenderFeedback.LISTEN_5_MIN
import eu.kanade.tachiyomi.data.audio.RecommenderFeedback.LISTEN_60_PERCENT
import eu.kanade.tachiyomi.data.audio.RecommenderFeedback.START_LISTEN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecommenderFeedbackTrackerTest {

    @Test
    fun `first playing tick reports the listen and nothing repeats it`() {
        val tracker = RecommenderFeedbackTracker()

        assertEquals(listOf(START_LISTEN), tracker.onTick(WORK, TOTAL_MS, nowMs = 0, playing = true))
        assertEquals(emptyList<RecommenderFeedback>(), tracker.onTick(WORK, TOTAL_MS, nowMs = 500, playing = true))
    }

    @Test
    fun `paused time is not counted as listening`() {
        val tracker = RecommenderFeedbackTracker()

        tracker.onTick(WORK, TOTAL_MS, nowMs = 0, playing = true)
        tracker.onTick(WORK, TOTAL_MS, nowMs = 240_000, playing = true)
        // An hour on the sofa: the tick still runs, and the tracker has to see it.
        tracker.onTick(WORK, TOTAL_MS, nowMs = 3_840_000, playing = false)
        tracker.onTick(WORK, TOTAL_MS, nowMs = 3_870_000, playing = true)

        assertEquals(
            emptyList<RecommenderFeedback>(),
            tracker.onTick(WORK, TOTAL_MS, nowMs = 3_900_000, playing = true),
        )
    }

    @Test
    fun `the five minute mark waits for five minutes of playing`() {
        val tracker = RecommenderFeedbackTracker()

        tracker.onTick(WORK, TOTAL_MS, nowMs = 0, playing = true)

        assertEquals(
            listOf(LISTEN_5_MIN),
            tracker.onTick(WORK, TOTAL_MS, nowMs = 300_000, playing = true),
        )
        assertEquals(
            emptyList<RecommenderFeedback>(),
            tracker.onTick(WORK, TOTAL_MS, nowMs = 400_000, playing = true),
        )
    }

    @Test
    fun `percent marks are a share of the work and come with whatever else is due`() {
        val tracker = RecommenderFeedbackTracker()

        tracker.onTick(WORK, TEN_MINUTES, nowMs = 0, playing = true)

        assertEquals(
            listOf(LISTEN_30_PERCENT),
            tracker.onTick(WORK, TEN_MINUTES, nowMs = 180_000, playing = true),
        )
        assertEquals(
            listOf(LISTEN_5_MIN, LISTEN_60_PERCENT),
            tracker.onTick(WORK, TEN_MINUTES, nowMs = 360_000, playing = true),
        )
    }

    @Test
    fun `a work with no known length never reaches the percent marks`() {
        val tracker = RecommenderFeedbackTracker()

        tracker.onTick(WORK, totalMs = 0, nowMs = 0, playing = true)

        assertEquals(
            emptyList<RecommenderFeedback>(),
            tracker.onTick(WORK, totalMs = 0, nowMs = 120_000, playing = true),
        )
    }

    @Test
    fun `another work starts a new count`() {
        val tracker = RecommenderFeedbackTracker()

        tracker.onTick(WORK, TOTAL_MS, nowMs = 0, playing = true)
        tracker.onTick(WORK, TOTAL_MS, nowMs = 200_000, playing = true)

        assertEquals(
            listOf(START_LISTEN),
            tracker.onTick(WORK + 1, TOTAL_MS, nowMs = 400_000, playing = true),
        )
    }

    @Test
    fun `nothing playing reports nothing`() {
        val tracker = RecommenderFeedbackTracker()

        assertEquals(emptyList<RecommenderFeedback>(), tracker.onTick(null, TOTAL_MS, nowMs = 0, playing = false))
        assertEquals(emptyList<RecommenderFeedback>(), tracker.onTick(WORK, TOTAL_MS, nowMs = 0, playing = false))
    }

    private companion object {
        const val WORK = 1122344L
        const val TOTAL_MS = 3_600_000L
        const val TEN_MINUTES = 600_000L
    }
}
