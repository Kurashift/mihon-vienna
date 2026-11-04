package tachiyomi.core.common.util.system

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The page rule is deliberately stricter than [ImageUtil.isImage]: a download client leaves a
 * thumbnail next to the pages as a file called `.thumb`, and its bytes are a JPEG, so sniffing the
 * header would accept it as an extra page at the end of the chapter.
 */
class PageImageNameTest {

    @Test
    fun `a leftover thumbnail file is not a page`() {
        assertFalse(PageImageName.isPage(".thumb"))
    }

    @Test
    fun `dot-prefixed page file names stay pages`() {
        // Page files are commonly named like this, so a leading dot alone is not junk.
        assertTrue(PageImageName.isPage(".001.webp"))
        assertTrue(PageImageName.isPage(".001.jpg"))
    }

    @Test
    fun `known image extensions are pages`() {
        assertTrue(PageImageName.isPage("001.jpg"))
        assertTrue(PageImageName.isPage("001.JPEG"))
        assertTrue(PageImageName.isPage("cover.png"))
        assertTrue(PageImageName.isPage("002.webp"))
        assertTrue(PageImageName.isPage("003.avif"))
    }

    @Test
    fun `the shared extension table covers webp and common spellings`() {
        // Local import reuses this table, so a readable format must not be silently skipped.
        assertTrue(PageImageName.hasImageExtension("001.webp"))
        assertTrue(PageImageName.hasImageExtension("001.WEBP"))
        assertTrue(PageImageName.hasImageExtension("cover.webp"))
        assertTrue(PageImageName.hasImageExtension("001.jpeg"))
        assertTrue(PageImageName.hasImageExtension("001.JPG"))
        assertTrue(PageImageName.hasImageExtension("001.png"))
        assertTrue(PageImageName.hasImageExtension("001.gif"))
        assertTrue(PageImageName.hasImageExtension("001.avif"))
        assertTrue(PageImageName.hasImageExtension("001.heif"))
        assertTrue(PageImageName.hasImageExtension("001.jxl"))
        assertFalse(PageImageName.hasImageExtension("ComicInfo.xml"))
        assertFalse(PageImageName.hasImageExtension("chapter.cbz"))
    }

    @Test
    fun `non-image extensions are not pages`() {
        assertFalse(PageImageName.isPage("info.txt"))
        assertFalse(PageImageName.isPage(".ehviewer"))
        assertFalse(PageImageName.isPage("ComicInfo.xml"))
        assertFalse(PageImageName.isPage("chapter.cbz"))
    }

    @Test
    fun `a null name is not a page`() {
        assertFalse(PageImageName.isPage(null))
    }

    @Test
    fun `a name with no extension falls back to the header sniff`() {
        // No extension means there is nothing to trust in the name, so the bytes decide.
        assertTrue(PageImageName.isPage("page") { true })
        assertFalse(PageImageName.isPage("page") { false })
        assertFalse(PageImageName.isPage("page"))
    }

    @Test
    fun `an extension is never sniffed`() {
        // The whole point: a thumbnail that decodes as an image must still be rejected when the
        // name says it is not one.
        assertFalse(PageImageName.isPage(".thumb") { true })
    }
}
