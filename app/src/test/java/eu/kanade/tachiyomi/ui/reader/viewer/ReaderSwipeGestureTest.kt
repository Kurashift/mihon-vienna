package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderSwipeGestureTest {

    @Test
    fun `movement inside touch slop remains undecided`() {
        assertEquals(
            ReaderSwipeGesture.Axis.UNDECIDED,
            ReaderSwipeGesture.resolveAxis(7f, 3f, touchSlop = 8, dominanceRatio = 1.5f),
        )
    }

    @Test
    fun `dominant random direction locks random gesture`() {
        assertEquals(
            ReaderSwipeGesture.Axis.RANDOM,
            ReaderSwipeGesture.resolveAxis(18f, 6f, touchSlop = 8, dominanceRatio = 1.5f),
        )
    }

    @Test
    fun `dominant reading direction locks reading gesture`() {
        assertEquals(
            ReaderSwipeGesture.Axis.READING,
            ReaderSwipeGesture.resolveAxis(6f, 18f, touchSlop = 8, dominanceRatio = 1.5f),
        )
    }

    @Test
    fun `large ambiguous diagonal defaults to reading`() {
        assertEquals(
            ReaderSwipeGesture.Axis.READING,
            ReaderSwipeGesture.resolveAxis(20f, 18f, touchSlop = 8, dominanceRatio = 1.5f),
        )
    }

    @Test
    fun `random swipe confirmation requires distance dominance and direction`() {
        assertTrue(
            ReaderSwipeGesture.isConfirmed(
                randomDelta = -220f,
                readingDelta = 60f,
                threshold = 192,
                dominanceRatio = 1.5f,
                expectedNegativeDirection = true,
            ),
        )
        assertFalse(
            ReaderSwipeGesture.isConfirmed(
                randomDelta = -180f,
                readingDelta = 30f,
                threshold = 192,
                dominanceRatio = 1.5f,
                expectedNegativeDirection = true,
            ),
        )
        assertFalse(
            ReaderSwipeGesture.isConfirmed(
                randomDelta = -220f,
                readingDelta = 170f,
                threshold = 192,
                dominanceRatio = 1.5f,
                expectedNegativeDirection = true,
            ),
        )
        assertFalse(
            ReaderSwipeGesture.isConfirmed(
                randomDelta = 220f,
                readingDelta = 30f,
                threshold = 192,
                dominanceRatio = 1.5f,
                expectedNegativeDirection = true,
            ),
        )
    }
}
