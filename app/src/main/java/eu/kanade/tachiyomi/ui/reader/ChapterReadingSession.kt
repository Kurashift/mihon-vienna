package eu.kanade.tachiyomi.ui.reader

internal enum class ChapterEntryDirection {
    Direct,
    Forward,
    Backward,
}

internal data class ChapterProgressDecision(
    val pageIndex: Int,
    val completed: Boolean,
)

internal class ChapterReadingSession(
    private val totalPages: Int,
    entryDirection: ChapterEntryDirection,
    private val alreadyRead: Boolean,
) {

    private val lastIndex = totalPages - 1
    private val settledPages = linkedSetOf<Int>()
    private val protectedBackwardEntry =
        entryDirection == ChapterEntryDirection.Backward && !alreadyRead
    private var completionArmed = !protectedBackwardEntry
    private var lastSettledPage: Int? = null
    private var movedForwardAfterArming = false

    fun onSettled(pageIndex: Int): ChapterProgressDecision? =
        evaluate(pageIndex = pageIndex, completingOnExit = false)

    fun onExit(pageIndex: Int): ChapterProgressDecision? =
        evaluate(pageIndex = pageIndex, completingOnExit = true)

    private fun evaluate(
        pageIndex: Int,
        completingOnExit: Boolean,
    ): ChapterProgressDecision? {
        if (alreadyRead || totalPages <= 0 || pageIndex !in 0..lastIndex) return null

        // Single-page chapters have no page-turn action that can confirm reading; settling on
        // the only page must not mark them as read (the same guard that was previously only
        // applied to protected backward entries).
        if (totalPages == 1) return null

        if (!protectedBackwardEntry) {
            return ChapterProgressDecision(
                pageIndex = pageIndex,
                completed = canComplete(pageIndex, completingOnExit),
            )
        }

        settledPages += pageIndex
        if (!completionArmed && settledPages.size >= minOf(REQUIRED_SETTLED_PAGES, totalPages)) {
            completionArmed = true
            lastSettledPage = pageIndex
            return ChapterProgressDecision(pageIndex = pageIndex, completed = false)
        }
        if (!completionArmed) return null

        val previousPage = lastSettledPage
        if (previousPage != null && pageIndex > previousPage) {
            movedForwardAfterArming = true
        }
        lastSettledPage = pageIndex

        return ChapterProgressDecision(
            pageIndex = pageIndex,
            completed = movedForwardAfterArming && canComplete(pageIndex, completingOnExit),
        )
    }

    private fun canComplete(pageIndex: Int, completingOnExit: Boolean): Boolean {
        return pageIndex == lastIndex ||
            (completingOnExit && pageIndex >= tailCompletionStartIndex(totalPages))
    }

    private companion object {
        const val REQUIRED_SETTLED_PAGES = 3
    }
}

internal fun tailCompletionStartIndex(totalPages: Int): Int {
    if (totalPages <= 0) return Int.MAX_VALUE

    val lastThreePagesStart = (totalPages - MAX_TAIL_COMPLETION_PAGES).coerceAtLeast(0)
    val eightyPercentPageNumber = (totalPages * 4 + 4) / 5
    return maxOf(lastThreePagesStart, eightyPercentPageNumber - 1)
}

private const val MAX_TAIL_COMPLETION_PAGES = 3
