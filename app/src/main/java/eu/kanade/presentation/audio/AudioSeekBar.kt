package eu.kanade.presentation.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.audio.AudioPlayerController

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
        AudioSlimProgress(
            positionMs = position,
            durationMs = duration,
            // Buffering deliberately does not dim this bar: a seek always round-trips through
            // STATE_BUFFERING, so tying the colours to it made every jump flicker.
            seekEnabled = duration > 0,
            onSeek = { dragPosition = it.coerceIn(0, duration) },
            onSeekFinished = {
                dragPosition?.let(controller::seekTo)
                dragPosition = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
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

@Composable
private fun AudioSlimProgress(
    positionMs: Long,
    durationMs: Long,
    seekEnabled: Boolean,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progressColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
    // Read the live values from inside the pointer-input coroutine instead of using them as
    // pointerInput keys: restarting the coroutine mid-drag would cancel the gesture before
    // onSeekFinished runs, which is exactly how the seek used to get dropped.
    val currentDuration by rememberUpdatedState(durationMs)
    val currentSeekEnabled by rememberUpdatedState(seekEnabled)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnSeekFinished by rememberUpdatedState(onSeekFinished)

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                fun seekAt(px: Float) {
                    val widthPx = size.width.toFloat()
                    if (widthPx <= 0f) return
                    val fraction = (px / widthPx).coerceIn(0f, 1f)
                    currentOnSeek((fraction * currentDuration).toLong())
                }
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        if (!currentSeekEnabled || currentDuration <= 0) continue
                        // Claim the gesture, otherwise the parent handler that drags the whole
                        // floating bar around steals it.
                        down.consume()
                        seekAt(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            seekAt(change.position.x)
                        }
                        currentOnSeekFinished()
                    }
                }
            },
    ) {
        val fraction = if (durationMs > 0) {
            positionMs.toFloat() / durationMs.toFloat()
        } else {
            0f
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterStart),
        ) {
            val trackHeight = 4.dp.toPx()
            val y = size.height / 2f
            val corner = CornerRadius(trackHeight / 2f)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, y - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = corner,
            )
            if (fraction > 0f) {
                drawRoundRect(
                    color = progressColor,
                    topLeft = Offset(0f, y - trackHeight / 2f),
                    size = Size(size.width * fraction, trackHeight),
                    cornerRadius = corner,
                )
                drawCircle(
                    color = progressColor,
                    radius = 4.5.dp.toPx(),
                    center = Offset(size.width * fraction, y),
                )
            }
        }
    }
}

/** OpenType feature tag for tabular (equal-width) numerals. */
private const val TABULAR_FIGURES = "tnum"
