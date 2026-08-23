package eu.kanade.tachiyomi.data.local

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
