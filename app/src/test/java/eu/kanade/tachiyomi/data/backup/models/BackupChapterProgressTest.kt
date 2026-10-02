package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackupChapterProgressTest {

    @Test
    fun `completed restore is normalized to final page`() {
        assertEquals(12, normalizeRestoredLastPageRead(read = true, lastPageRead = 8, totalPages = 12))
    }

    @Test
    fun `unread restore cannot occupy completion slot`() {
        assertEquals(11, normalizeRestoredLastPageRead(read = false, lastPageRead = 12, totalPages = 12))
        assertEquals(0, normalizeRestoredLastPageRead(read = false, lastPageRead = 1, totalPages = 1))
    }

    @Test
    fun `unknown page count preserves nonnegative progress`() {
        assertEquals(7, normalizeRestoredLastPageRead(read = false, lastPageRead = 7, totalPages = 0))
        assertEquals(0, normalizeRestoredLastPageRead(read = false, lastPageRead = -1, totalPages = 0))
    }

    @Test
    fun `a chapter written with the columns swapped restores with its real page count`() {
        // 22-page chapter read to page 2, stored as total_pages=2, last_page_read=22.
        val (totalPages, progress) = unswapRestoredProgress(
            read = false,
            lastPageRead = 22,
            totalPages = 2,
            scannedPageCount = 22,
        )

        assertEquals(22, totalPages)
        assertEquals(2, progress)
    }

    @Test
    fun `a chapter that was never opened restores as unread with its real page count`() {
        // The same swap with progress 0: total_pages=0, last_page_read=24.
        val (totalPages, progress) = unswapRestoredProgress(
            read = false,
            lastPageRead = 24,
            totalPages = 0,
            scannedPageCount = 24,
        )

        assertEquals(24, totalPages)
        assertEquals(0, progress)
    }

    @Test
    fun `a healthy chapter is left exactly as stored`() {
        val (totalPages, progress) = unswapRestoredProgress(
            read = false,
            lastPageRead = 5,
            totalPages = 22,
            scannedPageCount = 22,
        )

        assertEquals(22, totalPages)
        assertEquals(5, progress)
    }

    @Test
    fun `the swap is not inferred without a scanned page count to prove it`() {
        // A cloud chapter has no page count in its memo, so a progress equal to the stored page
        // count is not evidence of anything and must not be rewritten.
        val (totalPages, progress) = unswapRestoredProgress(
            read = false,
            lastPageRead = 20,
            totalPages = 20,
            scannedPageCount = 0,
        )

        assertEquals(20, totalPages)
        assertEquals(20, progress)
    }

    @Test
    fun `a finished chapter is never treated as swapped`() {
        val (totalPages, progress) = unswapRestoredProgress(
            read = true,
            lastPageRead = 22,
            totalPages = 22,
            scannedPageCount = 22,
        )

        assertEquals(22, totalPages)
        assertEquals(22, progress)
    }

    @Test
    fun `a page count that does not match the progress is not a swap`() {
        // The file's progress happens to equal the scan's count, but the stored page count is
        // larger than it, so the row cannot be one of the swapped ones.
        val (totalPages, progress) = unswapRestoredProgress(
            read = false,
            lastPageRead = 22,
            totalPages = 30,
            scannedPageCount = 22,
        )

        assertEquals(30, totalPages)
        assertEquals(22, progress)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `custom chapter cover survives backup serialization`() {
        val original = BackupChapter(
            url = "Author/Story.cbz",
            name = "Story",
            read = true,
            totalPages = 12,
            translatedName = "故事",
            customCover = byteArrayOf(1, 2, 3, 4),
        ).apply { chapterId = 99 }

        val encoded = ProtoBuf.encodeToByteArray(BackupChapter.serializer(), original)
        val restored = ProtoBuf.decodeFromByteArray(BackupChapter.serializer(), encoded)

        assertArrayEquals(original.customCover, restored.customCover)
        assertEquals("故事", restored.translatedName)
        assertEquals(true, restored.read)
        assertEquals(0, restored.chapterId)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `finish timestamp survives backup serialization and reaches the chapter`() {
        val original = BackupChapter(
            url = "Author/Story.cbz",
            name = "Story",
            read = true,
            totalPages = 12,
            markedReadAt = 1_700_000_000_000,
        )

        val encoded = ProtoBuf.encodeToByteArray(BackupChapter.serializer(), original)
        val restored = ProtoBuf.decodeFromByteArray(BackupChapter.serializer(), encoded)

        assertEquals(1_700_000_000_000, restored.markedReadAt)
        assertEquals(1_700_000_000_000, restored.toChapterImpl().markedReadAt)
    }

    @Test
    fun `a backup without the field restores as no timestamp rather than zero`() {
        // Backups written before the field existed decode to 0, which the restorer reads as
        // "nothing recorded" and leaves the device's own date alone.
        assertEquals(0, BackupChapter(url = "Author/Story.cbz", name = "Story").markedReadAt)
    }
}
