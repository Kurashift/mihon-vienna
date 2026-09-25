package eu.kanade.tachiyomi.data.local

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.LocalSourceDirectoryEntryState
import tachiyomi.source.local.image.LocalChapterCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem

class LocalEntryDeletionServiceTest {

    private val fileSystem = mockk<LocalSourceFileSystem>(relaxed = true)
    private val mangaRepository = mockk<MangaRepository>(relaxed = true)
    private val chapterRepository = mockk<ChapterRepository>(relaxed = true)
    private val coverManager = mockk<LocalChapterCoverManager>(relaxed = true)
    private val coverCache = mockk<CoverCache>(relaxed = true)
    private val mangaMarkStore = mockk<MangaMarkStore>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)

    private val service = LocalEntryDeletionService(
        fileSystem = fileSystem,
        mangaRepository = mangaRepository,
        chapterRepository = chapterRepository,
        coverManager = coverManager,
        coverCache = coverCache,
        mangaMarkStore = mangaMarkStore,
        sourceManager = sourceManager,
    )

    private val entry = LocalEntryDeletionService.MangaEntry(
        id = 7L,
        url = "Ghost author",
        title = "Ghost author",
        manga = Manga.create().copy(id = 7L, url = "Ghost author", title = "Ghost author"),
    )

    @Test
    fun `a directory confirmed gone still drops the records it left behind`() = runBlocking {
        // The files are already in the state the deletion asks for, so only the records are left.
        // Treating this as a failure is what left an undeletable card in the library: every retry
        // re-reported a removal that had already happened.
        every { fileSystem.getBaseDirectory() } returns mockk<UniFile>(relaxed = true).also {
            every { it.findFile(entry.url) } returns null
        }
        every { fileSystem.createMangaDirectoryEntryStateLookup() } returns {
            LocalSourceDirectoryEntryState.MISSING
        }

        val result = service.deleteManga(entry)

        assertEquals(1, result.deleted)
        assertTrue(result.failed.isEmpty())
        assertTrue(7L in result.deletedMangaIds)
        coVerify(exactly = 1) { mangaRepository.deleteMangaById(7L) }
    }

    @Test
    fun `an unreadable directory never counts as a deletion`() = runBlocking {
        // A provider that failed to answer is not a removal. The rows dropped here cannot be
        // rebuilt - history, bookmarks, chapter titles and the custom cover all go with them -
        // so an unknown answer has to come back as a failure the reader can retry.
        every { fileSystem.getBaseDirectory() } returns mockk<UniFile>(relaxed = true).also {
            every { it.findFile(entry.url) } returns null
        }
        every { fileSystem.createMangaDirectoryEntryStateLookup() } returns {
            LocalSourceDirectoryEntryState.UNKNOWN
        }

        val result = service.deleteManga(entry)

        assertEquals(0, result.deleted)
        assertEquals(listOf(entry.title), result.failed)
        assertTrue(result.deletedMangaIds.isEmpty())
        coVerify(exactly = 0) { mangaRepository.deleteMangaById(any()) }
        verify(exactly = 0) { coverCache.deleteFromCache(any(), any()) }
    }

    @Test
    fun `a directory that exists is deleted as before`() = runBlocking {
        val dir = mockk<UniFile>(relaxed = true)
        every { dir.isDirectory } returns false
        every { dir.delete() } returns true
        every { fileSystem.getBaseDirectory() } returns mockk<UniFile>(relaxed = true).also {
            every { it.findFile(entry.url) } returns dir
        }

        val result = service.deleteManga(entry)

        assertEquals(1, result.deleted)
        assertTrue(result.failed.isEmpty())
        coVerify(exactly = 1) { mangaRepository.deleteMangaById(7L) }
        // The lookup is only needed when the directory is absent; a real deletion must not wait
        // on it.
        verify(exactly = 0) { fileSystem.createMangaDirectoryEntryStateLookup() }
    }
}
