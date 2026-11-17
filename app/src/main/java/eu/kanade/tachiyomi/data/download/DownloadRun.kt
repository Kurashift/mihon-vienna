package eu.kanade.tachiyomi.data.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class DownloadRun(private val scope: CoroutineScope) {
    @Volatile
    private var current: Job? = null

    val isRunning: Boolean
        get() = current?.isActive == true

    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized
    fun start(block: suspend CoroutineScope.(Job) -> Unit) {
        if (isRunning) return
        val previous = current
        val published = CompletableDeferred<Unit>()
        val next = scope.launch(start = CoroutineStart.ATOMIC) {
            withContext(NonCancellable) {
                published.await()
                previous?.join()
            }
            coroutineContext.ensureActive()
            block(coroutineContext[Job]!!)
        }
        current = next
        published.complete(Unit)
    }

    @Synchronized
    fun cancel() {
        current?.cancel()
    }

    fun isCurrent(owner: Job): Boolean = current === owner && owner.isActive
}
