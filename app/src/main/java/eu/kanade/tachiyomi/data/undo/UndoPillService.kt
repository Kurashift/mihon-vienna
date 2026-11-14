package eu.kanade.tachiyomi.data.undo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.SNACKBAR_ACTION_TIMEOUT_MILLIS
import tachiyomi.presentation.core.components.material.SnackbarTimeout

/**
 * The app's one undo pill.
 *
 * It lives outside any screen on purpose: an undo offered for something that already happened
 * should not disappear because the user switched tabs or opened a work. The pill keeps its own
 * countdown from the moment it is shown, no matter what is on screen.
 *
 * Replaced pills are not undone - only an explicit tap on the label runs [onUndo].
 */
class UndoPillService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutablePill = MutableStateFlow<Pill?>(null)
    val pill = mutablePill.asStateFlow()

    private val timer = SnackbarTimeout(scope, SNACKBAR_ACTION_TIMEOUT_MILLIS) { dismiss() }

    private var pendingUndo: (suspend () -> Unit)? = null
    private var resolution: CompletableDeferred<Boolean>? = null
    private var nextRevision = 0

    data class Pill(val message: String, val revision: Int)

    /**
     * Shows [message] with an undo action, replacing whatever pill is up.
     *
     * @return completes with whether the action was undone, for callers that have to wait before
     * asking a follow-up question. Awaiting is optional: the pill and its countdown do not depend
     * on the caller still being alive.
     */
    fun show(message: String, onUndo: suspend () -> Unit): Deferred<Boolean> {
        resolve(undone = false)
        nextRevision++
        pendingUndo = onUndo
        val deferred = CompletableDeferred<Boolean>()
        resolution = deferred
        mutablePill.value = Pill(message = message, revision = nextRevision)
        timer.start()
        return deferred
    }

    fun undo() {
        val action = pendingUndo ?: return
        pendingUndo = null
        timer.cancel()
        mutablePill.value = null
        resolve(undone = true)
        scope.launch { action() }
    }

    /** Dismissed by the countdown running out or by a swipe: the change stays. */
    fun dismiss() {
        timer.cancel()
        pendingUndo = null
        mutablePill.value = null
        resolve(undone = false)
    }

    fun setPressed(pressed: Boolean) {
        timer.setPressed(pressed)
    }

    private fun resolve(undone: Boolean) {
        resolution?.complete(undone)
        resolution = null
    }
}
