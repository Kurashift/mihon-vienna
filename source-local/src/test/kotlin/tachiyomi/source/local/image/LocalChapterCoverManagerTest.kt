package tachiyomi.source.local.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalChapterCoverManagerTest {

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
