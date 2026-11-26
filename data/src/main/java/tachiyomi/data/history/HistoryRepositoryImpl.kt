package tachiyomi.data.history

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOne
import tachiyomi.data.subscribeToOneOrNull
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryVisibilityState
import tachiyomi.domain.history.model.HistoryVisibilityUndo
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.model.PermanentReadingRecord
import tachiyomi.domain.history.model.ReadingOverview
import tachiyomi.domain.history.model.ReadingRecordLocator
import tachiyomi.domain.history.model.mergeImportedRecord
import tachiyomi.domain.history.repository.HistoryRepository
import java.util.Date

private const val HISTORY_ID_CHUNK_SIZE = 500

class HistoryRepositoryImpl(
    private val database: Database,
) : HistoryRepository {

    override suspend fun exportPermanentRecords(): List<PermanentReadingRecord> = database.transactionWithResult {
        val aliases = database.historyQueries.getAllReadingKeyAliases().awaitAsList().groupBy { it.record_key }
        val locators = database.historyQueries.getAllReadingLocators().awaitAsList().groupBy { it.record_key }
        database.historyQueries.getPermanentRecords {
                key,
                work,
                source,
                mangaUrl,
                mangaTitle,
                chapterUrl,
                chapterName,
                readAt,
                duration,
                activity,
                completed,
                version,
                actor,
                hidden,
            ->
            PermanentReadingRecord(
                key, work, source, mangaUrl, mangaTitle, chapterUrl, chapterName,
                readAt, duration, activity != 0L, completed != 0L, version, actor, hidden != 0L,
                aliases[key].orEmpty().map { it.alias_key },
                locators[key].orEmpty().map { ReadingRecordLocator(it.source_id, it.manga_url, it.chapter_url) },
            )
        }.awaitAsList()
    }

    override fun subscribeReadingOverview(): Flow<ReadingOverview> =
        database.historyQueries.getReadingOverview { read, completed, chapters, duration ->
            ReadingOverview(read, completed, chapters, duration)
        }.subscribeToOne()

    override suspend fun <T> withoutReadingStateChanges(block: suspend () -> T): T =
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

    private suspend fun matchingRecords(incoming: PermanentReadingRecord): List<tachiyomi.data.History> {
        val matches = linkedMapOf<String, tachiyomi.data.History>()
        (listOf(incoming.recordKey) + incoming.recordAliases).forEach { key ->
            database.historyQueries.getPermanentRecordByKey(key).awaitAsOneOrNull()?.let { matches[it.record_key] = it }
        }
        (
            listOf(ReadingRecordLocator(incoming.sourceId, incoming.mangaUrl, incoming.chapterUrl)) +
                incoming.locationAliases
            ).forEach { locator ->
            database.historyQueries.getPermanentRecordByLocation(
                locator.sourceId,
                locator.mangaUrl,
                locator.chapterUrl,
            ).awaitAsOneOrNull()?.let { matches[it.record_key] = it }
        }
        return matches.values.toList()
    }

    private fun tachiyomi.data.History.toPermanentRecord() = PermanentReadingRecord(
        record_key, work_key, source_id, manga_url, manga_title, chapter_url, chapter_name,
        last_read?.time ?: 0L, time_read, has_read_activity != 0L, completed != 0L,
        state_version, state_actor, hidden != 0L,
    )

    override suspend fun importPermanentRecords(records: List<PermanentReadingRecord>, legacyMode: Boolean) {
        withoutReadingStateChanges {
            val workKeys = linkedMapOf<String, String>()
            // Resolve every existing work before inserting anything: input order must not split one work.
            records.forEach { incoming ->
                require(incoming.recordKey.isNotBlank() && incoming.workKey.isNotBlank())
                require(incoming.readDuration >= 0 && incoming.readAt >= 0 && incoming.stateVersion >= 0)
                val matches = matchingRecords(incoming)
                val knownWork = matches.firstOrNull()?.work_key
                    ?: database.historyQueries.getWorkKeyByLocation(
                        incoming.sourceId,
                        incoming.mangaUrl,
                    ).awaitAsOneOrNull()
                if (knownWork != null) workKeys.putIfAbsent(incoming.workKey, knownWork)
            }
            records.forEach { incoming ->
                val matches = matchingRecords(incoming)
                val existing = matches.firstOrNull()
                val key = existing?.record_key ?: incoming.recordKey
                val work = workKeys[incoming.workKey] ?: incoming.workKey
                var merged = existing?.toPermanentRecord() ?: incoming
                matches.drop(1).forEach { merged = merged.mergeImportedRecord(it.toPermanentRecord()) }
                merged = merged.mergeImportedRecord(incoming, legacyMode)
                if (existing == null) {
                    database.historyQueries.insertPermanentRecord(
                        recordKey = key, workKey = work, sourceId = incoming.sourceId,
                        mangaUrl = incoming.mangaUrl, mangaTitle = incoming.mangaTitle,
                        chapterUrl = incoming.chapterUrl, chapterName = incoming.chapterName,
                        readAt = Date(merged.readAt), readDuration = merged.readDuration,
                        hasReadActivity = if (merged.hasReadActivity) 1 else 0,
                        completed = if (merged.completed) 1 else 0,
                        stateVersion = merged.stateVersion, stateActor = merged.stateActor,
                        hidden = if (merged.hidden) 1 else 0,
                    )
                } else {
                    database.historyQueries.updatePermanentRecord(
                        readAt = Date(merged.readAt),
                        readDuration = merged.readDuration,
                        hasReadActivity = if (merged.hasReadActivity) 1 else 0,
                        completed = if (merged.completed) 1 else 0,
                        stateVersion = merged.stateVersion,
                        stateActor = merged.stateActor,
                        hidden = if (merged.hidden) 1 else 0,
                        recordKey = key,
                    )
                }
                matches.forEach { match ->
                    database.historyQueries.setWorkKey(work, match.work_key)
                    if (match.record_key != key) {
                        database.historyQueries.redirectLocatorsByKey(key, match.record_key)
                        database.historyQueries.redirectKeyAliasesByKey(key, match.record_key)
                        database.historyQueries.redirectMergedChapterLinks(key, match.record_key)
                        database.historyQueries.insertReadingKeyAlias(match.record_key, key)
                        database.historyQueries.removeMergedHistory(removedKey = match.record_key, keptKey = key)
                    }
                }
                database.historyQueries.insertReadingLocator(
                    incoming.sourceId,
                    incoming.mangaUrl,
                    incoming.chapterUrl,
                    key,
                )
                (incoming.recordAliases + merged.recordAliases + incoming.recordKey).filter {
                    it != key
                }.distinct().forEach {
                    database.historyQueries.insertReadingKeyAlias(it, key)
                }
                (incoming.locationAliases + merged.locationAliases).distinct().forEach {
                    database.historyQueries.insertReadingLocator(it.sourceId, it.mangaUrl, it.chapterUrl, key)
                }
                val local = existing?.toPermanentRecord()
                val changedState = local == null || matches.size > 1 || merged.completed != local.completed ||
                    merged.stateVersion != local.stateVersion || merged.stateActor != local.stateActor
                if (changedState) database.historyQueries.applyPermanentCompletionToChapter(key)
            }
            val newlyAttached = database.historyQueries.getUnlinkedChapterLocators().awaitAsList()
            database.historyQueries.attachPermanentRecords()
            database.historyQueries.attachPrimaryChapter()
            newlyAttached.forEach {
                database.historyQueries.applyPermanentCompletionToAttachedChapter(it._id)
            }
        }
    }

    override suspend fun hideHistory(ids: Set<Long>?): HistoryVisibilityUndo = database.transactionWithResult {
        val entries = if (ids == null) {
            database.historyQueries.getVisibleHistoryIds { id, version -> HistoryVisibilityState(id, version + 1) }
                .awaitAsList()
        } else if (ids.isEmpty()) {
            emptyList()
        } else {
            ids.toList().chunked(HISTORY_ID_CHUNK_SIZE).flatMap { chunk ->
                database.historyQueries.getVisibleHistoryIdsIn(chunk) { id, version ->
                    HistoryVisibilityState(id, version + 1)
                }.awaitAsList()
            }
        }
        if (entries.isNotEmpty()) {
            entries.map { it.historyId }.chunked(HISTORY_ID_CHUNK_SIZE).forEach { chunk ->
                database.historyQueries.resetHistoryByIds(chunk)
            }
        }
        HistoryVisibilityUndo(entries)
    }

    override suspend fun undoHistoryVisibility(undo: HistoryVisibilityUndo) {
        database.transaction {
            undo.entries.forEach { database.historyQueries.restoreVisibility(it.historyId, it.hiddenVersion) }
        }
    }

    override fun getHistory(query: String): Flow<List<HistoryWithRelations>> {
        return database.historyViewQueries
            .history(query, HistoryMapper::mapHistoryWithRelations)
            .subscribeToList()
    }

    override suspend fun getLastHistory(): HistoryWithRelations? {
        return database.historyViewQueries
            .getLatestHistory(HistoryMapper::mapHistoryWithRelations)
            .awaitAsOneOrNull()
    }

    override suspend fun getTotalReadDuration(): Long {
        return database.historyQueries
            .getReadDuration()
            .awaitAsOne()
    }

    override suspend fun getHistoryByMangaId(mangaId: Long): List<History> {
        return database.historyQueries
            .getHistoryByMangaId(mangaId, HistoryMapper::mapHistory)
            .awaitAsList()
    }

    override suspend fun resetHistory(historyId: Long) {
        try {
            database.historyQueries.resetHistoryById(historyId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun resetHistoryByMangaId(mangaId: Long) {
        try {
            database.historyQueries.resetHistoryByMangaId(mangaId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun resetHistoryByMangaIds(mangaIds: List<Long>) {
        if (mangaIds.isEmpty()) return
        try {
            database.historyQueries.resetHistoryByMangaIds(mangaIds)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun resetHistoryByIds(historyIds: List<Long>) {
        if (historyIds.isEmpty()) return
        try {
            database.historyQueries.resetHistoryByIds(historyIds)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun resetHistoryBySourceId(sourceId: Long) {
        try {
            database.historyQueries.resetHistoryBySourceId(sourceId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun deleteAllHistory(): Boolean {
        return try {
            database.historyQueries.removeAllHistory()
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            false
        }
    }

    override suspend fun getAllHistory(): List<History> {
        return try {
            database.historyQueries.getAllHistory(HistoryMapper::mapHistory).awaitAsList()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            emptyList()
        }
    }

    override suspend fun restoreHistory(entries: List<History>) {
        if (entries.isEmpty()) return
        try {
            database.transaction {
                entries.forEach { entry ->
                    database.historyQueries.restoreHistory(
                        chapterId = entry.chapterId,
                        // A row whose last_read was reset to 0 comes back as Date(0), not null;
                        // null here would only mean "no row", which never happens.
                        readAt = entry.readAt?.time ?: 0L,
                        time_read = entry.readDuration,
                    )
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun upsertHistory(historyUpdate: HistoryUpdate) {
        try {
            database.historyQueries.upsert(
                readAt = historyUpdate.readAt,
                time_read = historyUpdate.sessionReadDuration,
                chapterId = historyUpdate.chapterId,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }
}
