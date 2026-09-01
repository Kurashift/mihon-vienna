package tachiyomi.domain.chapter.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository

class UpdateChapter(
    private val chapterRepository: ChapterRepository,
) {

    suspend fun await(chapterUpdate: ChapterUpdate) {
        try {
            chapterRepository.update(chapterUpdate)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    suspend fun awaitAll(chapterUpdates: List<ChapterUpdate>) {
        try {
            chapterRepository.updateAll(chapterUpdates)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    suspend fun awaitReaderProgress(
        chapterId: Long,
        pageNumber: Long,
        totalPages: Long,
        completed: Boolean,
        completedAt: Long,
    ) {
        try {
            chapterRepository.updateReaderProgress(
                chapterId = chapterId,
                pageNumber = pageNumber,
                totalPages = totalPages,
                completed = completed,
                completedAt = completedAt,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    suspend fun awaitBumpVersion(chapterId: Long) {
        try {
            chapterRepository.bumpVersion(chapterId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
