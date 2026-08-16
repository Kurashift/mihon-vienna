package eu.kanade.tachiyomi.data.backup

import tachiyomi.core.common.preference.Preference

/**
 * Preferences restored through a dedicated identity-aware path, or rebuilt from this device.
 */
internal object BackupPreferencePolicy {
    val dedicatedRestoreKeys = setOf(
        "marked_chapters",
        "good_doujins",
    )

    val rebuildableKeys = setOf(
        "local_source_sync_mtime",
    )

    private val privateKeys = setOf(
        "audio_auth_token",
    )

    fun isPrivate(key: String): Boolean {
        return Preference.isPrivate(key) || key in privateKeys
    }
}
