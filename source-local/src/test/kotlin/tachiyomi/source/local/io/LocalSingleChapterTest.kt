package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.system.PageImageName

class LocalSingleChapterTest {

    @Test
    fun `a folder of loose pages with no chapter entry is one single chapter`() {
        assertEquals(
            listOf("001.webp", "002.webp", "003.webp"),
            loosePages(
                listOf(
                    file("001.webp"),
                    file("002.webp"),
                    file("003.webp"),
                    file("ComicInfo.xml"),
                    file(".thumb", isDirectory = true),
                ),
            ).map { it.name },
        )
    }

    @Test
    fun `the generated cover is not a page of the single chapter`() {
        assertEquals(
            listOf("001.webp", "002.webp"),
            loosePages(listOf(file("cover.webp"), file("001.webp"), file("cover.jpg"), file("002.webp")))
                .map { it.name },
        )
    }

    @Test
    fun `a folder holding only the generated cover has no pages`() {
        assertTrue(loosePages(listOf(file("cover.jpg"))).isEmpty())
    }

    @Test
    fun `a folder holding only metadata has no pages`() {
        assertTrue(loosePages(listOf(file("ComicInfo.xml"), file(".noxml"), file(".nomedia"))).isEmpty())
    }

    @Test
    fun `an empty folder has no pages`() {
        assertTrue(loosePages(emptyList()).isEmpty())
    }

    @Test
    fun `dot prefixed page files stay pages`() {
        assertTrue(isLoosePageName(".001.webp"))
    }

    @Test
    fun `the virtual chapter name is neither a chapter entry nor a page`() {
        assertTrue(isSingleChapterEntryName(LOCAL_SINGLE_CHAPTER_NAME))
        assertFalse(isSingleChapterEntryName("01篇"))
        assertFalse(isSingleChapterEntryName(""))
        // The name is addressed as "<title>/<name>", so it must never be mistaken for a file the
        // user could have put on disk: no chapter extension, no image extension.
        assertFalse(PageImageName.hasImageExtension(LOCAL_SINGLE_CHAPTER_NAME))
        assertFalse(Archive.isChapterEntryName(LOCAL_SINGLE_CHAPTER_NAME, isDirectory = false))
        assertFalse(Archive.isChapterEntryName(LOCAL_SINGLE_CHAPTER_NAME, isDirectory = true))
    }

    @Test
    fun `a chapter url names its virtual chapter only for a flat folder`() {
        assertTrue("Work A/$LOCAL_SINGLE_CHAPTER_NAME".isSingleChapterChapterUrl())
        assertFalse("Work A/01篇".isSingleChapterChapterUrl())
        assertFalse("Work A".isSingleChapterChapterUrl())
    }

    private fun file(name: String, isDirectory: Boolean = false): UniFile = mockk(relaxed = true) {
        every { this@mockk.name } returns name
        every { this@mockk.isDirectory } returns isDirectory
    }
}
