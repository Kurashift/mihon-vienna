package eu.kanade.tachiyomi.data.audio

/**
 * How much of the player screen the subtitle (lyrics) panel is allowed to take.
 *
 * The player stacks cover art, titles, the subtitle list and transport controls in one column, so
 * the subtitle list only ever gets the leftover height. Since the list is the thing being read
 * along with the audio, the modes trade cover art and line density for subtitle height.
 */
enum class AudioSubtitleDisplayMode(val preferenceValue: String) {
    /** Cover art and subtitles share the screen, current behaviour. */
    STANDARD("standard"),

    /** Cover art is dropped and lines are enlarged, leaving the subtitles almost the full screen. */
    IMMERSIVE("immersive"),
    ;

    fun next(): AudioSubtitleDisplayMode = when (this) {
        STANDARD -> IMMERSIVE
        IMMERSIVE -> STANDARD
    }

    companion object {
        fun fromPreference(value: String): AudioSubtitleDisplayMode = entries
            .firstOrNull { it.preferenceValue == value }
            ?: STANDARD
    }
}
