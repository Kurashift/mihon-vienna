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
    lastPageRead: Int,
) {

    private val lastIndex = totalPages - 1
    private val protectedBackwardEntry =
        entryDirection == ChapterEntryDirection.Backward && !alreadyRead
    private val backwardUnlockIndex = if (lastPageRead in 1 until totalPages) {
        lastPageRead - 1
    } else {
        minOf(2, lastIndex)
    }

    // Entering a partially read chapter from its previous one starts below the saved position, so
    // scrolling through its first pages (or leaving the reader there) must not downgrade the
    // stored progress. Persistence stays locked until the user scrolls back to the saved page.
    private val protectedForwardEntry =
        entryDirection == ChapterEntryDirection.Forward && !alreadyRead &&
            lastPageRead in 1 until totalPages
    private val forwardUnlockIndex = lastPageRead - 1
    private var reachedUnlockBoundary = false
    private var normalReadingStarted = !protectedBackwardEntry && !protectedForwardEntry
    private var lastObservedPageIndex: Int? = null
    private var forwardBoundaryCrossed = false

    /**
     * Observes a page selected by an actual webtoon scroll. This only changes in-memory guards;
     * database persistence remains tied to [onSettled].
     */
    fun onUserPageSelected(pageIndex: Int) {
        if (pageIndex !in 0..lastIndex) return

        if (protectedBackwardEntry) {
            val previousPageIndex = lastObservedPageIndex
            lastObservedPageIndex = pageIndex

            if (pageIndex <= backwardUnlockIndex) {
                reachedUnlockBoundary = true
            }

            if (reachedUnlockBoundary &&
                previousPageIndex != null &&
                previousPageIndex <= backwardUnlockIndex &&
                pageIndex > backwardUnlockIndex
            ) {
                normalReadingStarted = true
            }
        }

        maybeUnlockForward(pageIndex)
    }

    /** Marks that the user crossed into the next chapter while moving forward. */
    fun markForwardBoundaryCrossed() {
        forwardBoundaryCrossed = true
    }

    /**
     * A protected backward entry may only complete on a forward chapter exit after the user has
     * returned through its saved reading boundary and resumed reading forward.
     */
    fun canCompleteOnForwardExit(): Boolean {
        return !alreadyRead && forwardBoundaryCrossed && normalReadingStarted
    }

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

        maybeUnlockForward(pageIndex)

        if (!normalReadingStarted) return null

        return ChapterProgressDecision(
            pageIndex = pageIndex,
            completed = canComplete(pageIndex, completingOnExit),
        )
    }

    /**
     * A protected forward entry unlocks once the user's position reaches the saved page again; a
     * settle there also counts, so a fling that skips the intermediate pages still resumes.
     */
    private fun maybeUnlockForward(pageIndex: Int) {
        if (protectedForwardEntry &&
            !normalReadingStarted &&
            pageIndex in forwardUnlockIndex..lastIndex
        ) {
            normalReadingStarted = true
        }
    }

    private fun canComplete(pageIndex: Int, completingOnExit: Boolean): Boolean {
        return pageIndex == lastIndex ||
            (completingOnExit && pageIndex >= tailCompletionStartIndex(totalPages))
    }
}

internal fun tailCompletionStartIndex(totalPages: Int): Int {
    if (totalPages <= 0) return Int.MAX_VALUE

    val lastThreePagesStart = (totalPages - MAX_TAIL_COMPLETION_PAGES).coerceAtLeast(0)
    val eightyPercentPageNumber = (totalPages * 4 + 4) / 5
    return maxOf(lastThreePagesStart, eightyPercentPageNumber - 1)
}

private const val MAX_TAIL_COMPLETION_PAGES = 3
