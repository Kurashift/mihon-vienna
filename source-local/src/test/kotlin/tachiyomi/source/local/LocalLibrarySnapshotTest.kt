package tachiyomi.source.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.source.local.io.LOCAL_SINGLE_CHAPTER_NAME

class LocalLibrarySnapshotTest {
    @Test
    fun `unknown cache differs from a confirmed empty library`() {
        assertNull(LocalLibrarySnapshot("root", null, emptyMap()).workCount)
        assertNull(LocalLibrarySnapshot("root", null, emptyMap()).chapterCount)
        assertEquals(0L, LocalLibrarySnapshot("root", emptyMap(), emptyMap()).workCount)
        assertEquals(0L, LocalLibrarySnapshot("root", emptyMap(), emptyMap()).chapterCount)
    }

    @Test
    fun `full filename identities preserve formats and the single chapter sentinel`() {
        val names = setOf("same.cbz", "same.pdf", LOCAL_SINGLE_CHAPTER_NAME)
        assertEquals(
            names.mapTo(linkedSetOf()) { "work/$it" },
            localLibraryChapterUrls("work", 3, 100, 100, names),
        )
    }

    @Test
    fun `mismatched or missing private indexes cannot assert chapter identities`() {
        assertNull(localLibraryChapterUrls("work", 2, 100, 100, setOf("one.cbz")))
        assertNull(localLibraryChapterUrls("work", 1, 100, 99, setOf("one.cbz")))
        assertNull(localLibraryChapterUrls("work", 1, 100, 100, null))
        assertNull(localLibraryChapterUrls("work", 1, 100, 100, setOf("nested/one.cbz")))
    }

    @Test
    fun `unknown chapter count does not become a partial sum`() {
        val snapshot = LocalLibrarySnapshot("root", mapOf("one" to 3L, "two" to -1L), emptyMap())
        assertEquals(2L, snapshot.workCount)
        assertNull(snapshot.chapterCount)
    }
}
