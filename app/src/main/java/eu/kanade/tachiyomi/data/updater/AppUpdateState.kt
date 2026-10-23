package eu.kanade.tachiyomi.data.updater

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.domain.release.model.Release

/** Holds the latest update result for screens that expose an update indicator. */
object AppUpdateState {
    private val _availableRelease = MutableStateFlow<Release?>(null)
    val availableRelease: StateFlow<Release?> = _availableRelease.asStateFlow()

    /**
     * Whether the start-up prompt was already answered, and when.
     *
     * Dismissing the prompt — the button, a tap outside it, or back — means "not now", so it only
     * silences the prompt for the visit it was given in. [resetPromptForNewVisit] re-arms it when
     * the app is opened again; 不再提示 is the only answer that lasts.
     */
    private val _promptAnswered = MutableStateFlow(false)
    val promptAnswered: StateFlow<Boolean> = _promptAnswered.asStateFlow()

    fun setAvailable(release: Release) {
        // Only a different release re-arms the prompt. The same one arriving again — a manual
        // check from About, say — must not undo a dismissal the reader just gave.
        val isNewRelease = _availableRelease.value?.version != release.version
        _availableRelease.value = release
        if (isNewRelease) {
            _promptAnswered.value = false
        }
    }

    fun clear() {
        _availableRelease.value = null
    }

    /** Hides the prompt until the app is opened again. */
    fun dismissPrompt() {
        _promptAnswered.value = true
    }

    /**
     * Re-arms the prompt for a new visit.
     *
     * Called when the whole app returns to the foreground, not when an activity resumes: walking
     * from the library into the reader and back is the same visit, and re-asking there would put
     * the dialog in the reader's way on every chapter.
     */
    fun resetPromptForNewVisit() {
        _promptAnswered.value = false
    }
}
