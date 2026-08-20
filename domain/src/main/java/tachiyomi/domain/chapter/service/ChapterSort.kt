package tachiyomi.domain.chapter.service

import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

fun getChapterSort(
    manga: Manga,
    sortDescending: Boolean = manga.sortDescending(),
): (
    Chapter,
    Chapter,
) -> Int {
    val primary: (Chapter, Chapter) -> Int = when (manga.sorting) {
        Manga.CHAPTER_SORTING_SOURCE -> when (sortDescending) {
            true -> { c1, c2 -> c1.sourceOrder.compareTo(c2.sourceOrder) }
            false -> { c1, c2 -> c2.sourceOrder.compareTo(c1.sourceOrder) }
        }
        Manga.CHAPTER_SORTING_NUMBER -> when (sortDescending) {
            true -> { c1, c2 -> c2.chapterNumber.compareTo(c1.chapterNumber) }
            false -> { c1, c2 -> c1.chapterNumber.compareTo(c2.chapterNumber) }
        }
        Manga.CHAPTER_SORTING_UPLOAD_DATE -> when (sortDescending) {
            true -> { c1, c2 -> c2.dateUpload.compareTo(c1.dateUpload) }
            false -> { c1, c2 -> c1.dateUpload.compareTo(c2.dateUpload) }
        }
        Manga.CHAPTER_SORTING_ALPHABET -> when (sortDescending) {
            true -> { c1, c2 -> c2.name.compareToCaseInsensitiveNaturalOrder(c1.name) }
            false -> { c1, c2 -> c1.name.compareToCaseInsensitiveNaturalOrder(c2.name) }
        }
        Manga.CHAPTER_SORTING_TRANSLATED -> when (sortDescending) {
            true -> { c1, c2 -> compareTranslatedNames(c2, c1) }
            false -> { c1, c2 -> compareTranslatedNames(c1, c2) }
        }
        // Custom manual order is always ascending; 0 means unpositioned and sorts at the end,
        // with the chapter name as a stable tie-breaker.
        Manga.CHAPTER_SORTING_CUSTOM -> { c1, c2 ->
            // Unpositioned chapters (customOrder == 0) sort after positioned ones.
            val byOrder = when {
                c1.customOrder == 0L && c2.customOrder != 0L -> 1
                c2.customOrder == 0L && c1.customOrder != 0L -> -1
                else -> c1.customOrder.compareTo(c2.customOrder)
            }
            if (byOrder != 0) {
                byOrder
            } else {
                c1.name.compareToCaseInsensitiveNaturalOrder(c2.name)
            }
        }
        else -> throw NotImplementedError("Invalid chapter sorting method: ${manga.sorting}")
    }

    // Chapters sharing the same sort key (duplicate chapter numbers, equal upload dates, ...)
    // must still compare deterministically. Without this the relative order of those chapters
    // depends on the unspecified row order returned by the database query, so the list visibly
    // re-shuffles every time the chapter flow re-emits (e.g. re-entering the detail screen).
    return { c1, c2 ->
        primary(c1, c2).takeIf { it != 0 } ?: c1.id.compareTo(c2.id)
    }
}

private fun compareTranslatedNames(first: Chapter, second: Chapter): Int {
    val byDisplayedName = (first.translatedNameOrNull ?: first.name)
        .compareToCaseInsensitiveNaturalOrder(second.translatedNameOrNull ?: second.name)
    return byDisplayedName.takeIf { it != 0 }
        ?: first.name.compareToCaseInsensitiveNaturalOrder(second.name)
}

/**
 * Reorders only the visible chapters while leaving filtered-out chapters in their existing slots.
 */
fun mergeVisibleChapterOrder(
    currentIds: List<Long>,
    orderedVisibleIds: List<Long>,
): List<Long> {
    if (currentIds.isEmpty() || orderedVisibleIds.isEmpty()) return currentIds

    val currentIdSet = currentIds.toHashSet()
    val visibleOrder = orderedVisibleIds.distinct().filter { it in currentIdSet }
    if (visibleOrder.isEmpty()) return currentIds

    val visibleIds = visibleOrder.toHashSet()
    val reordered = visibleOrder.iterator()
    return currentIds.map { id ->
        if (id in visibleIds) reordered.next() else id
    }
}
