package tachiyomi.source.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalSourceChapterChangesTest {

    @Test
    fun `legacy chapter name index can resolve supported files`() {
        assertEquals(
            listOf(
                "Chapter 1",
                "Chapter 1.cbz",
                "Chapter 1.zip",
                "Chapter 1.cbr",
                "Chapter 1.rar",
                "Chapter 1.cb7",
                "Chapter 1.7z",
                "Chapter 1.cbt",
                "Chapter 1.tar",
                "Chapter 1.epub",
            ),
            chapterFileNameCandidates("Chapter 1"),
        )
    }

    @Test
    fun `database chapter url uses one exact lookup`() {
        assertEquals(
            listOf("Chapter 1.cbz"),
            chapterFileNameCandidates("Chapter 1.cbz"),
        )
        assertEquals("Chapter 1", chapterBaseName("Chapter 1.cbz"))
    }

    @Test
    fun `unchanged chapter files do not request a sync`() {
        assertFalse(
            chapterFileSetChanged(
                existingChapterUrls = listOf("Author/Story.cbz"),
                currentChapterUrls = setOf("Author/Story.cbz"),
                isAccessible = true,
                isConfirmedEmpty = false,
            ),
        )
    }

    @Test
    fun `added chapter file requests a sync`() {
        assertTrue(
            chapterFileSetChanged(
                existingChapterUrls = listOf("Author/Story.cbz"),
                currentChapterUrls = setOf("Author/Story.cbz", "Author/New story.cbz"),
                isAccessible = true,
                isConfirmedEmpty = false,
            ),
        )
    }

    @Test
    fun `existing disk chapters restore an empty database list`() {
        assertTrue(
            chapterFileSetChanged(
                existingChapterUrls = emptyList(),
                currentChapterUrls = setOf("Author/Story.cbz"),
                isAccessible = true,
                isConfirmedEmpty = false,
            ),
        )
    }

    @Test
    fun `transient empty result does not request a sync`() {
        assertFalse(
            chapterFileSetChanged(
                existingChapterUrls = listOf("Author/Story.cbz"),
                currentChapterUrls = emptySet(),
                isAccessible = true,
                isConfirmedEmpty = false,
            ),
        )
    }

    @Test
    fun `confirmed deletion of all chapters requests a sync`() {
        assertTrue(
            chapterFileSetChanged(
                existingChapterUrls = listOf("Author/Story.cbz"),
                currentChapterUrls = emptySet(),
                isAccessible = true,
                isConfirmedEmpty = true,
            ),
        )
    }

    @Test
    fun `unavailable directory does not request a sync`() {
        assertFalse(
            chapterFileSetChanged(
                existingChapterUrls = listOf("Author/Story.cbz"),
                currentChapterUrls = emptySet(),
                isAccessible = false,
                isConfirmedEmpty = false,
            ),
        )
    }
}
