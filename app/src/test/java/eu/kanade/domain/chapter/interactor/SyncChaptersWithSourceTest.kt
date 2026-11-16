package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.download.ChapterRenameReceipt
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import java.io.IOException

class SyncChaptersWithSourceTest {

    @Test
    fun `manual local scan restores chapters after database list was lost`() = runTest {
        val chapterRepository = mockk<ChapterRepository>()
        coEvery { chapterRepository.getChapterByMangaId(1L, false) } returns emptyList()
        coEvery { chapterRepository.applySourceSync(any(), any(), any()) } answers { secondArg() }
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
        coVerify(exactly = 1) { chapterRepository.applySourceSync(emptyList(), match { it.size == 1 }, emptyList()) }
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
        coVerify(exactly = 0) { chapterRepository.applySourceSync(any(), any(), any()) }
    }

    @Test
    fun `confirmed empty local scan removes deleted chapters`() = runTest {
        val chapterRepository = mockk<ChapterRepository>()
        val existingChapter = Chapter.create().copy(id = 10L, mangaId = 1L, url = "Author/Story.cbz")
        coEvery { chapterRepository.getChapterByMangaId(1L, false) } returns listOf(existingChapter)
        coEvery { chapterRepository.applySourceSync(listOf(10L), emptyList(), emptyList()) } returns emptyList()
        val source = mockk<Source>()
        every { source.id } returns LocalSource.ID

        createSubject(chapterRepository).await(
            rawSourceChapters = emptyList(),
            manga = Manga.create().copy(id = 1L, source = LocalSource.ID),
            source = source,
            manualFetch = false,
            allowEmptyLocalSource = true,
        )

        coVerify(exactly = 1) { chapterRepository.applySourceSync(listOf(10L), emptyList(), emptyList()) }
    }

    @Test
    fun `duplicate source URLs are inserted once in one batch`() = runTest {
        val repository = mockk<ChapterRepository>()
        coEvery { repository.getChapterByMangaId(1L, false) } returns emptyList()
        coEvery { repository.applySourceSync(any(), any(), any()) } answers { secondArg() }
        val source = mockk<Source>()
        every { source.id } returns 2L
        val chapter = sourceChapter("same", "Chapter 1")

        val inserted = createSubject(repository).await(
            listOf(chapter, chapter),
            Manga.create().copy(id = 1L, source = 2L),
            source,
        )

        assertEquals(1, inserted.size)
        coVerify(exactly = 1) { repository.applySourceSync(emptyList(), match { it.size == 1 }, emptyList()) }
        coVerify(exactly = 0) { repository.addAll(any()) }
    }

    @Test
    fun `mixed sync batches confirmed removal insertion and source-only updates`() = runTest {
        val repository = mockk<ChapterRepository>()
        val existing = Chapter.create().copy(
            id = 10L,
            mangaId = 1L,
            url = "kept",
            name = "Old",
            read = true,
            bookmark = true,
            lastPageRead = 6L,
            totalPages = 10L,
            translatedName = "Translated",
            customOrder = 7L,
            markedReadAt = 42L,
        )
        val deleted = existing.copy(id = 11L, url = "removed")
        coEvery { repository.getChapterByMangaId(1L, false) } returns listOf(existing, deleted)
        val updates = mutableListOf<ChapterUpdate>()
        coEvery { repository.applySourceSync(listOf(11L), any(), any()) } answers {
            updates.addAll(thirdArg<List<ChapterUpdate>>())
            secondArg<List<Chapter>>()
        }
        val source = mockk<Source>()
        every { source.id } returns 2L
        val manager = mockk<DownloadManager>(relaxed = true)
        val provider = mockk<DownloadProvider>()
        every { provider.isChapterDirNameChanged(any(), any()) } returns false

        val added = createSubject(repository, manager, provider).await(
            listOf(sourceChapter("kept", "Chapter 2"), sourceChapter("added", "Chapter 3")),
            Manga.create().copy(id = 1L, source = 2L),
            source,
        )

        assertEquals(listOf("added"), added.map { it.url })
        val update = updates.single()
        assertEquals(10L, update.id)
        assertEquals("Chapter 2", update.name)
        assertNull(update.read)
        assertNull(update.bookmark)
        assertNull(update.lastPageRead)
        assertNull(update.customOrder)
        assertNull(update.translatedName)
        assertNull(update.markedReadAt)
        assertNull(update.version)
        assertNull(update.mangaId)
        assertNull(update.url)
        coVerify(exactly = 1) { repository.applySourceSync(listOf(11L), any(), any()) }
        coVerify(exactly = 0) { repository.addAll(any()) }
        coVerify(exactly = 0) { repository.removeChaptersWithIds(any()) }
        coVerify(exactly = 0) { repository.updateAll(any()) }
    }

    @Test
    fun `database failure restores completed renames in reverse and skips manga updates`() = runTest {
        val fixture = renameFixture()
        val failure = IOException("database write failed")
        coEvery { fixture.repository.applySourceSync(any(), any(), any()) } throws failure

        try {
            fixture.subject.await(fixture.sourceChapters, fixture.manga, fixture.source)
            fail<Unit>("Expected database failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }

        assertEquals(listOf("second", "first"), fixture.restored)
        coVerify(exactly = 0) { fixture.updateManga.awaitUpdateLastUpdate(any()) }
    }

    @Test
    fun `successful batch keeps renamed downloads and updates manga timestamps`() = runTest {
        val fixture = renameFixture()
        coEvery { fixture.repository.applySourceSync(any(), any(), any()) } returns emptyList()

        fixture.subject.await(fixture.sourceChapters, fixture.manga, fixture.source)

        assertTrue(fixture.restored.isEmpty())
        coVerify(exactly = 1) { fixture.repository.applySourceSync(emptyList(), emptyList(), any()) }
        coVerify(exactly = 1) { fixture.updateManga.awaitUpdateLastUpdate(1L) }
    }

    @Test
    fun `empty remote scan cannot use local deletion confirmation`() = runTest {
        val repository = mockk<ChapterRepository>()
        coEvery { repository.getChapterByMangaId(1L, false) } returns listOf(
            Chapter.create().copy(id = 10L, mangaId = 1L),
        )
        val source = mockk<Source>()
        every { source.id } returns 2L

        try {
            createSubject(repository).await(
                emptyList(),
                Manga.create().copy(id = 1L, source = 2L),
                source,
                allowEmptyLocalSource = true,
            )
            fail<Unit>("Expected untrusted empty source protection")
        } catch (_: NoChaptersException) {
            coVerify(exactly = 0) { repository.applySourceSync(any(), any(), any()) }
        }
    }

    @Test
    fun `second rename failure restores the first without writing database`() = runTest {
        val fixture = renameFixture()
        val failure = IOException("second rename failed")
        coEvery {
            fixture.manager.renameChapter(any(), any(), match { it.id == 11L }, any())
        } throws failure

        try {
            fixture.subject.await(fixture.sourceChapters, fixture.manga, fixture.source)
            fail<Unit>("Expected rename failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }

        assertEquals(listOf("first"), fixture.restored)
        coVerify(exactly = 0) { fixture.repository.applySourceSync(any(), any(), any()) }
    }

    @Test
    fun `cancellation propagates after completed renames are compensated`() = runTest {
        val fixture = renameFixture()
        val cancellation = CancellationException("cancelled sync")
        coEvery { fixture.repository.applySourceSync(any(), any(), any()) } throws cancellation

        try {
            fixture.subject.await(fixture.sourceChapters, fixture.manga, fixture.source)
            fail<Unit>("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertEquals(listOf("second", "first"), fixture.restored)
    }

    private suspend fun renameFixture(): RenameFixture {
        val repository = mockk<ChapterRepository>()
        val manager = mockk<DownloadManager>(relaxed = true)
        val provider = mockk<DownloadProvider>()
        val updateManga = mockk<UpdateManga>(relaxed = true)
        val manga = Manga.create().copy(id = 1L, source = 2L)
        val source = mockk<Source>()
        every { source.id } returns 2L
        val chapters = listOf("first", "second").mapIndexed { index, url ->
            Chapter.create().copy(id = 10L + index, mangaId = 1L, url = url, name = url)
        }
        coEvery { repository.getChapterByMangaId(1L, false) } returns chapters
        every { provider.isChapterDirNameChanged(any(), any()) } returns true
        every { manager.isChapterDownloaded(any(), any(), any(), any(), any()) } returns true
        val restored = mutableListOf<String>()
        chapters.forEach { chapter ->
            val receipt = mockk<ChapterRenameReceipt>()
            coEvery { receipt.rollback() } coAnswers {
                restored.add(chapter.url)
            }
            coEvery {
                manager.renameChapter(any(), any(), match { it.id == chapter.id }, any())
            } returns receipt
        }
        return RenameFixture(
            createSubject(repository, manager, provider, updateManga),
            repository,
            manager,
            updateManga,
            manga,
            source,
            chapters.map { sourceChapter(it.url, "New " + it.name) },
            restored,
        )
    }

    private fun sourceChapter(url: String, name: String) = SChapter.create().apply {
        this.url = url
        this.name = name
    }

    private data class RenameFixture(
        val subject: SyncChaptersWithSource,
        val repository: ChapterRepository,
        val manager: DownloadManager,
        val updateManga: UpdateManga,
        val manga: Manga,
        val source: Source,
        val sourceChapters: List<SChapter>,
        val restored: MutableList<String>,
    )

    private fun createSubject(
        chapterRepository: ChapterRepository,
        downloadManager: DownloadManager = mockk(relaxed = true),
        downloadProvider: DownloadProvider = mockk(relaxed = true),
        updateManga: UpdateManga = mockk(relaxed = true),
    ) =
        SyncChaptersWithSource(
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            getChaptersByMangaId = GetChaptersByMangaId(chapterRepository),
            getExcludedScanlators = mockk<GetExcludedScanlators>(relaxed = true),
        )
}
