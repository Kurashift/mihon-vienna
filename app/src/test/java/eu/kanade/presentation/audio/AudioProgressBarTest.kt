package eu.kanade.presentation.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioProgressBarTest {

    @Test
    fun `an unknown duration reports no progress`() {
        assertEquals(0f, progressFraction(positionMs = 5_000, durationMs = 0))
    }

    @Test
    fun `progress is the played share of the track`() {
        assertEquals(0.25f, progressFraction(positionMs = 25_000, durationMs = 100_000))
    }

    @Test
    fun `a position past the end does not overdraw`() {
        // The player reports the requested position while a seek is still in flight, so a position
        // ahead of a duration that has not been re-read yet is a real state, not a bug.
        assertEquals(1f, progressFraction(positionMs = 120_000, durationMs = 100_000))
    }

    @Test
    fun `a negative position reports no progress`() {
        assertEquals(0f, progressFraction(positionMs = -1_000, durationMs = 100_000))
    }

    @Test
    fun `buffered share is the buffered length of the track`() {
        assertEquals(0.5f, bufferedFraction(positionMs = 10_000, bufferedMs = 50_000, durationMs = 100_000))
    }

    @Test
    fun `the buffered segment is never shorter than the played one`() {
        // Position and buffer are read from the player separately, so a position can briefly report
        // ahead of the buffer covering it. Drawing that verbatim makes the segment vanish for a
        // frame, which is the flicker this floor exists to prevent.
        val fraction = bufferedFraction(positionMs = 40_000, bufferedMs = 30_000, durationMs = 100_000)

        assertEquals(0.4f, fraction)
    }

    @Test
    fun `a buffered segment past the end does not overdraw`() {
        assertEquals(1f, bufferedFraction(positionMs = 0, bufferedMs = 200_000, durationMs = 100_000))
    }

    @Test
    fun `an unknown duration reports nothing buffered`() {
        assertEquals(0f, bufferedFraction(positionMs = 5_000, bufferedMs = 50_000, durationMs = 0))
    }

    @Test
    fun `buffered progress never precedes the played progress`() {
        // Swept rather than spot-checked: the invariant is what the drawing relies on, and any
        // combination of the three inputs has to satisfy it.
        for (position in 0L..100_000L step 7_000L) {
            for (buffered in 0L..100_000L step 11_000L) {
                val played = progressFraction(position, 100_000)
                val bufferedFraction = bufferedFraction(position, buffered, 100_000)
                assertTrue(
                    bufferedFraction >= played,
                    "buffered $bufferedFraction < played $played at position=$position buffered=$buffered",
                )
            }
        }
    }
}
