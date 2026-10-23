package eu.kanade.presentation.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PinchColumnsTest {

    @Test
    fun `an adaptive grid fits whole cells and their spacing`() {
        // 360dp at 3x, minus the 16dp the grid spends on its own content padding, with 4dp of
        // spacing between cells: (1032 + 12) / (384 + 12) = 2.6, so two columns.
        assertEquals(2, adaptiveColumnCount(availableWidthPx = 1032, spacingPx = 12, minCellWidthPx = 384))
    }

    @Test
    fun `a wider screen fits more columns`() {
        // 800dp at 3x landscape: (2388 + 12) / (384 + 12) = 6.06, so six columns.
        assertEquals(6, adaptiveColumnCount(availableWidthPx = 2388, spacingPx = 12, minCellWidthPx = 384))
    }

    @Test
    fun `a grid narrower than one cell still has one column`() {
        assertEquals(1, adaptiveColumnCount(availableWidthPx = 300, spacingPx = 12, minCellWidthPx = 384))
        assertEquals(1, adaptiveColumnCount(availableWidthPx = 384, spacingPx = 12, minCellWidthPx = 384))
    }

    @Test
    fun `a grid that has not been measured yet reports one column`() {
        assertEquals(1, adaptiveColumnCount(availableWidthPx = 0, spacingPx = 12, minCellWidthPx = 384))
        assertEquals(1, adaptiveColumnCount(availableWidthPx = -20, spacingPx = 12, minCellWidthPx = 384))
    }

    @Test
    fun `a pinch that has not moved keeps the count`() {
        assertEquals(4, pinchedColumnCount(base = 4, zoom = 1f, current = 4))
    }

    @Test
    fun `spreading the fingers takes columns away and pinching adds them`() {
        assertEquals(2, pinchedColumnCount(base = 4, zoom = 2f, current = 4))
        assertEquals(8, pinchedColumnCount(base = 4, zoom = 0.5f, current = 4))
    }

    @Test
    fun `the count stays inside the slider's range`() {
        assertEquals(MAX_PINCH_COLUMNS, pinchedColumnCount(base = 10, zoom = 0.1f, current = 10))
        assertEquals(MIN_PINCH_COLUMNS, pinchedColumnCount(base = 1, zoom = 10f, current = 1))
        assertEquals(10, pinchedColumnCount(base = 4, zoom = 0.01f, current = 4))
    }

    @Test
    fun `a count inside the dead band does not step`() {
        // 3.55 columns wanted, coming up from 3: the band holds it at 3 until the pinch is clearly
        // past the boundary, so a hand resting on it cannot flip back and forth every frame.
        assertEquals(3, pinchedColumnCount(base = 10, zoom = 10f / 3.55f, current = 3))
        // 3.7 columns wanted: past the band, so it steps.
        assertEquals(4, pinchedColumnCount(base = 10, zoom = 10f / 3.7f, current = 3))
    }

    @Test
    fun `the dead band holds on the way back too`() {
        assertEquals(4, pinchedColumnCount(base = 10, zoom = 10f / 3.45f, current = 4))
        assertEquals(3, pinchedColumnCount(base = 10, zoom = 10f / 3.3f, current = 4))
    }

    @Test
    fun `a pointer set that just changed shape leaves the count alone`() {
        listOf(Float.NaN, 0f, -1f, Float.POSITIVE_INFINITY).forEach { zoom ->
            assertEquals(4, pinchedColumnCount(base = 4, zoom = zoom, current = 4))
        }
    }

    @Test
    fun `an unknown base leaves the count alone`() {
        assertEquals(4, pinchedColumnCount(base = 0, zoom = 2f, current = 4))
    }

    @Test
    fun `one continuous pinch only ever steps in one direction`() {
        var current = 4
        val seen = mutableListOf(current)
        listOf(1f, 1.2f, 1.4f, 1.6f, 1.8f, 2f, 2.5f, 3f, 4f).forEach { zoom ->
            current = pinchedColumnCount(base = 4, zoom = zoom, current = current)
            seen += current
        }
        // Four columns down to one, never back up in between.
        assertEquals(seen.sortedDescending(), seen)
        assertEquals(MIN_PINCH_COLUMNS, current)
    }
}
