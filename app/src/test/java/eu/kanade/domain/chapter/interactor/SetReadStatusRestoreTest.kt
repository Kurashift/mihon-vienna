package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.download.interactor.DeleteDownload
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterReadState
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.repository.MangaRepository

class SetReadStatusRestoreTest {

    private val chapterRepository = mockk<ChapterRepository>(relaxed = true)
    private val mangaRepository = mockk<MangaRepository>(relaxed = true)
    private val deleteDownload = mockk<DeleteDownload>(relaxed = true)
    private val removeAfterMarkedAsReadPreference = mockk<Preference<Boolean>> {
        every { get() } returns false
    }
    private val downloadPreferences = mockk<DownloadPreferences> {
        every { removeAfterMarkedAsRead } returns removeAfterMarkedAsReadPreference
    }

    private val subject = SetReadStatus(
        downloadPreferences = downloadPreferences,
        deleteDownload = deleteDownload,
        mangaRepository = mangaRepository,
        chapterRepository = chapterRepository,
    )

    @Test
    fun `restoring a snapshot writes the captured state as it was`() = runBlocking {
        val states = listOf(
            ChapterReadState(chapterId = 1, read = true, lastPageRead = 4, markedReadAt = 42),
            ChapterReadState(chapterId = 2, read = false, lastPageRead = 0, markedReadAt = 0),
        )

        subject.restoreReadStates(states)

        // The exact write, not the normalising update path: that one would push a half-read
        // chapter's resume point to its last page.
        coVerify(exactly = 1) { chapterRepository.updateReadStates(states) }
        coVerify(exactly = 0) { chapterRepository.updateAll(any()) }
    }

    @Test
    fun `restoring nothing writes nothing`() = runBlocking {
        subject.restoreReadStates(emptyList())

        coVerify(exactly = 0) { chapterRepository.updateReadStates(any()) }
    }

    @Test
    fun `marking unread clears progress only for chapters that had some`() = runBlocking {
        val halfRead = chapter(id = 1, read = false, lastPageRead = 3, totalPages = 20)
        val read = chapter(id = 2, read = true, lastPageRead = 20, totalPages = 20)
        val untouched = chapter(id = 3, read = false)

        val result = subject.await(read = false, halfRead, read, untouched)

        assertEquals(SetReadStatus.Result.Success, result)
        coVerify(exactly = 1) {
            chapterRepository.updateAll(
                match { updates ->
                    updates.map { it.id } == listOf(1L, 2L) &&
                        updates.all { it.read == false && it.lastPageRead == 0L && it.markedReadAt == 0L }
                },
            )
        }
    }

    @Test
    fun `marking read stamps the finish time only for chapters that were unread`() = runBlocking {
        val unread = chapter(id = 1, read = false, totalPages = 10)
        val alreadyRead = chapter(id = 2, read = true, markedReadAt = 7)

        subject.await(read = true, unread, alreadyRead)

        coVerify(exactly = 1) {
            chapterRepository.updateAll(
                match { updates ->
                    val update = updates.single()
                    update.id == 1L && update.read == true && update.markedReadAt != null
                },
            )
        }
    }

    @Test
    fun `an edit that changes nothing writes nothing`() = runBlocking {
        val result = subject.await(read = true, chapter(id = 1, read = true))

        assertEquals(SetReadStatus.Result.NoChapters, result)
        coVerify(exactly = 0) { chapterRepository.updateAll(any()) }
    }

    @Test
    fun `the candidate set matches what the write touches`() {
        val chapters = listOf(
            chapter(id = 1, read = false),
            chapter(id = 2, read = false, lastPageRead = 5),
            chapter(id = 3, read = true, lastPageRead = 9),
        )

        assertEquals(listOf(1L, 2L), chapters.readStateCandidates(read = true).map { it.id })
        assertEquals(listOf(2L, 3L), chapters.readStateCandidates(read = false).map { it.id })
        assertTrue(chapters.toReadStates().all { it.chapterId in listOf(1L, 2L, 3L) })
    }

    private fun chapter(
        id: Long,
        read: Boolean,
        lastPageRead: Long = 0,
        totalPages: Long = 0,
        markedReadAt: Long = 0,
    ): Chapter = Chapter.create().copy(
        id = id,
        read = read,
        lastPageRead = lastPageRead,
        totalPages = totalPages,
        markedReadAt = markedReadAt,
    )
}
