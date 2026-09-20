package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.MarkFilter
import eu.kanade.tachiyomi.ui.manga.ChapterScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalMarkFilterTest {

    /**
     * Only a mark filter narrows a work's own page. "Not on a shelf" says where the work is filed
     * rather than which chapters were kept, so it opens the work whole like an unfiltered list.
     */
    @Test
    fun `mark filters map to the displayed part of a work`() {
        assertEquals(ChapterScope.FLAGGED, MarkFilter.FLAGGED.toChapterScope())
        assertEquals(ChapterScope.GOOD_DOUJIN, MarkFilter.GOOD_DOUJIN.toChapterScope())
        assertEquals(ChapterScope.ALL, MarkFilter.NONE.toChapterScope())
        assertEquals(ChapterScope.ALL, MarkFilter.NOT_IN_LIBRARY.toChapterScope())
    }
}
