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
