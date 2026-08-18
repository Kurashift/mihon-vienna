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
            enabled = !state.isBuffering && duration > 0,
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
                Text(
                    text = formatDuration(position),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AudioSlimProgress(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progressColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)

    Box(
        modifier = modifier
            .pointerInput(enabled, durationMs) {
                if (!enabled) return@pointerInput
                val widthPx = size.width.toFloat()
                fun seekAt(px: Float) {
                    if (durationMs <= 0) return
                    val fraction = (px / widthPx).coerceIn(0f, 1f)
                    onSeek((fraction * durationMs).toLong())
                }
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        seekAt(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            seekAt(change.position.x)
                        }
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
            val effectiveTrack = trackColor.let { if (enabled) it else it.copy(alpha = 0.5f) }
            drawRoundRect(
                color = effectiveTrack,
                topLeft = Offset(0f, y - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = corner,
            )
            if (fraction > 0f) {
                val effectiveProgress = if (enabled) progressColor else progressColor.copy(alpha = 0.5f)
                drawRoundRect(
                    color = effectiveProgress,
                    topLeft = Offset(0f, y - trackHeight / 2f),
                    size = Size(size.width * fraction, trackHeight),
                    cornerRadius = corner,
                )
                drawCircle(
                    color = effectiveProgress,
                    radius = 4.5.dp.toPx(),
                    center = Offset(size.width * fraction, y),
                )
            }
        }
    }
}
