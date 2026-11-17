package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.creators.MarksBackupCreator
import eu.kanade.tachiyomi.data.manga.GoodDoujinStore
import eu.kanade.tachiyomi.data.manga.MangaMark
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.IOException

class MarksBackupCreatorTest {
    private val mark = MangaMark(9001, "work", 9002, "chapter", 123)
    private val manga = Manga.create().copy(id = 9001, source = 0, url = "work")
    private val chapter = Chapter.create().copy(id = 9002, mangaId = 9001, url = "work/chapter.cbz")
    private val ordinary = mockk<MangaMarkStore> { every { marks } returns MutableStateFlow(listOf(mark)) }
    private val good = mockk<GoodDoujinStore> { coEvery { snapshot() } returns listOf(mark) }
    private val mangas = mockk<MangaRepository> { coEvery { getMangaByIdOrNull(9001) } returns manga }
    private val chapters = mockk<ChapterRepository> { coEvery { getChapterById(9002) } returns chapter }
    private val creator = MarksBackupCreator(ordinary, good, mangas, chapters)

    @Test
    fun `both mark lists export stable addresses and timestamps`() = runTest {
        val saved = creator()
        assertEquals(listOf(0, 1), saved.map { it.listType })
        assertTrue(
            saved.all {
                it.sourceId == 0L && it.mangaUrl == manga.url && it.chapterUrl == chapter.url && it.markedAt == 123L
            },
        )
    }

    @Test
    fun `deleted manga or chapter is skipped without blocking backup`() = runTest {
        coEvery { mangas.getMangaByIdOrNull(9001) } returns null
        assertTrue(creator().isEmpty())
        coEvery { mangas.getMangaByIdOrNull(9001) } returns manga
        coEvery { chapters.getChapterById(9002) } returns null
        assertTrue(creator().isEmpty())
    }

    @Test
    fun `chapter moved to another manga is not exported under its old parent`() = runTest {
        coEvery { chapters.getChapterById(9002) } returns chapter.copy(mangaId = 8001)
        assertTrue(creator().isEmpty())
    }

    @Test
    fun `database read failure aborts export instead of silently dropping marks`() = runTest {
        coEvery { mangas.getMangaByIdOrNull(9001) } throws IOException("manga query failed")
        assertThrows<IOException> { creator() }
        coEvery { mangas.getMangaByIdOrNull(9001) } returns manga
        coEvery { chapters.getChapterById(9002) } throws IOException("chapter query failed")
        assertThrows<IOException> { creator() }
    }

    @Test
    fun `cancellation and snapshot failures abort export`() = runTest {
        coEvery { good.snapshot() } throws IOException("snapshot failed")
        assertThrows<IOException> { creator() }
        coEvery { good.snapshot() } throws CancellationException("cancelled")
        assertThrows<CancellationException> { creator() }
    }
}
