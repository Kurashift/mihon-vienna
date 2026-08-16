package tachiyomi.source.local.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalChapterCoverManagerTest {

    @Test
    fun `unchanged chapter keeps the same cache file`() {
        val first = localChapterCoverCacheFileName("Author/Story.cbz", 1024L, 123L)
        val second = localChapterCoverCacheFileName("Author/Story.cbz", 1024L, 123L)

        assertEquals(first, second)
        assertTrue(first.endsWith(".webp"))
    }

    @Test
    fun `file changes invalidate the cached cover`() {
        val original = localChapterCoverCacheFileName("Author/Story.cbz", 1024L, 123L)
        val resized = localChapterCoverCacheFileName("Author/Story.cbz", 2048L, 123L)
        val modified = localChapterCoverCacheFileName("Author/Story.cbz", 1024L, 456L)

        assertNotEquals(original, resized)
        assertNotEquals(original, modified)
    }

    @Test
    fun `renamed chapter receives a different cache file`() {
        val original = localChapterCoverCacheFileName("Author/Story.cbz", 1024L, 123L)
        val renamed = localChapterCoverCacheFileName("Author/Renamed.cbz", 1024L, 123L)

        assertNotEquals(original, renamed)
    }
}
