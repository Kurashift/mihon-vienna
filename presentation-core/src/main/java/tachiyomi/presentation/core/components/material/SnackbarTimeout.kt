package tachiyomi.presentation.core.components.material

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SNACKBAR_ACTION_TIMEOUT_MILLIS = 5_000L

/** Owned by the operation, so removing its UI does not stop the timeout. */
class SnackbarTimeout(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onTimeout: () -> Unit,
) {
    private var job: Job? = null
    private var active = false
    private var pressed = false
    private var deadline = 0L

    val deadlineMillis: Long
        get() = if (pressed) freshDeadline() else deadline

    private fun freshDeadline(): Long =
        if (timeoutMillis == Long.MAX_VALUE) Long.MAX_VALUE else nowMillis() + timeoutMillis

    fun start(deadlineMillis: Long = freshDeadline()) {
        job?.cancel()
        active = true
        deadline = deadlineMillis
        if (pressed) return
        job = scope.launch {
            delay((deadlineMillis - nowMillis()).coerceAtLeast(0L))
            active = false
            job = null
            onTimeout()
        }
    }

    fun setPressed(value: Boolean) {
        if (!active || pressed == value) return
        pressed = value
        if (value) {
            job?.cancel()
            job = null
        } else {
            start()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        active = false
        pressed = false
        deadline = 0L
    }
}
