package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.MarkFilter
import eu.kanade.tachiyomi.ui.manga.ChapterScope

/**
 * The part of a work the details screen should show when opened from a list narrowed by [this].
 *
 * A mark filter names the chapters that put a work on the list, so the work's own page opens
 * showing those rather than making the reader find them again among all of its chapters.
 *
 * "Not on a shelf" is not a mark: it says where the work is filed, not which of its chapters were
 * kept, so it opens the work whole like an unfiltered list does. The reading filters are left out
 * for the same kind of reason - they describe the work, not its chapters.
 */
internal fun MarkFilter.toChapterScope(): ChapterScope = when (this) {
    MarkFilter.NONE,
    MarkFilter.NOT_IN_LIBRARY,
    -> ChapterScope.ALL

    MarkFilter.FLAGGED -> ChapterScope.FLAGGED
    MarkFilter.GOOD_DOUJIN -> ChapterScope.GOOD_DOUJIN
}
