package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.download.interactor.DeleteDownload
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterReadState
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.time.Clock

class SetReadStatus(
    private val downloadPreferences: DownloadPreferences,
    private val deleteDownload: DeleteDownload,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
) {

    private val mapper = { chapter: Chapter, read: Boolean ->
        ChapterUpdate(
            read = read,
            lastPageRead = if (!read) 0L else chapter.totalPages.takeIf { it > 0 },
            // 标记已读时记录时间点，标记未读时清空——复查页据此稳定排序，不随打开阅读器刷新。
            markedReadAt = if (read) Clock.System.now().toEpochMilliseconds() else 0L,
            id = chapter.id,
        )
    }

    suspend fun await(read: Boolean, vararg chapters: Chapter): Result = withNonCancellableContext {
        val chaptersToUpdate = chapters.toList().readStateCandidates(read)
        if (chaptersToUpdate.isEmpty()) {
            return@withNonCancellableContext Result.NoChapters
        }

        try {
            chapterRepository.updateAll(
                chaptersToUpdate.map { mapper(it, read) },
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }

        if (read && downloadPreferences.removeAfterMarkedAsRead.get()) {
            chaptersToUpdate
                .groupBy { it.mangaId }
                .forEach { (mangaId, chapters) ->
                    // The row can be gone by now; nothing to delete in that case.
                    val manga = mangaRepository.getMangaByIdOrNull(mangaId) ?: return@forEach
                    deleteDownload.awaitAll(
                        manga = manga,
                        chapters = chapters.toTypedArray(),
                    )
                }
        }

        Result.Success
    }

    /**
     * Puts the read state back exactly as [states] describe it.
     *
     * Undoing a read/unread edit is not the same write as making it: the resume point must land
     * where it was, not where the shared update path would normalise it.
     */
    suspend fun restoreReadStates(states: List<ChapterReadState>) = withNonCancellableContext {
        if (states.isEmpty()) return@withNonCancellableContext
        chapterRepository.updateReadStates(states)
    }

    suspend fun await(mangaId: Long, read: Boolean): Result = withNonCancellableContext {
        await(
            read = read,
            chapters = chapterRepository
                .getChapterByMangaId(mangaId)
                .toTypedArray(),
        )
    }

    suspend fun await(manga: Manga, read: Boolean) =
        await(manga.id, read)

    sealed interface Result {
        data object Success : Result
        data object NoChapters : Result
        data class InternalError(val error: Throwable) : Result
    }
}

/**
 * The chapters a read/unread edit will actually change.
 *
 * Shared by the edit itself and by the snapshot taken before it, so an undo can never restore a
 * different set than the one that was touched.
 */
internal fun List<Chapter>.readStateCandidates(read: Boolean): List<Chapter> = filter {
    when (read) {
        true -> !it.read
        false -> it.read || it.lastPageRead > 0
    }
}
