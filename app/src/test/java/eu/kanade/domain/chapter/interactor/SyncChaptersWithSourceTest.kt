package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource

class SyncChaptersWithSourceTest {

    @Test
    fun `manual local scan restores chapters after database list was lost`() = runTest {
        val chapterRepository = mockk<ChapterRepository>()
        coEvery { chapterRepository.getChapterByMangaId(1L, false) } returns emptyList()
        coEvery { chapterRepository.addAll(any()) } answers { firstArg() }
        val source = mockk<Source>()
        every { source.id } returns LocalSource.ID
        val subject = createSubject(chapterRepository)
        val sourceChapter = SChapter.create().apply {
            url = "Author/Story.cbz"
            name = "Story.cbz"
        }

        val restored = subject.await(
            rawSourceChapters = listOf(sourceChapter),
            manga = Manga.create().copy(id = 1L, source = LocalSource.ID),
            source = source,
            manualFetch = true,
        )

        assertEquals(listOf("Author/Story.cbz"), restored.map { it.url })
        coVerify(exactly = 1) { chapterRepository.addAll(match { it.size == 1 }) }
    }

    @Test
    fun `empty local scan cannot delete existing chapters`() = runTest {
        val chapterRepository = mockk<ChapterRepository>()
        val existingChapter = Chapter.create().copy(id = 10L, mangaId = 1L, url = "Author/Story.cbz")
        coEvery { chapterRepository.getChapterByMangaId(1L, false) } returns listOf(existingChapter)
        val source = mockk<Source>()
        every { source.id } returns LocalSource.ID
        val subject = createSubject(chapterRepository)

        var protected = false
        try {
            subject.await(
                rawSourceChapters = emptyList(),
                manga = Manga.create().copy(id = 1L, source = LocalSource.ID),
                source = source,
                manualFetch = true,
            )
        } catch (_: NoChaptersException) {
            protected = true
        }

        assertTrue(protected)
    }

    @Test
    fun `confirmed empty local scan removes deleted chapters`() = runTest {
        val chapterRepository = mockk<ChapterRepository>()
        val existingChapter = Chapter.create().copy(id = 10L, mangaId = 1L, url = "Author/Story.cbz")
        coEvery { chapterRepository.getChapterByMangaId(1L, false) } returns listOf(existingChapter)
        coEvery { chapterRepository.removeChaptersWithIds(listOf(10L)) } returns Unit
        val source = mockk<Source>()
        every { source.id } returns LocalSource.ID

        createSubject(chapterRepository).await(
            rawSourceChapters = emptyList(),
            manga = Manga.create().copy(id = 1L, source = LocalSource.ID),
            source = source,
            manualFetch = false,
            allowEmptyLocalSource = true,
        )

        coVerify(exactly = 1) { chapterRepository.removeChaptersWithIds(listOf(10L)) }
    }

    private fun createSubject(chapterRepository: ChapterRepository) =
        SyncChaptersWithSource(
            downloadManager = mockk<DownloadManager>(relaxed = true),
            downloadProvider = mockk<DownloadProvider>(relaxed = true),
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = mockk<ShouldUpdateDbChapter>(relaxed = true),
            updateManga = mockk<UpdateManga>(relaxed = true),
            updateChapter = mockk<UpdateChapter>(relaxed = true),
            getChaptersByMangaId = GetChaptersByMangaId(chapterRepository),
            getExcludedScanlators = mockk<GetExcludedScanlators>(relaxed = true),
        )
}
