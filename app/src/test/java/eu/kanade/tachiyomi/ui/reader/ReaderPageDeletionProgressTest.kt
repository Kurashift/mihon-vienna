package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ReaderPageDeletionProgressTest {

    @Test
    fun `reindexing keeps loaded page objects and original page positions`() {
        val first = ReaderPage(0)
        val removed = ReaderPage(1)
        val third = ReaderPage(2)

        val remaining = reindexReaderPages(listOf(first, third))

        assertSame(first, remaining[0])
        assertSame(third, remaining[1])
        assertEquals(1, third.index)
        assertEquals(2, third.originalIndex)

        val restored = reindexReaderPages(listOf(first, removed, third))
        assertSame(third, restored[2])
        assertEquals(2, third.index)
    }

    @Test
    fun `deleting pages before progress shifts it back`() {
        assertEquals(
            8 to 5,
            adjustedPageProgressAfterDeletion(
                totalPages = 10,
                lastPageRead = 6,
                wasRead = false,
                removedPageIndexes = listOf(1, 8),
            ),
        )
    }

    @Test
    fun `completed chapter remains completed after page deletion`() {
        assertEquals(
            7 to 7,
            adjustedPageProgressAfterDeletion(
                totalPages = 9,
                lastPageRead = 9,
                wasRead = true,
                removedPageIndexes = listOf(2, 5),
            ),
        )
    }

    @Test
    fun `deleting later pages leaves earlier progress unchanged`() {
        assertEquals(
            6 to 3,
            adjustedPageProgressAfterDeletion(
                totalPages = 8,
                lastPageRead = 3,
                wasRead = false,
                removedPageIndexes = listOf(4, 6),
            ),
        )
    }

    @Test
    fun `an older failed batch restores only its pages and keeps newer deletions hidden`() {
        val pages = List(6) { ReaderPage(it) }
        val visible = reindexReaderPages(listOf(pages[0], pages[3], pages[5]))

        val restored = restoreFailedPageDeletions(visible, listOf(pages[1]))

        assertEquals(listOf(pages[0], pages[1], pages[3], pages[5]), restored)
        assertEquals(listOf(0, 1, 2), visible.map { it.index })
    }

    @Test
    fun `a second batch adjusts progress from its own snapshot instead of original image indexes`() {
        val pages = List(8) { ReaderPage(it) }
        val afterFirstBatch = reindexReaderPages(pages.filter { it !== pages[1] })
        val removedIndex = afterFirstBatch.indexOf(pages[4])

        assertEquals(
            6 to 4,
            adjustedPageProgressAfterDeletion(
                totalPages = afterFirstBatch.size,
                lastPageRead = 5,
                wasRead = false,
                removedPageIndexes = listOf(removedIndex),
            ),
        )
    }
}
