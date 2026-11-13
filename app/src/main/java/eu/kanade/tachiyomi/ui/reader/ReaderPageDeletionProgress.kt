package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

internal fun adjustedPageProgressAfterDeletion(
    totalPages: Int,
    lastPageRead: Int,
    wasRead: Boolean,
    removedPageIndexes: List<Int>,
): Pair<Int, Int> {
    val newTotal = (totalPages - removedPageIndexes.size).coerceAtLeast(0)
    val newLastPageRead = if (wasRead) {
        newTotal
    } else {
        (lastPageRead - removedPageIndexes.count { it < lastPageRead }).coerceIn(0, newTotal)
    }
    return newTotal to newLastPageRead
}

internal fun reindexReaderPages(pages: List<ReaderPage>): List<ReaderPage> = pages.onEachIndexed { index, page ->
    page.index = index
}

internal fun restoreFailedPageDeletions(
    visiblePages: List<ReaderPage>,
    failedPages: List<ReaderPage>,
): List<ReaderPage> = (visiblePages + failedPages).distinct().sortedBy { it.originalIndex }
