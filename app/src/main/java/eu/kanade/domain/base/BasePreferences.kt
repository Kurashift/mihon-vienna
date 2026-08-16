package eu.kanade.domain.base

import android.content.Context
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.util.system.GLUtil
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.MR

class BasePreferences(
    val context: Context,
    preferenceStore: PreferenceStore,
) {

    val downloadedOnly: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("pref_downloaded_only"),
        false,
    )

    val incognitoMode: Preference<Boolean> = preferenceStore.getBoolean(Preference.appStateKey("incognito_mode"), false)

    val extensionInstaller: ExtensionInstallerPreference = ExtensionInstallerPreference(context, preferenceStore)

    val shownOnboardingFlow: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("onboarding_complete"),
        false,
    )

    enum class ExtensionInstaller(val titleRes: StringResource, val requiresSystemPermission: Boolean) {
        LEGACY(MR.strings.ext_installer_legacy, true),
        PACKAGEINSTALLER(MR.strings.ext_installer_packageinstaller, true),
        SHIZUKU(MR.strings.ext_installer_shizuku, false),
        PRIVATE(MR.strings.ext_installer_private, false),
    }

    val displayProfile: Preference<String> = preferenceStore.getString("pref_display_profile_key", "")

    val hardwareBitmapThreshold: Preference<Int> = preferenceStore.getInt(
        "pref_hardware_bitmap_threshold",
        GLUtil.SAFE_TEXTURE_LIMIT,
    )

    val alwaysDecodeLongStripWithSSIV: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_always_decode_long_strip_with_ssiv",
        false,
    )

    val localChapterCoversEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_local_chapter_covers_enabled",
        true,
    )

    val localChapterCoverGridEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_local_chapter_cover_grid_enabled",
        true,
    )

    val installationId: Preference<String> = preferenceStore.getString(Preference.appStateKey("installation_id"), "")

    val donationCampaignShown: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("donation_campaign_shown"),
        false,
    )

    /** Chapters flagged by the user (duplicate series marked for later cleanup). */
    val markedChapters: Preference<String> = preferenceStore.getString("marked_chapters", "")

    /** Chapters the user keeps as good doujins. */
    val goodDoujins: Preference<String> = preferenceStore.getString("good_doujins", "")

    /** Recent random manga and chapter selections shared by every random entry point. */
    val recentlySkippedManga: Preference<String> = preferenceStore.getString("recently_skipped_manga", "")

    /** Play history of the audio module, stored as a JSON list. */
    val audioHistory: Preference<String> = preferenceStore.getString("audio_history", "")

    /** Audio "待播列表" (play queue) for quick background playback, stored as a JSON list. */
    val audioPlaylist: Preference<String> = preferenceStore.getString("audio_favorite_list", "")

    /** Locally collected audio works, stored independently from the track play queue. */
    val audioFavorites: Preference<String> = preferenceStore.getString("audio_favorite_works", "")

    /** JWT token from the audio backend login. */
    val audioAuthToken: Preference<String> = preferenceStore.getString("audio_auth_token", "")

    /** Preferred audio stream quality for the ASMR backend. */
    val audioQuality: Preference<String> = preferenceStore.getString("audio_quality", "fluent_first")

    /** Logged-in username of the audio backend ("" = not logged in). */
    val audioUsername: Preference<String> = preferenceStore.getString("audio_username", "")

    /** Anonymous recommender UUID used for personalized recommendations. */
    val audioRecommenderUuid: Preference<String> = preferenceStore.getString("audio_recommender_uuid", "")

    /** Base-directory mtime of the last local-source chapter rescan, used to detect disk changes. */
    val localSourceSyncMtime: Preference<Long> = preferenceStore.getLong("local_source_sync_mtime", 0L)

    /** Stable top-level manga directory signature committed after a successful local refresh. */
    val localSourceDirectorySignature: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("local_source_directory_signature"),
        "",
    )

    /** Last selected first-level destination in the local-first build. */
    val localFirstLastTab: Preference<String> = preferenceStore.getString("local_first_last_tab", "local")
}
