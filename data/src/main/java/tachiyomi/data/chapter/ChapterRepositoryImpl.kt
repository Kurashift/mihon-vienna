package tachiyomi.data.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import logcat.LogPriority
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.subscribeToList
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterReadState
import tachiyomi.domain.chapter.model.ChapterTranslatedName
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository

class ChapterRepositoryImpl(
    private val database: Database,
) : ChapterRepository {

    override suspend fun addAll(chapters: List<Chapter>): List<Chapter> {
        return try {
            database.transactionWithResult {
                chapters.map { insertChapter(it) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    override suspend fun applySourceSync(
        removedChapterIds: List<Long>,
        newChapters: List<Chapter>,
        chapterUpdates: List<ChapterUpdate>,
    ): List<Chapter> {
        return database.transactionWithResult {
            currentCoroutineContext().ensureActive()
            if (removedChapterIds.isNotEmpty()) {
                database.chaptersQueries.removeChaptersWithIds(removedChapterIds)
            }
            val inserted = newChapters.distinctBy { it.mangaId to it.url }.mapNotNull { chapter ->
                currentCoroutineContext().ensureActive()
                val existing = database.chaptersQueries.getChapterByUrlAndMangaId(
                    chapter.url,
                    chapter.mangaId,
                ).awaitAsOneOrNull()
                if (existing == null) insertChapter(chapter) else null
            }
            chapterUpdates.forEach { update ->
                currentCoroutineContext().ensureActive()
                database.chaptersQueries.updateSourceFields(
                    name = update.name,
                    scanlator = update.scanlator,
                    chapterNumber = update.chapterNumber,
                    sourceOrder = update.sourceOrder,
                    dateUpload = update.dateUpload,
                    totalPages = update.totalPages,
                    memo = update.memo?.let(MemoColumnAdapter::encode),
                    chapterId = update.id,
                )
            }
            currentCoroutineContext().ensureActive()
            inserted
        }
    }

    private suspend fun insertChapter(chapter: Chapter): Chapter {
        val chapterId = database.chaptersQueries.insertReturningId(
            mangaId = chapter.mangaId,
            url = chapter.url,
            name = chapter.name,
            scanlator = chapter.scanlator,
            read = chapter.read,
            bookmark = chapter.bookmark,
            totalPages = chapter.totalPages,
            lastPageRead = chapter.lastPageRead,
            customOrder = chapter.customOrder,
            chapterNumber = chapter.chapterNumber,
            sourceOrder = chapter.sourceOrder,
            dateFetch = chapter.dateFetch,
            dateUpload = chapter.dateUpload,
            version = chapter.version,
            memo = chapter.memo,
            translatedName = chapter.translatedName,
            markedReadAt = chapter.markedReadAt,
        ).awaitAsOne()
        return chapter.copy(id = chapterId)
    }

    override suspend fun update(chapterUpdate: ChapterUpdate) {
        partialUpdate(chapterUpdate)
    }

    override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
        partialUpdate(*chapterUpdates.toTypedArray())
    }

    override suspend fun captureReadStates(chapters: List<Chapter>, read: Boolean): List<ChapterReadState> =
        database.transactionWithResult {
            chapters.map { chapter ->
                val record = database.historyQueries.getPermanentRecordByChapterId(chapter.id).awaitAsOneOrNull()
                val version = record?.state_version
                val changesState = record?.completed != read.toLong() || version == 0L
                ChapterReadState(
                    chapter.id, chapter.read, chapter.lastPageRead, chapter.markedReadAt,
                    record?.completed?.let { it != 0L }, version,
                    version?.let { it + if (changesState) 1 else 0 }, read, record?.visibility_version,
                )
            }
        }

    override suspend fun updateReadStates(states: List<ChapterReadState>) {
        if (states.isEmpty()) return
        withoutReadingStateChanges {
            // Validate every token before changing any canonical record shared by several attachments.
            val accepted = states.mapNotNull { state ->
                val record = database.historyQueries.getPermanentRecordByChapterId(state.chapterId).awaitAsOneOrNull()
                val chapter = database.chaptersQueries.getChapterById(state.chapterId).awaitAsOneOrNull()
                if (state.expectedStateVersion != null && (
                        record?.state_version != state.expectedStateVersion ||
                            record?.visibility_version != state.historyVisibilityVersion ||
                            chapter?.read != state.expectedRead
                        )
                ) {
                    null
                } else {
                    state to record?.record_key
                }
            }
            accepted.forEach { (state, _) ->
                database.chaptersQueries.restoreReadState(
                    read = state.read,
                    lastPageRead = state.lastPageRead,
                    markedReadAt = state.markedReadAt,
                    chapterId = state.chapterId,
                )
            }
            accepted.distinctBy { (_, key) -> key }.forEach { (state, _) ->
                database.historyQueries.restorePermanentCompletion(
                    completed = (state.permanentCompleted ?: state.read).toLong(),
                    chapterId = state.chapterId,
                )
            }
        }
    }

    private suspend fun <T> withoutReadingStateChanges(block: suspend () -> T): T =
        database.transactionWithResult {
            database.historyQueries.ensureStateContext()
            val previous = database.historyQueries.getStateTrackingSuppressed().awaitAsOne()
            database.historyQueries.setStateTrackingSuppressed(1)
            try {
                block()
            } finally {
                database.historyQueries.setStateTrackingSuppressed(previous)
            }
        }

    override suspend fun clearProgress(chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) return
        withoutReadingStateChanges { database.chaptersQueries.clearProgressByChapterIds(chapterIds) }
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
            val keptKey = database.historyQueries.getPermanentRecordByChapterId(
                chapterUpdate.id,
            ).awaitAsOne().record_key
            val removedKey = database.historyQueries.getPermanentRecordByChapterId(
                duplicateChapterId,
            ).awaitAsOne().record_key
            database.historyQueries.mergeForChapterMove(chapterUpdate.id, duplicateChapterId)
            database.historyQueries.redirectMergedLocators(chapterUpdate.id, duplicateChapterId)
            database.historyQueries.redirectMergedKeyAliases(chapterUpdate.id, duplicateChapterId)
            database.historyQueries.rememberMergedRecordKey(chapterUpdate.id, duplicateChapterId)
            database.historyQueries.redirectMergedChapterLinks(keptKey, removedKey)
            database.historyQueries.removeMergedHistory(removedKey = removedKey, keptKey = keptKey)
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
                markedReadAt = chapterUpdate.markedReadAt,
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
        completedAt: Long,
    ) {
        database.transaction {
            database.chaptersQueries.updateReaderProgress(
                chapterId = chapterId,
                pageNumber = pageNumber,
                totalPages = totalPages,
                completed = completed.toLong(),
                completedAt = completedAt,
            )
            if (pageNumber > 2 || completed) database.historyQueries.markReadActivity(chapterId)
        }
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
                    markedReadAt = chapterUpdate.markedReadAt,
                )
                chapterUpdate.read?.let { read ->
                    database.historyQueries.setPermanentCompletion(read.toLong(), listOf(chapterUpdate.id))
                }
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
            withoutReadingStateChanges { database.chaptersQueries.setAllChaptersUnreadBySource(sourceId) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    override suspend fun setAllChaptersUnreadByMangaIds(mangaIds: List<Long>) {
        if (mangaIds.isEmpty()) return
        try {
            withoutReadingStateChanges { database.chaptersQueries.setAllChaptersUnreadByMangaIds(mangaIds) }
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

    override fun observeChaptersByMangaIds(
        mangaIds: List<Long>,
        applyScanlatorFilter: Boolean,
    ): Flow<List<Chapter>> {
        if (mangaIds.isEmpty()) return flowOf(emptyList())
        return database.chaptersQueries
            .getChaptersByMangaIds(mangaIds, applyScanlatorFilter.toLong(), ::mapChapter)
            .subscribeToList()
    }

    override suspend fun getTranslatedNames(mangaIds: List<Long>): List<ChapterTranslatedName> {
        if (mangaIds.isEmpty()) return emptyList()
        return database.chaptersQueries
            .getTranslatedNamesByMangaIds(mangaIds) { mangaId, translatedName ->
                ChapterTranslatedName(mangaId = mangaId, name = translatedName.trim())
            }
            .awaitAsList()
    }

    override fun observeTranslatedNames(mangaIds: List<Long>): Flow<List<ChapterTranslatedName>> {
        if (mangaIds.isEmpty()) return flowOf(emptyList())
        return database.chaptersQueries
            .getTranslatedNamesByMangaIds(mangaIds) { mangaId, translatedName ->
                ChapterTranslatedName(mangaId = mangaId, name = translatedName.trim())
            }
            .subscribeToList()
    }

    override suspend fun getTranslatedNamesBySourceId(sourceId: Long): Map<String, List<String>> {
        return database.chaptersQueries
            .getTranslatedNamesBySourceId(sourceId) { url, translatedName -> url to translatedName.trim() }
            .awaitAsList()
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, names) -> names.distinct() }
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
        markedReadAt: Long,
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
        markedReadAt = markedReadAt,
    )
}
