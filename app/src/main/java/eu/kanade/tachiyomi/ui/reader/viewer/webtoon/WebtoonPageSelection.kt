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

    fun isInitialPositionReady(
        pageExtent: Int,
        currentStart: Int?,
        targetStart: Int,
        acceptClampedPosition: Boolean,
    ): Boolean {
        if (pageExtent <= 0 || currentStart == null) return false
        return acceptClampedPosition || abs(currentStart - targetStart) <= INITIAL_POSITION_TOLERANCE
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

private const val EDGE_SLOP_DIVISOR = 100
private const val INITIAL_POSITION_TOLERANCE = 1
