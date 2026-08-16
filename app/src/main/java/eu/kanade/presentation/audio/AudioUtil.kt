package eu.kanade.presentation.audio

import eu.kanade.tachiyomi.data.audio.Work

/** Formats a millisecond duration as `M:SS` or `H:MM:SS`. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val paddedSeconds = seconds.toString().padStart(2, '0')
    return if (hours > 0) {
        val paddedMinutes = minutes.toString().padStart(2, '0')
        "$hours:$paddedMinutes:$paddedSeconds"
    } else {
        "$minutes:$paddedSeconds"
    }
}

/** Short metadata line for a work: duration, release date and rating. */
fun workMeta(work: Work): String = buildList {
    val durationMs = ((work.duration ?: 0.0) * 1000).toLong()
    if (durationMs > 0) add(formatDuration(durationMs))
    if (!work.release.isNullOrBlank()) add(work.release)
    work.rateAverage2dp?.let { add("★$it") }
}.joinToString(" · ")
