package eu.kanade.tachiyomi.data.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalDeletionReportTest {

    private fun entry(
        name: String,
        outcome: LocalDeletionOutcome = LocalDeletionOutcome.DELETED,
        fileCount: Int = 1,
    ) = LocalDeletionEntry(
        mangaTitle = "[社团] 作品",
        name = name,
        relativePath = "作品/$name",
        fileCount = fileCount,
        sizeBytes = 1024L,
        outcome = outcome,
    )

    private fun report(
        entries: List<LocalDeletionEntry>,
        cancelled: Boolean = false,
        skippedCount: Int = 0,
    ) = LocalDeletionReport(
        id = 1L,
        unit = LocalDeletionUnit.CHAPTER,
        entries = entries,
        cancelled = cancelled,
        skippedCount = skippedCount,
        deletedMangaIds = emptySet(),
        finishedAt = 0L,
    )

    @Test
    fun `one deleted entry only needs a toast`() {
        assertTrue(report(listOf(entry("第01话.cbz"))).isSingleSuccess)
    }

    @Test
    fun `two entries need the result dialog`() {
        assertFalse(report(listOf(entry("第01话.cbz"), entry("第02话.cbz"))).isSingleSuccess)
    }

    @Test
    fun `a stopped run is never a single success`() {
        assertFalse(report(listOf(entry("第01话.cbz")), cancelled = true).isSingleSuccess)
    }

    @Test
    fun `a failed entry is never a single success`() {
        assertFalse(
            report(listOf(entry("第01话.cbz", LocalDeletionOutcome.FAILED))).isSingleSuccess,
        )
    }

    @Test
    fun `csv escapes separators and quotes`() {
        val csv = LocalDeletionReportCsv.encode(report(listOf(entry("a,b\"c.cbz"))))

        assertTrue(csv.contains("\"a,b\"\"c.cbz\""))
    }

    @Test
    fun `csv starts with a bom and one header row`() {
        val csv = LocalDeletionReportCsv.encode(report(listOf(entry("第01话.cbz"))))

        assertTrue(csv.startsWith("\uFEFF作品,章节,路径,大小,结果,时间"))
        assertEquals(2, csv.lines().size)
    }

    @Test
    fun `a folder carries its file count into the list`() {
        val csv = LocalDeletionReportCsv.encode(
            report(listOf(entry("第05话/", fileCount = 142))),
        )

        assertTrue(csv.contains("第05话/ (142 个文件)"))
    }
}
