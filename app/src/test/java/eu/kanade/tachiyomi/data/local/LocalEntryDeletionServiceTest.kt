package eu.kanade.tachiyomi.data.local

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.LocalSourceDirectoryEntryState
import tachiyomi.source.local.image.LocalChapterCoverManager
import tachiyomi.source.local.io.LOCAL_SINGLE_CHAPTER_NAME
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.nio.file.Files
import java.nio.file.Path

class LocalEntryDeletionServiceTest {

    @TempDir lateinit var directory: Path

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
    fun `erasing a folder-level single chapter keeps the work and its non-page files`() = runBlocking {
        val chapter = Chapter.create().copy(
            id = 3L,
            mangaId = 7L,
            url = "Work A/$LOCAL_SINGLE_CHAPTER_NAME",
            name = "单篇",
        )
        val page = mockk<UniFile>(relaxed = true) {
            every { name } returns "001.png"
            every { delete() } returns true
        }
        val cover = mockk<UniFile>(relaxed = true) { every { name } returns "cover.jpg" }
        val notes = mockk<UniFile>(relaxed = true) { every { name } returns "notes.txt" }
        val workDirectory = mockk<UniFile>(relaxed = true) {
            every { name } returns "Work A"
            every { isDirectory } returns true
            every { listFiles() } returns emptyArray()
            every { delete() } returns true
        }
        every { fileSystem.getBaseDirectory() } returns mockk<UniFile>(relaxed = true).also {
            every { it.findFile("Work A") } returns workDirectory
        }
        every { fileSystem.getDirectorySnapshot(workDirectory, fresh = true) } returns
            LocalSourceFileSystem.DirectorySnapshot(workDirectory, listOf(page, cover, notes), isAccessible = true)
        coEvery { chapterRepository.getChapterByMangaId(7L) } returns listOf(chapter)
        coEvery { mangaRepository.getMangaById(7L) } returns
            Manga.create().copy(id = 7L, url = "Work A", title = "Work A")

        val result = service.deleteChapters(
            listOf(
                LocalEntryDeletionService.ChapterTarget(
                    id = 3L,
                    mangaId = 7L,
                    mangaTitle = "Work A",
                    name = "单篇",
                ),
            ),
        )

        assertEquals(1, result.deleted)
        assertTrue(result.failed.isEmpty())
        assertTrue(result.deletedMangaIds.isEmpty())
        assertEquals(emptySet<Long>(), service.lastReport.value?.removedFolderMangaIds)
        verify(exactly = 1) { page.delete() }
        verify(exactly = 0) { cover.delete() }
        verify(exactly = 0) { notes.delete() }
        verify(exactly = 0) { workDirectory.delete() }
        coVerify(exactly = 0) { mangaRepository.deleteMangaById(7L) }
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

    @Test
    fun `optional cleanup reports the removed directory without deleting the manga row`() = runBlocking {
        val work = Files.createDirectory(directory.resolve("Work"))
        Files.writeString(work.resolve("cover.jpg"), "cover")
        val chapterFile = mockk<UniFile>(relaxed = true) { every { delete() } returns true }
        val workDirectory = mockk<UniFile> {
            every { filePath } returns work.toString()
            every { findFile("one.cbz") } returns chapterFile
        }
        val coverEntry = mockk<UniFile> {
            every { isFile } returns true
            every { name } returns "cover.jpg"
        }
        every { fileSystem.getDirectorySnapshot(workDirectory, fresh = true) } returns
            LocalSourceFileSystem.DirectorySnapshot(workDirectory, listOf(coverEntry), isAccessible = true)
        every { fileSystem.getBaseDirectory() } returns mockk<UniFile> {
            every { findFile("Work") } returns workDirectory
        }
        val chapter = Chapter.create().copy(id = 3L, mangaId = 7L, url = "Work/one.cbz")
        coEvery { chapterRepository.getChapterByMangaId(7L) } returnsMany listOf(listOf(chapter), emptyList())

        val result = service.deleteChapters(
            listOf(LocalEntryDeletionService.ChapterTarget(3L, 7L, "Work", "one")),
            listOf(LocalEntryDeletionService.EmptyFolderCleanup(7L, "Work", setOf("cover.jpg"))),
        )

        assertEquals(1, result.deleted)
        assertEquals(setOf(7L), service.lastReport.value?.removedFolderMangaIds)
        assertEquals(emptySet<Long>(), service.lastReport.value?.deletedMangaIds)
        assertTrue(Files.notExists(work))
        coVerify(exactly = 0) { mangaRepository.deleteMangaById(7L) }
    }

    @Test
    fun `optional cleanup never deletes an unapproved attachment`() = runBlocking {
        val work = Files.createDirectory(directory.resolve("Work"))
        val cover = Files.writeString(work.resolve("cover.jpg"), "cover")
        val notes = Files.writeString(work.resolve("notes.txt"), "notes")
        val chapter = Chapter.create().copy(id = 3L, mangaId = 7L, url = "Work/one.cbz")
        val chapterFile = mockk<UniFile>(relaxed = true) { every { delete() } returns true }
        val workDirectory = mockk<UniFile>(relaxed = true) {
            every { filePath } returns work.toString()
            every { findFile("one.cbz") } returns chapterFile
        }
        val coverEntry = mockk<UniFile> {
            every { isFile } returns true
            every { name } returns "cover.jpg"
        }
        val notesEntry = mockk<UniFile> {
            every { isFile } returns true
            every { name } returns "notes.txt"
        }
        every { fileSystem.getDirectorySnapshot(workDirectory, fresh = true) } returns
            LocalSourceFileSystem.DirectorySnapshot(workDirectory, listOf(coverEntry, notesEntry), isAccessible = true)
        every { fileSystem.getBaseDirectory() } returns mockk<UniFile> {
            every { findFile("Work") } returns workDirectory
        }
        coEvery { chapterRepository.getChapterByMangaId(7L) } returnsMany listOf(listOf(chapter), emptyList())

        val result = service.deleteChapters(
            listOf(LocalEntryDeletionService.ChapterTarget(3L, 7L, "Work", "one")),
            listOf(LocalEntryDeletionService.EmptyFolderCleanup(7L, "Work", setOf("cover.jpg"))),
        )

        assertEquals(1, result.deleted)
        assertEquals(listOf("Work"), service.lastReport.value?.keptFolders)
        assertEquals(emptySet<Long>(), service.lastReport.value?.removedFolderMangaIds)
        assertTrue(Files.exists(cover))
        assertTrue(Files.exists(notes))
        coVerify(exactly = 0) { mangaRepository.deleteMangaById(7L) }
    }
}
