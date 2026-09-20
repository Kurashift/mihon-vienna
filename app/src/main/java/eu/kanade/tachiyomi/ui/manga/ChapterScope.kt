package eu.kanade.tachiyomi.ui.manga

/**
 * Which of a work's chapters the details screen shows.
 *
 * The library list can be narrowed to the works carrying a mark, and the chapters that put a work
 * on that list are exactly the ones the reader came for. Reaching such a work and being shown all
 * of its chapters makes them hunt for the two or three marks again, so the narrowing travels with
 * the visit.
 *
 * It deliberately covers the mark filters only. 在读/剩余/读完 describe the work, not its chapters
 * ("does this one still have something to read"), and the details screen has its own unread
 * filter; quietly hiding read chapters would also hide the chapter the reader just finished.
 *
 * This is a property of one visit rather than a per-work setting: it is handed over through the
 * route like the browse list's random pool, never stored in the work's chapter flags. Leaving and
 * re-entering the work, or opening it from the shelf, shows everything again.
 */
enum class ChapterScope {
    ALL,
    FLAGGED,
    GOOD_DOUJIN,
}

/**
 * Whether a chapter belongs to the part of the work [scope] shows.
 *
 * Both id sets are the chapter-level marks of this very work, so a chapter is in at most one of
 * them per store and the two are independent: a work can be flagged without being a good doujin.
 * The sets are read as given - an empty one simply matches nothing, which is what a list narrowed
 * to a mark the work no longer carries should show.
 */
fun ChapterScope.includes(
    chapterId: Long,
    markedChapterIds: Set<Long>,
    goodDoujinChapterIds: Set<Long>,
): Boolean = when (this) {
    ChapterScope.ALL -> true
    ChapterScope.FLAGGED -> chapterId in markedChapterIds
    ChapterScope.GOOD_DOUJIN -> chapterId in goodDoujinChapterIds
}
