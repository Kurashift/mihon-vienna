package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object WebtoonPageSelection {

    data class VisiblePage(
        val position: Int,
        val start: Int,
        val end: Int,
    )

    fun resolveInitialPageTop(
        pageIndex: Int,
        lastPageIndex: Int,
        pageExtent: Int,
        viewportStart: Int,
        viewportEnd: Int,
    ): Int {
        if (pageExtent <= 0 || viewportEnd <= viewportStart) return viewportStart
        if (pageIndex == 0) return viewportStart
        if (pageIndex == lastPageIndex) return viewportEnd - pageExtent
        return viewportStart + (viewportEnd - viewportStart - pageExtent) / 2
    }

    /**
     * Whether the target page has been measured and is resting within one pixel of its target
     * top. There is deliberately no "close enough" mode: the reader becomes visible on this
     * answer, so accepting a displaced page shows a frame that then has to move under the user's
     * eyes.
     */
    fun isInitialPositionReady(
        pageExtent: Int,
        currentStart: Int?,
        targetStart: Int,
    ): Boolean {
        if (pageExtent <= 0 || currentStart == null) return false
        return abs(currentStart - targetStart) <= INITIAL_POSITION_TOLERANCE
    }

    /**
     * A displaced position is only acceptable when the viewport cannot move any further towards
     * the target: a page taller than the viewport on a chapter whose content ends there, or a
     * short last page pinned at the bottom edge. In that case nothing will shift after the reveal,
     * so showing the measured frame is the settled frame. Anything the viewport *could* still
     * scroll towards is not settled, no matter how close it looks.
     */
    fun isInitialPositionClamped(
        currentStart: Int?,
        targetStart: Int,
        canScrollForward: Boolean,
        canScrollBackward: Boolean,
    ): Boolean {
        if (currentStart == null) return false
        val correction = currentStart - targetStart
        if (abs(correction) <= INITIAL_POSITION_TOLERANCE) return false
        return if (correction > 0) !canScrollForward else !canScrollBackward
    }

    /**
     * One alignment pass: the page counts as settled only once its measured extent stopped
     * changing and it has rested at its target top for [requiredStableFrames] consecutive passes.
     */
    fun isInitialAlignSettled(
        previousExtent: Int,
        currentExtent: Int,
        currentStart: Int?,
        targetStart: Int,
        stableFrames: Int,
        requiredStableFrames: Int,
    ): Boolean {
        val extentStable = currentExtent > 0 && previousExtent == currentExtent
        return extentStable &&
            stableFrames >= requiredStableFrames &&
            isInitialPositionReady(currentExtent, currentStart, targetStart)
    }

    fun isStartBoundaryActive(
        pageStart: Int,
        pageEnd: Int,
        viewportStart: Int,
        viewportEnd: Int,
        contentStartReached: Boolean = false,
    ): Boolean {
        val viewportExtent = viewportEnd - viewportStart
        if (viewportExtent <= 0 || pageEnd <= pageStart) return false

        val edgeSlop = max(1, viewportExtent / EDGE_SLOP_DIVISOR)
        return (contentStartReached || pageStart <= viewportStart + edgeSlop) &&
            visibleEnough(pageStart, pageEnd, viewportStart, viewportEnd)
    }

    fun isEndBoundaryActive(
        pageStart: Int,
        pageEnd: Int,
        viewportStart: Int,
        viewportEnd: Int,
        contentEndReached: Boolean = false,
    ): Boolean {
        val viewportExtent = viewportEnd - viewportStart
        if (viewportExtent <= 0 || pageEnd <= pageStart) return false

        val edgeSlop = max(1, viewportExtent / EDGE_SLOP_DIVISOR)
        return (contentEndReached || pageEnd >= viewportEnd - edgeSlop) &&
            visibleEnough(pageStart, pageEnd, viewportStart, viewportEnd)
    }

    fun isPageActive(
        pageStart: Int,
        pageEnd: Int,
        viewportStart: Int,
        viewportEnd: Int,
    ): Boolean {
        return visibleEnough(pageStart, pageEnd, viewportStart, viewportEnd)
    }

    fun resolveForwardPage(
        currentPosition: Int,
        centerPosition: Int,
        pages: List<VisiblePage>,
        viewportStart: Int,
        viewportEnd: Int,
    ): Int {
        if (currentPosition < 0) return centerPosition
        if (centerPosition <= currentPosition) return currentPosition

        val currentPage = pages.firstOrNull { it.position == currentPosition }
        if (
            currentPage != null &&
            visibleEnough(currentPage.start, currentPage.end, viewportStart, viewportEnd)
        ) {
            return currentPosition
        }

        val viewportCenter = viewportStart + (viewportEnd - viewportStart) / 2
        return pages.firstOrNull { page ->
            page.position in (currentPosition + 1)..centerPosition &&
                page.start <= viewportCenter &&
                visibleEnough(page.start, page.end, viewportStart, viewportEnd)
        }?.position ?: centerPosition
    }

    fun resolveDirectionalPosition(
        currentPosition: Int,
        candidatePosition: Int,
        scrollDelta: Int,
    ): Int {
        if (currentPosition < 0 || candidatePosition < 0 || scrollDelta == 0) return candidatePosition
        return when {
            scrollDelta < 0 && candidatePosition > currentPosition -> currentPosition
            scrollDelta > 0 && candidatePosition < currentPosition -> currentPosition
            else -> candidatePosition
        }
    }

    private fun visibleEnough(
        pageStart: Int,
        pageEnd: Int,
        viewportStart: Int,
        viewportEnd: Int,
    ): Boolean {
        val visibleExtent = min(pageEnd, viewportEnd) - max(pageStart, viewportStart)
        if (visibleExtent <= 0) return false

        val referenceExtent = min(pageEnd - pageStart, viewportEnd - viewportStart)
        return visibleExtent * 2 >= referenceExtent
    }
}

/**
 * Identity of a reader page that outlives the page object itself.
 *
 * [eu.kanade.tachiyomi.ui.reader.model.ReaderPage] has no `equals`, so it compares by instance.
 * Every adapter rebuild hands out new instances for the same pages, which used to abort the
 * initial-alignment flow silently — the reader stayed transparent and looked like a black screen.
 * The flow matches on this value instead.
 */
internal data class PageIdentity(
    val mangaId: Long?,
    val chapterId: Long?,
    val chapterUrl: String,
    val pageIndex: Int,
)

private const val EDGE_SLOP_DIVISOR = 100
private const val INITIAL_POSITION_TOLERANCE = 1
