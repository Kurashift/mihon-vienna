package eu.kanade.tachiyomi.ui.reader

/**
 * Which chapter should follow [current] in the reader when skip-read is on.
 *
 * The reader's chapter chain itself is never trimmed: trimming it once at open time makes the
 * next chapter a distant one in the original order, and a chapter that was merely half-read can
 * disappear from the chain altogether. Instead only the "next" slot is chosen here, and only when
 * the user is continuing onward from a chapter that was unread when it became active.
 *
 * Re-reading from an already-read chapter keeps the original adjacency, so a user who wants to
 * walk through read chapters in order still can. When no unread chapter follows, the immediate
 * neighbour is kept as a fallback.
 *
 * The previous chapter is deliberately left untouched. Recomputing both directions on every
 * chapter change was what made the old dynamic version rebuild the viewport (a far, never-loaded
 * prev chapter inserted next to the visible transition), so only the forward slot is gated.
 *
 * Kept free of Android/Compose types so the rule can be exercised by a plain JVM unit test.
 */
internal object ReaderNextChapterRule {

    /**
     * @param chapters the full, untrimmed chapter chain in reading order.
     * @param currentPos index of the active chapter in [chapters].
     * @param currentWasUnread whether the active chapter was unread when it became active.
     * @param skipRead whether the skip-read preference is on.
     * @param readAt whether the chapter at an index is marked read.
     * @return index of the chapter that should occupy the "next" slot, or null if there is none.
     */
    fun nextIndex(
        chaptersSize: Int,
        currentPos: Int,
        currentWasUnread: Boolean,
        skipRead: Boolean,
        readAt: (Int) -> Boolean,
    ): Int? {
        val adjacent = currentPos + 1
        if (adjacent >= chaptersSize) return null
        if (!skipRead || !currentWasUnread) return adjacent

        // Continue to the first unread chapter ahead. Half-read chapters have read = false, so
        // they stay in the chain and are never skipped.
        for (index in adjacent until chaptersSize) {
            if (!readAt(index)) return index
        }
        // Everything ahead is read: fall back to the immediate neighbour rather than dropping
        // the transition entirely, so re-reading forward still works.
        return adjacent
    }
}
