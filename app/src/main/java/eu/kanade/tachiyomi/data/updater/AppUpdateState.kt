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
     * Whether the start-up prompt was already answered in this process.
     *
     * Deliberately not persisted: 知道了 means "not now", and the point of that button is that the
     * next launch asks again. 忽略更新 is the other half, stored as a version in preferences.
     */
    private val _promptAnswered = MutableStateFlow(false)
    val promptAnswered: StateFlow<Boolean> = _promptAnswered.asStateFlow()

    fun setAvailable(release: Release) {
        // Only a different release re-arms the prompt. The same one arriving again — a manual
        // check from About, say — must not undo a "知道了" the reader just gave in this process.
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
}
