package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebtoonPageSelectionTest {

    private val mixedPages = listOf(
        WebtoonPageSelection.VisiblePage(position = 1, start = 0, end = 240),
        WebtoonPageSelection.VisiblePage(position = 2, start = 240, end = 480),
        WebtoonPageSelection.VisiblePage(position = 3, start = 480, end = 2880),
    )

    @Test
    fun `landscape double tap normalizes a newly visible page to full height`() {
        assertEquals(
            WebtoonLandscapeZoom.FULL_HEIGHT_FRACTION,
            WebtoonLandscapeZoom.targetHeightFraction(displayedHeightFraction = 0.63f),
        )
    }

    @Test
    fun `landscape double tap shrinks a page already at full height`() {
        assertEquals(
            WebtoonLandscapeZoom.CONTINUOUS_HEIGHT_FRACTION,
            WebtoonLandscapeZoom.targetHeightFraction(displayedHeightFraction = 1.01f),
        )
    }

    @Test
    fun `backward scrolling cannot advance progress during a layout refresh`() {
        assertEquals(
            8,
            WebtoonPageSelection.resolveDirectionalPosition(
                currentPosition = 8,
                candidatePosition = 10,
                scrollDelta = -1,
            ),
        )
    }

    @Test
    fun `backward scrolling still accepts an earlier page`() {
        assertEquals(
            6,
            WebtoonPageSelection.resolveDirectionalPosition(
                currentPosition = 8,
                candidatePosition = 6,
                scrollDelta = -1,
            ),
        )
    }

    @Test
    fun `forward scrolling cannot retreat progress during a layout refresh`() {
        assertEquals(
            8,
            WebtoonPageSelection.resolveDirectionalPosition(
                currentPosition = 8,
                candidatePosition = 6,
                scrollDelta = 1,
            ),
        )
    }

    @Test
    fun `first page aligns to viewport start`() {
        assertEquals(
            0,
            WebtoonPageSelection.resolveInitialPageTop(0, 16, 804, 0, 2400),
        )
    }

    @Test
    fun `short last page aligns to viewport end`() {
        assertEquals(
            1596,
            WebtoonPageSelection.resolveInitialPageTop(16, 16, 804, 0, 2400),
        )
    }

    @Test
    fun `long last page keeps its bottom at viewport end`() {
        assertEquals(
            -1600,
            WebtoonPageSelection.resolveInitialPageTop(16, 16, 4000, 0, 2400),
        )
    }

    @Test
    fun `middle page is centered in inset viewport`() {
        assertEquals(
            848,
            WebtoonPageSelection.resolveInitialPageTop(7, 16, 804, 50, 2450),
        )
    }

    @Test
    fun `single page chapter keeps first page semantics`() {
        assertEquals(
            0,
            WebtoonPageSelection.resolveInitialPageTop(0, 0, 804, 0, 2400),
        )
    }

    @Test
    fun `initial reveal requires the target position`() {
        assertFalse(
            WebtoonPageSelection.isInitialPositionReady(804, 1500, 1596),
        )
        assertTrue(
            WebtoonPageSelection.isInitialPositionReady(804, 1595, 1596),
        )
    }

    @Test
    fun `initial reveal requires a measured attached page`() {
        assertFalse(
            WebtoonPageSelection.isInitialPositionReady(0, 1596, 1596),
        )
        assertFalse(
            WebtoonPageSelection.isInitialPositionReady(804, null, 1596),
        )
    }

    @Test
    fun `displaced position is only settled when the viewport cannot move`() {
        // 96px below the target with content still scrollable in both directions: not settled.
        assertFalse(
            WebtoonPageSelection.isInitialPositionClamped(
                currentStart = 1692,
                targetStart = 1596,
                canScrollForward = true,
                canScrollBackward = true,
            ),
        )
        // Same offset, but the content ends here, so nothing will move after the reveal.
        assertTrue(
            WebtoonPageSelection.isInitialPositionClamped(
                currentStart = 1692,
                targetStart = 1596,
                canScrollForward = false,
                canScrollBackward = true,
            ),
        )
        // On target is not "clamped", it is simply ready.
        assertFalse(
            WebtoonPageSelection.isInitialPositionClamped(
                currentStart = 1596,
                targetStart = 1596,
                canScrollForward = false,
                canScrollBackward = false,
            ),
        )
    }

    @Test
    fun `alignment settles only after the extent and the position hold`() {
        // First pass: no previous extent to compare against yet.
        assertFalse(
            WebtoonPageSelection.isInitialAlignSettled(
                previousExtent = -1,
                currentExtent = 2400,
                currentStart = 0,
                targetStart = 0,
                stableFrames = 2,
                requiredStableFrames = 2,
            ),
        )
        // Extent stopped changing, but the page has not reached its target top.
        assertFalse(
            WebtoonPageSelection.isInitialAlignSettled(
                previousExtent = 2400,
                currentExtent = 2400,
                currentStart = 1500,
                targetStart = 0,
                stableFrames = 2,
                requiredStableFrames = 2,
            ),
        )
        // Extent and position are stable, just not for long enough yet.
        assertFalse(
            WebtoonPageSelection.isInitialAlignSettled(
                previousExtent = 2400,
                currentExtent = 2400,
                currentStart = 0,
                targetStart = 0,
                stableFrames = 1,
                requiredStableFrames = 2,
            ),
        )
        assertTrue(
            WebtoonPageSelection.isInitialAlignSettled(
                previousExtent = 2400,
                currentExtent = 2400,
                currentStart = 0,
                targetStart = 0,
                stableFrames = 2,
                requiredStableFrames = 2,
            ),
        )
    }

    @Test
    fun `page identity survives adapter rebuilds`() {
        // The adapter hands out new page objects on every rebuild, so the initial-alignment flow
        // matches on this value instead of on the instance.
        assertEquals(
            PageIdentity(1L, 2L, "chapter-url", 3),
            PageIdentity(1L, 2L, "chapter-url", 3),
        )
        assertNotEquals(
            PageIdentity(1L, 2L, "chapter-url", 3),
            PageIdentity(1L, 2L, "chapter-url", 4),
        )
    }

    @Test
    fun `short first page remains active above taller pages at chapter start`() {
        assertTrue(
            WebtoonPageSelection.isStartBoundaryActive(
                pageStart = 0,
                pageEnd = 240,
                viewportStart = 0,
                viewportEnd = 2400,
            ),
        )
    }

    @Test
    fun `small layout offset does not lose first page boundary`() {
        assertTrue(
            WebtoonPageSelection.isStartBoundaryActive(
                pageStart = -20,
                pageEnd = 220,
                viewportStart = 0,
                viewportEnd = 2400,
            ),
        )
    }

    @Test
    fun `first page stops owning progress after most of it leaves the viewport`() {
        assertFalse(
            WebtoonPageSelection.isStartBoundaryActive(
                pageStart = -150,
                pageEnd = 90,
                viewportStart = 0,
                viewportEnd = 2400,
            ),
        )
    }

    @Test
    fun `next chapter first page does not activate before reaching the top edge`() {
        assertFalse(
            WebtoonPageSelection.isStartBoundaryActive(
                pageStart = 120,
                pageEnd = 360,
                viewportStart = 0,
                viewportEnd = 2400,
            ),
        )
    }

    @Test
    fun `leading transition does not prevent first page at content start`() {
        assertTrue(
            WebtoonPageSelection.isStartBoundaryActive(
                pageStart = 130,
                pageEnd = 370,
                viewportStart = 0,
                viewportEnd = 2400,
                contentStartReached = true,
            ),
        )
    }

    @Test
    fun `leading transition does not claim a barely visible first page`() {
        assertFalse(
            WebtoonPageSelection.isStartBoundaryActive(
                pageStart = 2300,
                pageEnd = 2540,
                viewportStart = 0,
                viewportEnd = 2400,
                contentStartReached = true,
            ),
        )
    }

    @Test
    fun `short last page remains active at the bottom edge`() {
        assertTrue(
            WebtoonPageSelection.isEndBoundaryActive(
                pageStart = 1596,
                pageEnd = 2400,
                viewportStart = 0,
                viewportEnd = 2400,
            ),
        )
    }

    @Test
    fun `initial short page remains active during a small first gesture`() {
        assertTrue(
            WebtoonPageSelection.isPageActive(
                pageStart = -80,
                pageEnd = 724,
                viewportStart = 0,
                viewportEnd = 2400,
            ),
        )
    }

    @Test
    fun `initial short page releases after more than half is consumed`() {
        assertFalse(
            WebtoonPageSelection.isPageActive(
                pageStart = -500,
                pageEnd = 304,
                viewportStart = 0,
                viewportEnd = 2400,
            ),
        )
    }

    @Test
    fun `visible chapter end remains active while its transition fills the bottom edge`() {
        assertTrue(
            WebtoonPageSelection.isEndBoundaryActive(
                pageStart = 1500,
                pageEnd = 2270,
                viewportStart = 0,
                viewportEnd = 2400,
                contentEndReached = true,
            ),
        )
    }

    @Test
    fun `long last page remains active when its bottom reaches the viewport edge`() {
        assertTrue(
            WebtoonPageSelection.isEndBoundaryActive(
                pageStart = -1600,
                pageEnd = 2400,
                viewportStart = 0,
                viewportEnd = 2400,
            ),
        )
    }

    @Test
    fun `forward reading keeps short first page before centered tall page`() {
        assertEquals(
            1,
            WebtoonPageSelection.resolveForwardPage(
                currentPosition = 1,
                centerPosition = 3,
                pages = mixedPages,
                viewportStart = 0,
                viewportEnd = 2400,
            ),
        )
    }

    @Test
    fun `forward reading advances through intermediate short page`() {
        val scrolledPages = listOf(
            WebtoonPageSelection.VisiblePage(position = 1, start = -150, end = 90),
            WebtoonPageSelection.VisiblePage(position = 2, start = 90, end = 330),
            WebtoonPageSelection.VisiblePage(position = 3, start = 330, end = 2730),
        )

        assertEquals(
            2,
            WebtoonPageSelection.resolveForwardPage(
                currentPosition = 1,
                centerPosition = 3,
                pages = scrolledPages,
                viewportStart = 0,
                viewportEnd = 2400,
            ),
        )
    }

    @Test
    fun `forward reading releases short page after most of it leaves`() {
        val scrolledPages = listOf(
            WebtoonPageSelection.VisiblePage(position = 2, start = -150, end = 90),
            WebtoonPageSelection.VisiblePage(position = 3, start = 90, end = 2490),
        )

        assertEquals(
            3,
            WebtoonPageSelection.resolveForwardPage(
                currentPosition = 2,
                centerPosition = 3,
                pages = scrolledPages,
                viewportStart = 0,
                viewportEnd = 2400,
            ),
        )
    }

    @Test
    fun `forward reading cannot retreat after chapter end was selected`() {
        assertEquals(
            10,
            WebtoonPageSelection.resolveForwardPage(
                currentPosition = 10,
                centerPosition = 9,
                pages = emptyList(),
                viewportStart = 0,
                viewportEnd = 2400,
            ),
        )
    }
}
