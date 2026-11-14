package mihon.core.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ArchiveFormatTest {

    @Test
    fun `zip family maps to the zip writer`() {
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.fromFileName("chapter.zip"))
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.fromFileName("chapter.cbz"))
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.fromFileName("CHAPTER.CBZ"))
    }

    @Test
    fun `7z family maps to the 7z writer`() {
        assertEquals(ArchiveFormat.SEVEN_ZIP, ArchiveFormat.fromFileName("chapter.7z"))
        assertEquals(ArchiveFormat.SEVEN_ZIP, ArchiveFormat.fromFileName("chapter.cb7"))
        assertEquals(ArchiveFormat.SEVEN_ZIP, ArchiveFormat.fromFileName("Chapter.CB7"))
    }

    @Test
    fun `tar family maps to the tar writer`() {
        assertEquals(ArchiveFormat.TAR, ArchiveFormat.fromFileName("chapter.tar"))
        assertEquals(ArchiveFormat.TAR, ArchiveFormat.fromFileName("chapter.cbt"))
        assertEquals(ArchiveFormat.TAR, ArchiveFormat.fromFileName("Chapter.CBT"))
    }

    @Test
    fun `rar stays read-only because libarchive cannot write it`() {
        assertNull(ArchiveFormat.fromFileName("chapter.rar"))
        assertNull(ArchiveFormat.fromFileName("chapter.cbr"))
        assertNull(ArchiveFormat.fromFileName("Chapter.CBR"))
    }

    @Test
    fun `unknown and missing extensions are not writable`() {
        assertNull(ArchiveFormat.fromFileName(null))
        assertNull(ArchiveFormat.fromFileName(""))
        assertNull(ArchiveFormat.fromFileName("chapter"))
        assertNull(ArchiveFormat.fromFileName("chapter."))
    }

    @Test
    fun `only the last extension decides`() {
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.fromFileName("chapter.v2.cbz"))
        assertEquals(ArchiveFormat.TAR, ArchiveFormat.fromFileName("dir.with.dots/chapter.tar"))
    }
}
