package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ChapterTransitionTest {

    private fun chapter(id: Long) = ReaderChapter(
        ChapterImpl().apply {
            this.id = id
            url = "url-$id"
            name = "chapter-$id"
        },
    )

    @Test
    fun `a next transition defaults to no skipped reads`() {
        assertEquals(0, ChapterTransition.Next(chapter(1), chapter(2)).skippedReadCount)
    }

    @Test
    fun `skipped read count is part of transition identity`() {
        val from = chapter(1)
        val to = chapter(2)
        val plain = ChapterTransition.Next(from, to, 0)
        val skipped = ChapterTransition.Next(from, to, 3)

        // DiffUtil compares transitions by equality; a changed skip count must not be swallowed.
        assertNotEquals(plain, skipped)
        assertNotEquals(plain.hashCode(), skipped.hashCode())
        assertEquals(skipped, ChapterTransition.Next(from, to, 3))
    }

    @Test
    fun `prev and next transitions are never equal`() {
        val from = chapter(1)
        val to = chapter(2)

        assertNotEquals(ChapterTransition.Prev(from, to), ChapterTransition.Next(from, to))
    }

    @Test
    fun `viewer chapters default to no skipped reads`() {
        val chapters = ViewerChapters(chapter(1), null, chapter(2))

        assertEquals(0, chapters.nextSkippedReadCount)
    }
}
