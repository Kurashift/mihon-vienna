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

/**
 * The works a random pick may land on under [scope], or null when it narrows nothing.
 *
 * A jump has to stay inside the part the reader is looking at: landing on a work that carries
 * none of the marked chapters the destination then tries to show opens an empty page. Marks are
 * stored against the chapter while this pool is made of works, so the two lists are folded to
 * their work ids here.
 *
 * Both inputs are the works of a mark store, and the scopes read them exactly as [includes]
 * reads the chapter-level sets - same store, same scope.
 */
fun ChapterScope.randomPoolMangaIds(
    markedMangaIds: Collection<Long>,
    goodDoujinMangaIds: Collection<Long>,
): Set<Long>? = when (this) {
    ChapterScope.ALL -> null
    ChapterScope.FLAGGED -> markedMangaIds.toHashSet()
    ChapterScope.GOOD_DOUJIN -> goodDoujinMangaIds.toHashSet()
}

/**
 * The scope a random *good doujin* jump opens with.
 *
 * That gesture always draws from the good-doujin list, whichever filter the screen it started
 * from happens to carry, so it can only hand over a scope the pool agrees with. Under
 * [ChapterScope.GOOD_DOUJIN] - the one mode where the reader did ask for these chapters - the
 * destination keeps it. Under every other scope, including no narrowing at all, it opens the work
 * whole.
 *
 * Keeping 标记 here would be the one combination that cannot work: the pick never consulted the
 * mark filter, so it lands on a work that may carry no marked chapters at all, and the destination
 * would open on an empty list. The reader did not ask for a mark-narrowed page either - they
 * dragged from a screen whose mode says nothing about which chapters to show.
 */
fun ChapterScope.opensGoodDoujinJump(): ChapterScope = when (this) {
    ChapterScope.GOOD_DOUJIN -> ChapterScope.GOOD_DOUJIN
    ChapterScope.ALL,
    ChapterScope.FLAGGED,
    -> ChapterScope.ALL
}
