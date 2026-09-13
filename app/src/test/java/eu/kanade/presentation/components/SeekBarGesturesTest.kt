package eu.kanade.presentation.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeekBarGesturesTest {

    @Test
    fun `a drag on the bar moves one unit per width of travel`() {
        assertEquals(
            0.5f,
            advanceFraction(fromFraction = 0f, dxPx = 500f, widthPx = 1000f, gain = 1f),
            1e-6f,
        )
    }

    @Test
    fun `a tiny drag no longer skips a long way`() {
        // The old mapping jumped to the touch position, so a press 5px past the thumb on a
        // 1000-page bar landed hundreds of pages away. Five pixels of travel now moves 5 pages.
        assertEquals(
            0.505f,
            advanceFraction(fromFraction = 0.5f, dxPx = 5f, widthPx = 1000f, gain = 1f),
            1e-6f,
        )
    }

    @Test
    fun `the position never leaves the track`() {
        assertEquals(0f, advanceFraction(fromFraction = 0.01f, dxPx = -5000f, widthPx = 1000f, gain = 1f))
        assertEquals(1f, advanceFraction(fromFraction = 0.99f, dxPx = 5000f, widthPx = 1000f, gain = 1f))
    }

    @Test
    fun `fine gain slows the same drag down`() {
        val full = advanceFraction(fromFraction = 0f, dxPx = 100f, widthPx = 1000f, gain = 1f)
        val fine = advanceFraction(fromFraction = 0f, dxPx = 100f, widthPx = 1000f, gain = 0.1f)

        assertEquals(0.1f, full, 1e-6f)
        assertEquals(0.01f, fine, 1e-6f)
    }

    @Test
    fun `finger on the bar scrubs at full speed`() {
        assertEquals(1f, fineSeekGain(liftPx = 0f, rampPx = 100f))
        assertEquals(1f, fineSeekGain(liftPx = -20f, rampPx = 100f))
    }

    @Test
    fun `half a ramp of lift halves the speed`() {
        assertEquals(0.5f, fineSeekGain(liftPx = 100f, rampPx = 100f))
    }

    @Test
    fun `speed keeps falling but never reaches zero`() {
        val far = fineSeekGain(liftPx = 100_000f, rampPx = 100f)
        assertTrue(far > 0f, "a far lift must still move, was $far")
        assertEquals(MIN_FINE_SEEK_GAIN, far)
    }

    @Test
    fun `a tap maps to the touched position`() {
        assertEquals(0f, tapFraction(xPx = 0f, widthPx = 1000f))
        assertEquals(0.5f, tapFraction(xPx = 500f, widthPx = 1000f))
        assertEquals(1f, tapFraction(xPx = 1000f, widthPx = 1000f))
    }

    @Test
    fun `a tap outside the track clamps instead of overshooting`() {
        assertEquals(0f, tapFraction(xPx = -80f, widthPx = 1000f))
        assertEquals(1f, tapFraction(xPx = 5000f, widthPx = 1000f))
    }

    @Test
    fun `degenerate inputs leave the position alone`() {
        assertEquals(0.3f, advanceFraction(fromFraction = 0.3f, dxPx = 50f, widthPx = 0f, gain = 1f))
        assertEquals(0f, tapFraction(xPx = 50f, widthPx = 0f))
    }

    @Test
    fun `fine seeking does not engage on a drag wobble`() {
        // A horizontal drag always wobbles vertically by a pixel or two. A bare `lift > 0` test
        // engaged fine seeking on that wobble and toggled it every frame, which resized the thumb
        // and made it pulse.
        assertFalse(fineSeekEngaged(alreadyEngaged = false, liftPx = 0f, engagePx = 30f))
        assertFalse(fineSeekEngaged(alreadyEngaged = false, liftPx = 2f, engagePx = 30f))
        assertFalse(fineSeekEngaged(alreadyEngaged = false, liftPx = 29f, engagePx = 30f))
    }

    @Test
    fun `fine seeking engages once the finger clearly lifts`() {
        assertTrue(fineSeekEngaged(alreadyEngaged = false, liftPx = 31f, engagePx = 30f))
    }

    @Test
    fun `once engaged it stays on through the wobble band`() {
        // Between half the threshold and the threshold itself the state must not flip: this band
        // is exactly where a hand holding a drag steady would otherwise flicker.
        assertTrue(fineSeekEngaged(alreadyEngaged = true, liftPx = 20f, engagePx = 30f))
        assertTrue(fineSeekEngaged(alreadyEngaged = true, liftPx = 16f, engagePx = 30f))
    }

    @Test
    fun `fine seeking releases only when the finger clearly comes back down`() {
        assertFalse(fineSeekEngaged(alreadyEngaged = true, liftPx = 14f, engagePx = 30f))
        assertFalse(fineSeekEngaged(alreadyEngaged = true, liftPx = 0f, engagePx = 30f))
    }

    @Test
    fun `the hysteresis band holds the same state in both directions`() {
        // 20px sits inside the band: staying on keeps it on, staying off keeps it off.
        assertTrue(fineSeekEngaged(alreadyEngaged = true, liftPx = 20f, engagePx = 30f))
        assertFalse(fineSeekEngaged(alreadyEngaged = false, liftPx = 20f, engagePx = 30f))
    }
}
