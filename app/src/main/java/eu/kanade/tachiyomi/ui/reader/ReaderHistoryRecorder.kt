package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tachiyomi.domain.history.model.HistoryUpdate
import java.util.Date

internal class ReaderHistoryRecorder(
    private val upsert: suspend (HistoryUpdate) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var startedAt: Long? = null
    private var pendingSave: Job? = null

    @Synchronized
    fun restartTimer() {
        startedAt = now()
    }

    @Synchronized
    fun save(
        chapterId: Long,
        incognito: Boolean,
        flushProgress: suspend () -> Unit = {},
    ): Job? {
        val endedAt = now()
        val duration = startedAt?.let { (endedAt - it).coerceAtLeast(0) } ?: 0
        startedAt = null
        if (incognito) return null

        val update = HistoryUpdate(chapterId, Date(endedAt), duration)
        val previousSave = pendingSave
        return scope.launch {
            previousSave?.join()
            try {
                flushProgress()
            } catch (error: Exception) {
                onError(error)
            }
            try {
                upsert(update)
            } catch (error: Exception) {
                onError(error)
            }
        }.also { pendingSave = it }
    }
}
