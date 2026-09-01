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
        false,
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

    /** Audio play queue for quick background playback, stored as a JSON list. */
    val audioPlaylist: Preference<String> = preferenceStore.getString("audio_favorite_list", "")

    /** Schema version of [audioPlaylist]; bump to discard old playlist entries once. */
    val audioPlaylistVersion: Preference<Int> = preferenceStore.getInt("audio_playlist_version", 0)

    /** Locally collected audio works, stored independently from the track play queue. */
    val audioFavorites: Preference<String> = preferenceStore.getString("audio_favorite_works", "")

    /** JWT token from the audio backend login. */
    val audioAuthToken: Preference<String> = preferenceStore.getString("audio_auth_token", "")

    /** Preferred audio stream quality for the ASMR backend. */
    val audioQuality: Preference<String> = preferenceStore.getString("audio_quality", "fluent_first")

    /** How much of the audio player screen the subtitle list takes, see AudioSubtitleDisplayMode. */
    val audioSubtitleDisplayMode: Preference<String> = preferenceStore.getString(
        "audio_subtitle_display_mode",
        "standard",
    )

    /**
     * Whether subtitles are mirrored into a window drawn on top of other apps. Off by default:
     * turning it on is what sends the user to the system "draw over other apps" screen.
     */
    val audioFloatingSubtitle: Preference<Boolean> = preferenceStore.getBoolean(
        "audio_floating_subtitle",
        false,
    )

    /**
     * Whether the floating subtitle window ignores touches. A locked window lets them through to
     * whatever runs underneath, leaving only the lock button itself tappable.
     */
    val audioFloatingSubtitleLocked: Preference<Boolean> = preferenceStore.getBoolean(
        "audio_floating_subtitle_locked",
        false,
    )

    /** Where the floating subtitle window was last dragged, [UNSET_POSITION] until the first drag. */
    val audioFloatingSubtitleX: Preference<Int> = preferenceStore.getInt(
        "audio_floating_subtitle_x",
        UNSET_POSITION,
    )

    val audioFloatingSubtitleY: Preference<Int> = preferenceStore.getInt(
        "audio_floating_subtitle_y",
        UNSET_POSITION,
    )

    companion object {
        /**
         * Marks a window position that was never dragged. A real coordinate can never be this low,
         * which is what lets "unset" be told apart from "dragged to the top left corner".
         */
        const val UNSET_POSITION = Int.MIN_VALUE
    }

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
