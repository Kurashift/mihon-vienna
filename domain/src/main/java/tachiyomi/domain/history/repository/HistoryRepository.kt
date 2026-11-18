package tachiyomi.domain.history.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryVisibilityUndo
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.model.PermanentReadingRecord
import tachiyomi.domain.history.model.ReadingOverview

interface HistoryRepository {

    suspend fun exportPermanentRecords(): List<PermanentReadingRecord>

    suspend fun importPermanentRecords(records: List<PermanentReadingRecord>, legacyMode: Boolean = false)

    fun subscribeReadingOverview(): Flow<ReadingOverview>

    suspend fun hideHistory(ids: Set<Long>? = null): HistoryVisibilityUndo

    suspend fun undoHistoryVisibility(undo: HistoryVisibilityUndo)

    suspend fun <T> withoutReadingStateChanges(block: suspend () -> T): T

    fun getHistory(query: String): Flow<List<HistoryWithRelations>>

    suspend fun getLastHistory(): HistoryWithRelations?

    suspend fun getTotalReadDuration(): Long

    suspend fun getHistoryByMangaId(mangaId: Long): List<History>

    suspend fun resetHistory(historyId: Long)

    suspend fun resetHistoryByMangaId(mangaId: Long)

    suspend fun resetHistoryByMangaIds(mangaIds: List<Long>)

    suspend fun resetHistoryByIds(historyIds: List<Long>)

    suspend fun resetHistoryBySourceId(sourceId: Long)

    suspend fun deleteAllHistory(): Boolean

    /**
     * Compatibility projection of all retained rows, including hidden and detached records.
     * Detached records use chapterId = -1; permanent export carries their durable identities.
     * Visibility undo uses [hideHistory] and [undoHistoryVisibility], not this projection.
     */
    suspend fun getAllHistory(): List<History>

    /**
     * Compatibility restore using absolute lower bounds for time; never subtracts newer reading.
     * Visibility undo must use [undoHistoryVisibility] to avoid revealing newer hidden records.
     * Permanent backup restores use [importPermanentRecords] to preserve detached identities.
     */
    suspend fun restoreHistory(entries: List<History>)

    suspend fun upsertHistory(historyUpdate: HistoryUpdate)
}
