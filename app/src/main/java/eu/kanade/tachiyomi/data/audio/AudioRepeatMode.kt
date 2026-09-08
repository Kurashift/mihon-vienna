package eu.kanade.tachiyomi.data.audio

/**
 * What the player does once a track runs out.
 *
 * Three modes rather than a single on/off switch, because "off" and "repeat the whole queue" are
 * different things: off stops at the end of the queue, which is what playing an album in order
 * means, while repeating the queue is a mode of its own.
 */
enum class AudioRepeatMode {
    /** Plays the queue in order and stops at the end of it. */
    OFF,

    /** Starts the queue over once the last track has finished. */
    ALL,

    /** Replays the track that just finished. */
    ONE,
    ;

    fun next(): AudioRepeatMode = when (this) {
        OFF -> ONE
        ONE -> ALL
        ALL -> OFF
    }
}
