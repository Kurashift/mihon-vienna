package eu.kanade.tachiyomi.data.audio

/**
 * Loading state of the transcript attached to the track currently held by the player.
 *
 * It lives next to the other audio data types because the player controller owns subtitle
 * loading: the transcript has the same lifetime as the track, and the floating subtitle window
 * needs it even while the player screen is not on screen.
 */
enum class AudioSubtitleState {
    /** The track has no subtitle file at all. */
    NOT_AVAILABLE,

    /** Subtitle download/parsing is in flight. */
    LOADING,

    /** Lines are available and ready to display. */
    READY,

    /** The subtitle was fetched but yielded no usable lines. */
    EMPTY,

    /** Downloading or parsing failed; retrying is offered to the user. */
    ERROR,
}
