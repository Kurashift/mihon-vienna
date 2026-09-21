package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FormatTest {

    @Test
    fun `pdf resolves to Pdf and never to Archive`() {
        // The archive reader is libarchive: handing it a PDF throws, and the chapter list it was
        // scanned for would come back empty. Pdf has to win over the archive whitelist.
        val format = Format.valueOf(entry("Chapter 1.pdf"))

        assertEquals(Format.Pdf::class, format::class)
    }

    @Test
    fun `pdf extension matching is case insensitive`() {
        assertEquals(Format.Pdf::class, Format.valueOf(entry("Chapter 1.PDF"))::class)
    }

    @Test
    fun `epub still resolves to Epub`() {
        assertEquals(Format.Epub::class, Format.valueOf(entry("Chapter 1.epub"))::class)
    }

    @Test
    fun `archives still resolve to Archive`() {
        assertEquals(Format.Archive::class, Format.valueOf(entry("Chapter 1.cbz"))::class)
        assertEquals(Format.Archive::class, Format.valueOf(entry("Chapter 1.zip"))::class)
    }

    @Test
    fun `directories still resolve to Directory`() {
        assertEquals(Format.Directory::class, Format.valueOf(entry("Chapter 1", directory = true))::class)
    }

    @Test
    fun `unsupported formats are rejected`() {
        assertThrows(Format.UnknownFormatException::class.java) {
            Format.valueOf(entry("notes.txt"))
        }
    }

    private fun entry(name: String, directory: Boolean = false): UniFile = mockk {
        every { this@mockk.name } returns name
        every { isDirectory } returns directory
    }
}
