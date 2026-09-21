package eu.kanade.tachiyomi.data.local

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.source.local.image.LocalChapterCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem

class LocalChapterTransferServiceTest {

    private val service = LocalChapterTransferService(
        context = mockk<Context>(relaxed = true),
        fileSystem = mockk<LocalSourceFileSystem>(relaxed = true),
        mangaRepository = mockk<MangaRepository>(relaxed = true),
        chapterRepository = mockk<ChapterRepository>(relaxed = true),
        coverManager = mockk<LocalChapterCoverManager>(relaxed = true),
        mangaMarkStore = mockk<MangaMarkStore>(relaxed = true),
    )

    @Test
    fun `author and book levels are recognized as grouped import`() {
        val book = directory("Book", image("1.jpg"))
        val author = directory("Author", book)
        val root = directory("local", author)

        val groups = service.expandGrouped(root)

        assertEquals(1, groups?.size)
        assertEquals("Author", groups?.single()?.name)
        assertEquals(listOf("Book"), groups?.single()?.candidateNames)
    }

    @Test
    fun `ordinary parent containing books keeps existing single target behavior`() {
        val firstBook = directory("Book A", image("1.jpg"))
        val secondBook = directory("Book B", image("1.jpg"))
        val root = directory("batch", firstBook, secondBook)

        assertNull(service.expandGrouped(root))
    }

    @Test
    fun `clear grouped structure ignores loose root files`() {
        val author = directory("Author", directory("Book", image("1.jpg")))
        val root = directory("local", image("cover.jpg"), author)

        assertEquals(listOf("Author"), service.expandGrouped(root)?.map { it.name })
    }

    @Test
    fun `a download client thumbnail folder is not imported as a chapter`() {
        // EHViewer leaves a `.thumb` folder of images next to the pages. Counting it as a child
        // folder would also make a book of loose images look like a container, so the book itself
        // would stop importing as a single chapter.
        val book = directory("Book", image("001.jpg"), image("002.jpg"), directory(".thumb", image("001.jpg")))

        assertEquals(listOf("Book"), service.expand(book).map { it.name })
    }

    @Test
    fun `an ordinary child folder is still a chapter`() {
        val book = directory("Book", directory("Chapter 1", image("001.jpg")))

        assertEquals(listOf("Chapter 1"), service.expand(book).map { it.name })
    }

    @Test
    fun `a selected pdf is a chapter`() {
        assertEquals(listOf("Chapter 1"), service.expand(image("Chapter 1.pdf")).map { it.name })
    }

    @Test
    fun `each pdf in a folder is its own chapter`() {
        // A PDF is a whole document, not a loose page, so it counts as a chapter file the same
        // way a cbz does - a folder of them is a folder of chapters, not one chapter.
        val book = directory("Book", image("Chapter 1.pdf"), image("Chapter 2.pdf"))

        assertEquals(listOf("Chapter 1", "Chapter 2"), service.expand(book).map { it.name })
    }

    @Test
    fun `pdfs sit alongside archives in a mixed folder`() {
        val book = directory("Book", image("Chapter 1.cbz"), image("Chapter 2.pdf"))

        assertEquals(listOf("Chapter 1", "Chapter 2"), service.expand(book).map { it.name })
    }

    @Test
    fun `picking the library folder itself clashes`() {
        assertTrue(
            service.overlapsPath(
                a = listOf("primary:Mihon", "local"),
                b = listOf("primary:Mihon", "local"),
            ),
        )
    }

    @Test
    fun `picking a folder inside the library clashes`() {
        assertTrue(
            service.overlapsPath(
                a = listOf("primary:Mihon", "local"),
                b = listOf("primary:Mihon", "local", "Some Book"),
            ),
        )
        assertTrue(
            service.overlapsPath(
                a = listOf("primary:Mihon", "local", "Some Book", "Ch 1"),
                b = listOf("primary:Mihon", "local"),
            ),
        )
    }

    @Test
    fun `picking an ancestor of the library clashes`() {
        assertTrue(
            service.overlapsPath(
                a = listOf("primary:Mihon", "local"),
                b = listOf("primary:Mihon"),
            ),
        )
    }

    @Test
    fun `a sibling folder does not clash`() {
        assertFalse(
            service.overlapsPath(
                a = listOf("primary:Mihon", "local"),
                b = listOf("primary:Mihon", "downloads"),
            ),
        )
    }

    @Test
    fun `a folder whose name merely shares a prefix does not clash`() {
        // "local2" must not be treated as living inside "local" just because the text starts
        // the same; only whole segments count.
        assertFalse(
            service.overlapsPath(
                a = listOf("primary:Mihon", "local"),
                b = listOf("primary:Mihon", "local2", "Book"),
            ),
        )
    }

    @Test
    fun `an unusable document id never clashes`() {
        assertFalse(service.overlapsPath(a = null, b = listOf("primary:Mihon", "local")))
        assertFalse(service.overlapsPath(a = emptyList(), b = listOf("primary:Mihon", "local")))
        assertFalse(service.overlapsPath(a = listOf("primary:Mihon", "local"), b = null))
    }

    private fun directory(name: String, vararg children: UniFile): UniFile {
        val file = mockk<UniFile>(relaxed = true)
        every { file.isDirectory } returns true
        every { file.name } returns name
        every { file.uri } returns mockk<Uri>(relaxed = true)
        every { file.listFiles() } returns children
        return file
    }

    private fun image(name: String): UniFile {
        val file = mockk<UniFile>(relaxed = true)
        every { file.isDirectory } returns false
        every { file.name } returns name
        every { file.uri } returns mockk<Uri>(relaxed = true)
        return file
    }
}
