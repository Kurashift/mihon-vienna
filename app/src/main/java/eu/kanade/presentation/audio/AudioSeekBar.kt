package eu.kanade.presentation.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.audio.AudioPlayerController

/**
 * The progress bar of the floating reader bar, with the elapsed and total times underneath.
 *
 * The bar itself is [AudioProgressBar], which is also what the player page draws — this only adds
 * the two labels and the local drag preview the gesture needs.
 */
@Composable
fun AudioSeekBar(
    controller: AudioPlayerController,
    modifier: Modifier = Modifier,
    showTimes: Boolean = true,
) {
    val state = controller.state
    val duration = state.durationMs.coerceAtLeast(0)
    var dragPosition by remember(state.item?.mediaStreamUrl) { mutableStateOf<Long?>(null) }
    val position = (dragPosition ?: state.positionMs).coerceIn(0, duration)

    Column(modifier = modifier) {
        AudioProgressBar(
            positionMs = position,
            durationMs = duration,
            bufferedMs = state.bufferedPositionMs,
            // Buffering deliberately does not dim this bar: a seek always round-trips through
            // STATE_BUFFERING, so tying the colours to it made every jump flicker.
            enabled = duration > 0,
            onSeek = { dragPosition = it.coerceIn(0, duration) },
            onSeekFinished = {
                dragPosition?.let(controller::seekTo)
                dragPosition = null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (showTimes) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Tabular figures: the digits here change every half second, and proportional
                // digits make the whole label twitch as 1s swap in for 8s.
                Text(
                    text = formatDuration(position),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR_FIGURES),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR_FIGURES),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** OpenType feature tag for tabular (equal-width) numerals. */
internal const val TABULAR_FIGURES = "tnum"
