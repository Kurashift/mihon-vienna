package eu.kanade.tachiyomi.data.updater

import android.os.SystemClock
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
     * Dismissing the prompt — the button, tapping outside, or back — means "not now", so it only
     * silences the prompt while the reader stays in the app. Coming back after being away for a
     * while asks again, which is what the three buttons promise: 不再提示 is the only answer that
     * lasts, 跳过这版 lasts until the next release.
     */
    private val _promptAnswered = MutableStateFlow(false)
    val promptAnswered: StateFlow<Boolean> = _promptAnswered.asStateFlow()

    private var promptDismissedAt = 0L

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

    /** Hides the prompt until the reader leaves the app for a while. */
    fun dismissPrompt(now: Long = SystemClock.uptimeMillis()) {
        _promptAnswered.value = true
        promptDismissedAt = now
    }

    /**
     * Re-arms a dismissal that has gone stale, so reopening the app asks again.
     *
     * The activity is resumed on every return — from the reader, from the installer, from another
     * app — so a dismissal is only allowed to expire once the reader has actually been away.
     */
    fun resetPromptIfStale(now: Long = SystemClock.uptimeMillis()) {
        if (!_promptAnswered.value) return
        if (now - promptDismissedAt < PROMPT_DISMISSAL_LIFETIME_MILLIS) return
        _promptAnswered.value = false
    }
}

/** How long a dismissed prompt stays quiet before the next app visit asks again. */
private const val PROMPT_DISMISSAL_LIFETIME_MILLIS = 30_000L
