package tachiyomi.source.local.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalChapterCoverManagerTest {

    @Test
    fun `webp covers are picked as the designated cover file`() {
        // The generated cover cache is WebP too, so a chapter's own cover.webp must be preferred
        // over the numbered pages just like cover.jpg is.
        assertTrue(isChapterCoverName("cover.webp"))
        assertTrue(isChapterCoverName("cover.WEBP"))
        assertTrue(isChapterCoverName("cover.jpg"))
        assertTrue(isChapterCoverName("Cover.JPEG"))
        assertTrue(isChapterCoverName("cover.png"))
        assertFalse(isChapterCoverName("cover.xml"))
        assertFalse(isChapterCoverName("001.webp"))
        assertFalse(isChapterCoverName(".thumb.webp"))
    }

    @Test
    fun `custom cover name depends only on stable chapter id`() {
        val first = localCustomChapterCoverFileName(123L)
        val afterMove = localCustomChapterCoverFileName(123L)
        val otherChapter = localCustomChapterCoverFileName(456L)

        assertEquals(first, afterMove)
        assertNotEquals(first, otherChapter)
        assertEquals("chapter-123.webp", first)
    }

    @Test
    fun `custom cover identity is independent from generated cache identity`() {
        val custom = localCustomChapterCoverFileName(123L)
        val generated = localChapterCoverCacheFileName("Author/Story.cbz", 1024L, 123L, 480, 672, 80)

        assertNotEquals(custom, generated)
    }

    @Test
    fun `unchanged chapter keeps the same cache file`() {
        val first = localChapterCoverCacheFileName("Author/Story.cbz", 1024L, 123L, 480, 672, 80)
        val second = localChapterCoverCacheFileName("Author/Story.cbz", 1024L, 123L, 480, 672, 80)

        assertEquals(first, second)
        assertTrue(first.endsWith(".webp"))
    }

    @Test
    fun `file changes invalidate the cached cover`() {
        val original = localChapterCoverCacheFileName("Author/Story.cbz", 1024L, 123L, 480, 672, 80)
        val resized = localChapterCoverCacheFileName("Author/Story.cbz", 2048L, 123L, 480, 672, 80)
        val modified = localChapterCoverCacheFileName("Author/Story.cbz", 1024L, 456L, 480, 672, 80)

        assertNotEquals(original, resized)
        assertNotEquals(original, modified)
    }

    @Test
    fun `renamed chapter receives a different cache file`() {
        val original = localChapterCoverCacheFileName("Author/Story.cbz", 1024L, 123L, 480, 672, 80)
        val renamed = localChapterCoverCacheFileName("Author/Renamed.cbz", 1024L, 123L, 480, 672, 80)

        assertNotEquals(original, renamed)
    }

    @Test
    fun `resolution changes invalidate the cached cover`() {
        val listSize = localChapterCoverCacheFileName("Author/Story.cbz", 1024L, 123L, 336, 504, 80)
        val gridSize = localChapterCoverCacheFileName("Author/Story.cbz", 1024L, 123L, 480, 672, 80)
        val lowerQuality = localChapterCoverCacheFileName("Author/Story.cbz", 1024L, 123L, 480, 672, 78)

        assertNotEquals(listSize, gridSize)
        assertNotEquals(gridSize, lowerQuality)
    }
}
