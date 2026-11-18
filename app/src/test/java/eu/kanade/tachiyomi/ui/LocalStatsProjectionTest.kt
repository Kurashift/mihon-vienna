package eu.kanade.tachiyomi.ui.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.source.local.LocalLibrarySnapshot

class LocalStatsProjectionTest {
    @Test
    fun `surviving work excludes deleted read chapter and includes unread replacement`() {
        val snapshot = LocalLibrarySnapshot(
            "root",
            mapOf("work" to 2L),
            mapOf("work" to setOf("work/A.cbz", "work/C.cbz")),
        )
        val chapters = listOf(chapter(1, "work/A.cbz", true), chapter(2, "work/B.cbz", true))
        val result = localStatsProjection(snapshot, mapOf("work" to 10L), chapters)
        assertEquals(1L, result.workCount)
        assertEquals(2L, result.chapterCount)
        assertEquals(1L, result.readChapterCount)
    }

    @Test
    fun `missing identities stay unknown instead of reporting partial read count`() {
        val snapshot = LocalLibrarySnapshot("root", mapOf("work" to 2L), emptyMap())
        assertNull(localStatsProjection(snapshot, mapOf("work" to 10L), emptyList()).readChapterCount)
        assertEquals(0L, localStatsProjection(snapshot, emptyMap(), emptyList()).readChapterCount)
    }

    @Test
    fun `partial reading and duplicate database rows do not inflate read chapters`() {
        val snapshot = LocalLibrarySnapshot(
            "root",
            mapOf("work" to 2L),
            mapOf("work" to setOf("work/A.cbz", "work/B.cbz")),
        )
        val chapters = listOf(
            chapter(1, "work/A.cbz", true),
            chapter(2, "work/A.cbz", true),
            chapter(3, "work/B.cbz", false).copy(lastPageRead = 10),
            chapter(4, "gone/C.cbz", true).copy(mangaId = 99),
        )
        assertEquals(1L, localStatsProjection(snapshot, mapOf("work" to 10L), chapters).readChapterCount)
    }

    private fun chapter(id: Long, url: String, read: Boolean): Chapter = Chapter.create().copy(
        id = id,
        mangaId = 10,
        url = url,
        read = read,
    )
}
