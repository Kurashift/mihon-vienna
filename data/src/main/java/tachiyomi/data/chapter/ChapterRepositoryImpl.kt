package tachiyomi.data.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import logcat.LogPriority
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.subscribeToList
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository

class ChapterRepositoryImpl(
    private val database: Database,
) : ChapterRepository {

    override suspend fun addAll(chapters: List<Chapter>): List<Chapter> {
        return try {
            database.transactionWithResult {
                chapters.map { chapter ->
                    val chapterId = database.chaptersQueries.insertReturningId(
                        chapter.mangaId,
                        chapter.url,
                        chapter.name,
                        chapter.scanlator,
                        chapter.read,
                        chapter.bookmark,
                        chapter.lastPageRead,
                        chapter.totalPages,
                        chapter.customOrder,
                        chapter.chapterNumber,
                        chapter.sourceOrder,
                        chapter.dateFetch,
                        chapter.dateUpload,
                        chapter.version,
                        chapter.memo,
                        chapter.translatedName,
                    )
                        .awaitAsOne()
                    chapter.copy(id = chapterId)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    override suspend fun update(chapterUpdate: ChapterUpdate) {
        partialUpdate(chapterUpdate)
    }

    override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
        partialUpdate(*chapterUpdates.toTypedArray())
    }

    override suspend fun relocateAll(chapterUpdates: List<ChapterUpdate>) {
        database.transaction {
            chapterUpdates.forEach { chapterUpdate ->
                val mangaId = requireNotNull(chapterUpdate.mangaId)
                val url = requireNotNull(chapterUpdate.url)
                database.chaptersQueries.relocate(
                    mangaId = mangaId,
                    url = url,
                    chapterId = chapterUpdate.id,
                )
                database.good_doujinsQueries.relocate(
                    mangaId = mangaId,
                    chapterId = chapterUpdate.id,
                )
            }
        }
    }

    override suspend fun mergeRelocatedChapter(chapterUpdate: ChapterUpdate, duplicateChapterId: Long) {
        database.transaction {
            val mangaId = requireNotNull(chapterUpdate.mangaId)
            val url = requireNotNull(chapterUpdate.url)
            database.historyQueries.mergeForChapterMove(chapterUpdate.id, duplicateChapterId)
            database.good_doujinsQueries.mergeForChapterMove(chapterUpdate.id, mangaId, duplicateChapterId)
            database.chaptersQueries.removeChaptersWithIds(listOf(duplicateChapterId))
            database.chaptersQueries.relocate(
                mangaId = mangaId,
                url = url,
                chapterId = chapterUpdate.id,
            )
            database.chaptersQueries.update(
                mangaId = mangaId,
                url = url,
                name = chapterUpdate.name,
                scanlator = chapterUpdate.scanlator,
                read = chapterUpdate.read,
                bookmark = chapterUpdate.bookmark,
                lastPageRead = chapterUpdate.lastPageRead,
                totalPages = chapterUpdate.totalPages,
                customOrder = chapterUpdate.customOrder,
                chapterNumber = chapterUpdate.chapterNumber,
                sourceOrder = chapterUpdate.sourceOrder,
                dateFetch = chapterUpdate.dateFetch,
                dateUpload = chapterUpdate.dateUpload,
                chapterId = chapterUpdate.id,
                version = chapterUpdate.version,
                isSyncing = 0,
                memo = chapterUpdate.memo?.let(MemoColumnAdapter::encode),
                translatedName = chapterUpdate.translatedName,
            )
        }
    }

    override suspend fun bumpVersion(chapterId: Long) {
        database.chaptersQueries.bumpVersion(chapterId)
    }

    override suspend fun updateReaderProgress(
        chapterId: Long,
        pageNumber: Long,
        totalPages: Long,
        completed: Boolean,
    ) {
        database.chaptersQueries.updateReaderProgress(
            chapterId = chapterId,
            pageNumber = pageNumber,
            totalPages = totalPages,
            completed = completed.toLong(),
        )
    }

    private suspend fun partialUpdate(vararg chapterUpdates: ChapterUpdate) {
        database.transaction {
            chapterUpdates.forEach { chapterUpdate ->
                database.chaptersQueries.update(
                    mangaId = chapterUpdate.mangaId,
                    url = chapterUpdate.url,
                    name = chapterUpdate.name,
                    scanlator = chapterUpdate.scanlator,
                    read = chapterUpdate.read,
                    bookmark = chapterUpdate.bookmark,
                    lastPageRead = chapterUpdate.lastPageRead,
                    totalPages = chapterUpdate.totalPages,
                    customOrder = chapterUpdate.customOrder,
                    chapterNumber = chapterUpdate.chapterNumber,
                    sourceOrder = chapterUpdate.sourceOrder,
                    dateFetch = chapterUpdate.dateFetch,
                    dateUpload = chapterUpdate.dateUpload,
                    chapterId = chapterUpdate.id,
                    version = chapterUpdate.version,
                    isSyncing = 0,
                    memo = chapterUpdate.memo?.let(MemoColumnAdapter::encode),
                    translatedName = chapterUpdate.translatedName,
                )
            }
        }
    }

    override suspend fun removeChaptersWithIds(chapterIds: List<Long>) {
        try {
            database.chaptersQueries.removeChaptersWithIds(chapterIds)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    override suspend fun setAllChaptersUnreadBySource(sourceId: Long) {
        try {
            database.chaptersQueries.setAllChaptersUnreadBySource(sourceId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    override suspend fun setAllChaptersUnreadByMangaIds(mangaIds: List<Long>) {
        if (mangaIds.isEmpty()) return
        try {
            database.chaptersQueries.setAllChaptersUnreadByMangaIds(mangaIds)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
        return database.chaptersQueries
            .getChaptersByMangaId(mangaId, applyScanlatorFilter.toLong(), ::mapChapter)
            .awaitAsList()
    }

    override suspend fun getChaptersByMangaIds(
        mangaIds: List<Long>,
        applyScanlatorFilter: Boolean,
    ): List<Chapter> {
        if (mangaIds.isEmpty()) return emptyList()
        return database.chaptersQueries
            .getChaptersByMangaIds(mangaIds, applyScanlatorFilter.toLong(), ::mapChapter)
            .awaitAsList()
    }

    override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> {
        return database.chaptersQueries
            .getScanlatorsByMangaId(mangaId) { it.orEmpty() }
            .awaitAsList()
    }

    override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> {
        return database.chaptersQueries
            .getScanlatorsByMangaId(mangaId) { it.orEmpty() }
            .subscribeToList()
    }

    override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> {
        return database.chaptersQueries
            .getBookmarkedChaptersByMangaId(mangaId, ::mapChapter)
            .awaitAsList()
    }

    override suspend fun getChapterById(id: Long): Chapter? {
        return database.chaptersQueries
            .getChapterById(id, ::mapChapter)
            .awaitAsOneOrNull()
    }

    override suspend fun getChapterByMangaIdAsFlow(mangaId: Long, applyScanlatorFilter: Boolean): Flow<List<Chapter>> {
        return database.chaptersQueries
            .getChaptersByMangaId(mangaId, applyScanlatorFilter.toLong(), ::mapChapter)
            .subscribeToList()
    }

    override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? {
        return database.chaptersQueries
            .getChapterByUrlAndMangaId(url, mangaId, ::mapChapter)
            .awaitAsOneOrNull()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapChapter(
        id: Long,
        mangaId: Long,
        url: String,
        name: String,
        scanlator: String?,
        read: Boolean,
        bookmark: Boolean,
        lastPageRead: Long,
        chapterNumber: Double,
        sourceOrder: Long,
        dateFetch: Long,
        dateUpload: Long,
        lastModifiedAt: Long,
        version: Long,
        isSyncing: Long,
        memo: JsonObject,
        totalPages: Long,
        customOrder: Long,
        translatedName: String?,
    ): Chapter = Chapter(
        id = id,
        mangaId = mangaId,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        totalPages = totalPages,
        customOrder = customOrder,
        dateFetch = dateFetch,
        sourceOrder = sourceOrder,
        url = url,
        name = name,
        dateUpload = dateUpload,
        chapterNumber = chapterNumber,
        scanlator = scanlator,
        lastModifiedAt = lastModifiedAt,
        version = version,
        memo = memo,
        translatedName = translatedName,
    )
}
