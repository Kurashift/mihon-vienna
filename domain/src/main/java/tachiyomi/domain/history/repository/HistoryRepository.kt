package tachiyomi.domain.history.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations

interface HistoryRepository {

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
     * Every row of the history table, as it stands - including the ones whose `last_read` was
     * reset to 0 and therefore never show on screen. They still count towards the read duration,
     * so a snapshot taken for an undo has to carry them too.
     */
    suspend fun getAllHistory(): List<History>

    /**
     * Puts rows back exactly as [entries] describe them, durations included.
     *
     * Not [upsertHistory]: that one accumulates `time_read`, which would double the reading time
     * of every restored entry.
     */
    suspend fun restoreHistory(entries: List<History>)

    suspend fun upsertHistory(historyUpdate: HistoryUpdate)
}
