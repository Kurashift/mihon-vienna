package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.source.service.SourceManager

class PreferenceBackupCreatorTest {

    private val preferenceStore = mockk<PreferenceStore>()
    private val creator = PreferenceBackupCreator(
        sourceManager = mockk<SourceManager>(),
        preferenceStore = preferenceStore,
    )

    @Test
    fun `dedicated and rebuildable preferences are not copied as app settings`() {
        every { preferenceStore.getAll() } returns mapOf(
            "marked_chapters" to "old ids",
            "good_doujins" to "old ids",
            "local_source_sync_mtime" to 123L,
            "audio_history" to "history",
        )

        val preferences = creator.createApp(includePrivatePreferences = true)

        assertEquals(listOf("audio_history"), preferences.map { it.key })
    }

    @Test
    fun `audio token follows the private settings option`() {
        every { preferenceStore.getAll() } returns mapOf(
            "audio_auth_token" to "secret",
            "audio_username" to "user",
        )

        val publicPreferences = creator.createApp(includePrivatePreferences = false)
        val privatePreferences = creator.createApp(includePrivatePreferences = true)

        assertFalse(publicPreferences.any { it.key == "audio_auth_token" })
        assertTrue(privatePreferences.any { it.key == "audio_auth_token" })
        assertTrue(publicPreferences.any { it.key == "audio_username" })
    }

    @Test
    fun `app state remains excluded`() {
        val appStateKey = Preference.appStateKey("search_history_library")
        every { preferenceStore.getAll() } returns mapOf(
            appStateKey to "queries",
            "audio_favorite_works" to "favorites",
        )

        val preferences = creator.createApp(includePrivatePreferences = true)

        assertFalse(preferences.any { it.key == appStateKey })
        assertEquals(
            "favorites",
            (preferences.single().value as StringPreferenceValue).value,
        )
    }
}
