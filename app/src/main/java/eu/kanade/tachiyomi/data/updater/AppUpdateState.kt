package eu.kanade.tachiyomi.data.updater

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.domain.release.model.Release

/** Holds the latest update result for screens that expose an update indicator. */
object AppUpdateState {
    private val _availableRelease = MutableStateFlow<Release?>(null)
    val availableRelease: StateFlow<Release?> = _availableRelease.asStateFlow()

    fun setAvailable(release: Release) {
        _availableRelease.value = release
    }

    fun clear() {
        _availableRelease.value = null
    }
}
