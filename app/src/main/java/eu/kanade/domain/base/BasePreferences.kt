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

    /** Coach marks already shown once, one flag per surface, excluded from backups. */
    val coachLocalFabShown: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("coach_local_fab_shown"),
        false,
    )

    /** Chapters flagged by the user (duplicate series marked for later cleanup). */
    val markedChapters: Preference<String> = preferenceStore.getString("marked_chapters", "")

    /** Chapters the user keeps as good doujins. */
    val goodDoujins: Preference<String> = preferenceStore.getString("good_doujins", "")

    /** Recent random manga selections, shared by every random entry point. */
    val recentlySkippedManga: Preference<String> = preferenceStore.getString("recently_skipped_manga", "")

    /** Play history of the audio module, stored as a JSON list. */
    val audioHistory: Preference<String> = preferenceStore.getString("audio_history", "")

    /** Audio play queue for quick background playback, stored as a JSON list. */
    val audioPlaylist: Preference<String> = preferenceStore.getString("audio_favorite_list", "")

    /** Schema version of [audioPlaylist]; bump to discard old playlist entries once. */
    val audioPlaylistVersion: Preference<Int> = preferenceStore.getInt("audio_playlist_version", 0)

    /**
     * Queue sections the user explicitly opened or closed. Sections with no entry here follow the
     * default: the one holding the current track is open, everything else is closed.
     */
    val audioQueueExpandedSections: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("audio_queue_expanded_sections"),
        emptySet(),
    )
    val audioQueueCollapsedSections: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("audio_queue_collapsed_sections"),
        emptySet(),
    )

    /**
     * Folders inside a queued work that the user explicitly opened or closed. Folders with no
     * entry follow the same rule as works: the one holding the current track is open.
     */
    val audioQueueExpandedFolders: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("audio_queue_expanded_folders"),
        emptySet(),
    )
    val audioQueueCollapsedFolders: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("audio_queue_collapsed_folders"),
        emptySet(),
    )

    /** Locally collected audio works, stored independently from the track play queue. */
    val audioFavorites: Preference<String> = preferenceStore.getString("audio_favorite_works", "")

    /** JWT token from the audio backend login. */
    val audioAuthToken: Preference<String> = preferenceStore.getString("audio_auth_token", "")

    /**
     * API base URL of the audio backend site the user picked, empty for the default.
     *
     * Stored as the full URL rather than an index so an install that chose a host keeps it even if
     * the shipped list is reordered. The four sites share accounts and catalogue, so a switch does
     * not invalidate [audioAuthToken].
     */
    val audioBackendUrl: Preference<String> = preferenceStore.getString("audio_backend_url", "")

    /** Preferred audio stream quality for the ASMR backend. */
    val audioQuality: Preference<String> = preferenceStore.getString("audio_quality", "fluent_first")

    /**
     * Whether the work lists are narrowed to works that ship subtitles.
     *
     * A filter rather than a sort: the backend answers it on the work feeds and on search alike,
     * and changing it re-requests the list. Kept out of the recommended and popular feeds, which
     * are ordered by the backend rather than browsed.
     */
    val audioSubtitlesOnly: Preference<Boolean> = preferenceStore.getBoolean("audio_subtitles_only", false)

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

    /**
     * Where the reader's audio bar sits across the width, as a fraction of the distance it can
     * travel. A fraction rather than a pixel offset is what lets the spot survive a rotation or a
     * different screen: the travel itself changes with the reader's width, and a pixel offset
     * picked on the wide one would come back clamped to an edge on the narrow one.
     */
    val audioReaderBarOffsetFraction: Preference<Float> = preferenceStore.getFloat(
        "audio_reader_bar_offset_fraction",
        0f,
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

    /**
     * Release tag whose update prompt was answered with 忽略更新, e.g. `v2.3.5`.
     *
     * Stored as the release's own version string, so the prompt is skipped only while the latest
     * release is still that one: the next release asks again. Kept in app state rather than in the
     * backup, since it records what this device has already been told.
     */
    val ignoredUpdateVersion: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("ignored_update_version"),
        "",
    )

    /**
     * Whether the start-up prompt announces a new release.
     *
     * Turning it off silences the prompt only: the manual check in the about screen keeps working,
     * so a reader who dismissed the prompt for good is never cut off from updates. Kept in app
     * state rather than in the backup, like [ignoredUpdateVersion].
     */
    val updatePromptEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("update_prompt_enabled"),
        true,
    )
}
