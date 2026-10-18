package mihon.domain.source.interactor

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource

class UpdateMangaFromRemoteTest {

    @Test
    fun `local work that gained a chapter is re-dated as imported now`() = runTest {
        val manga = Manga.create().copy(id = 1L, source = LocalSource.ID, url = "Author")
        val repository = mockk<MangaRepository>()
        coEvery { repository.getMangaByIdOrNull(1L) } returns manga
        coEvery { repository.update(any()) } returns true
        val sync = mockk<SyncChaptersWithSource>()
        coEvery { sync.await(any(), any(), any(), any(), any(), any()) } returns listOf(newChapter())

        val result = createSubject(repository, sync).invoke(
            source = source(local = true),
            manga = manga,
            fetchChapters = true,
        )

        assertTrue(result.isSuccess)
        coVerify { repository.update(match { it.id == 1L && it.dateAdded != null }) }
    }

    @Test
    fun `local work whose chapter files did not add anything keeps its import date`() = runTest {
        val manga = Manga.create().copy(id = 1L, source = LocalSource.ID, url = "Author")
        val repository = mockk<MangaRepository>()
        coEvery { repository.getMangaByIdOrNull(1L) } returns manga
        coEvery { repository.update(any()) } returns true
        val sync = mockk<SyncChaptersWithSource>()
        // A chapter file replaced under its own name, or one deleted, adds no chapter row.
        coEvery { sync.await(any(), any(), any(), any(), any(), any()) } returns emptyList()

        val result = createSubject(repository, sync).invoke(
            source = source(local = true),
            manga = manga,
            fetchChapters = true,
        )

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { repository.update(match { it.dateAdded != null }) }
    }

    @Test
    fun `remote work gaining chapters keeps the library date semantics`() = runTest {
        val manga = Manga.create().copy(id = 2L, source = 42L, url = "https://example.test/manga")
        val repository = mockk<MangaRepository>()
        coEvery { repository.getMangaByIdOrNull(2L) } returns manga
        coEvery { repository.update(any()) } returns true
        val sync = mockk<SyncChaptersWithSource>()
        coEvery { sync.await(any(), any(), any(), any(), any(), any()) } returns listOf(newChapter())

        val result = createSubject(repository, sync).invoke(
            source = source(local = false),
            manga = manga,
            fetchChapters = true,
        )

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { repository.update(match { it.dateAdded != null }) }
    }

    private fun newChapter(): Chapter = Chapter.create().copy(
        id = 10L,
        mangaId = 1L,
        url = "Author/Story.cbz",
        name = "Story.cbz",
    )

    private fun source(local: Boolean): Source = mockk<Source>().also {
        every { it.id } returns if (local) LocalSource.ID else 42L
        coEvery { it.getMangaUpdate(any(), any(), any(), any()) } returns SMangaUpdate(
            manga = SManga.create().apply {
                url = "Author"
                title = "Author"
            },
            chapters = listOf(
                SChapter.create().apply {
                    url = "Author/Story.cbz"
                    name = "Story.cbz"
                },
            ),
        )
    }

    private fun createSubject(
        repository: MangaRepository,
        sync: SyncChaptersWithSource,
    ) = UpdateMangaFromRemote(
        sourceManager = mockk<SourceManager>(relaxed = true),
        chapterRepository = mockk<ChapterRepository>().also {
            coEvery { it.getChapterByMangaId(any(), any()) } returns emptyList()
        },
        mangaRepository = repository,
        syncChaptersWithSource = sync,
        coverCache = mockk<CoverCache>(relaxed = true),
        libraryPreferences = mockk<LibraryPreferences>(relaxed = true),
        downloadManager = mockk<DownloadManager>(relaxed = true),
    )
}
