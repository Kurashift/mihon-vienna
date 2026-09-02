package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchiveTest {

    @Test
    fun `archive with an ellipsis title is a chapter`() {
        assertTrue(Archive.isChapterEntry(entry("...Dakara Ore wa Imouto ni Saimin o Kaketa [Chinese].cbz")))
    }

    @Test
    fun `archive with a single leading dot is a chapter`() {
        assertTrue(Archive.isChapterEntry(entry(".Hentai.cbz")))
    }

    @Test
    fun `epub with an ellipsis title is a chapter`() {
        assertTrue(Archive.isChapterEntry(entry("...Dakara.epub")))
    }

    @Test
    fun `plain archive and chapter directory are chapters`() {
        assertTrue(Archive.isChapterEntry(entry("Chapter 1.cbz")))
        assertTrue(Archive.isChapterEntry(entry("Chapter 1", directory = true)))
    }

    @Test
    fun `dot-prefixed directory stays hidden`() {
        assertFalse(Archive.isChapterEntry(entry(".thumbnails", directory = true)))
    }

    @Test
    fun `generated and unsupported dot files stay hidden`() {
        assertFalse(Archive.isChapterEntry(entry(".nomedia")))
        assertFalse(Archive.isChapterEntry(entry(".noxml")))
        assertFalse(Archive.isChapterEntry(entry(".DS_Store")))
    }

    @Test
    fun `unsupported formats are not chapters`() {
        assertFalse(Archive.isChapterEntry(entry("notes.txt")))
        assertFalse(Archive.isChapterEntry(entry("cover.jpg")))
    }

    private fun entry(name: String, directory: Boolean = false): UniFile = mockk {
        every { this@mockk.name } returns name
        every { isDirectory } returns directory
    }
}
