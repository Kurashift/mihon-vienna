package eu.kanade.presentation.audio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.audio.AudioPlayerController
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

@Composable
fun AudioReaderFloatingBar(
    compact: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onOpenPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = remember { Injekt.get<AudioPlayerController>() }
    val state = controller.state
    if (state.item == null) return

    if (compact) {
        AudioReaderMiniControl(
            controller = controller,
            onDismiss = onDismiss,
            modifier = modifier,
        )
        return
    }

    var volumeVisible by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        AnimatedVisibility(visible = volumeVisible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                AudioVerticalVolumeControl(
                    controller = controller,
                    modifier = Modifier.padding(end = 52.dp),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
        ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = controller::togglePlay,
                    modifier = Modifier.size(38.dp),
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = stringResource(
                                if (state.isPlaying) MR.strings.action_pause else MR.strings.action_play,
                            ),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                IconButton(
                    onClick = controller::previous,
                    enabled = state.hasPrevious || state.positionMs > 0,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SkipPrevious,
                        contentDescription = stringResource(MR.strings.audio_previous_track),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = controller::next,
                    enabled = state.hasNext,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SkipNext,
                        contentDescription = stringResource(MR.strings.audio_next_track),
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onOpenPlaylist)
                        .padding(horizontal = 6.dp),
                ) {
                    Text(
                        text = state.item.trackTitle,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.item.workTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = { volumeVisible = !volumeVisible },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = stringResource(MR.strings.audio_volume_up),
                        tint = if (volumeVisible) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onOpenPlaylist,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.QueueMusic,
                        contentDescription = stringResource(MR.strings.audio_quick_open),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(MR.strings.action_close),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            AudioSeekBar(
                controller = controller,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        }
    }
}

@Composable
private fun AudioReaderMiniControl(
    controller: AudioPlayerController,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = controller.state
    var volumeVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
    ) {
        AnimatedVisibility(visible = volumeVisible) {
            AudioVerticalVolumeControl(
                controller = controller,
                modifier = Modifier.padding(start = 40.dp),
            )
        }

        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = controller::togglePlay,
                    modifier = Modifier.size(36.dp),
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(17.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { volumeVisible = !volumeVisible },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = stringResource(MR.strings.audio_volume_up),
                        tint = if (volumeVisible) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(19.dp),
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(MR.strings.action_close),
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioVerticalVolumeControl(
    controller: AudioPlayerController,
    modifier: Modifier = Modifier,
) {
    val state = controller.state
    val maximum = state.maxMediaVolume.coerceAtLeast(1)
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val volume = (dragValue ?: state.mediaVolume.toFloat()).coerceIn(0f, maximum.toFloat())

    Surface(
        modifier = modifier.size(width = 32.dp, height = 140.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = stringResource(MR.strings.audio_volume_up),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(maximum, state.isMediaVolumeFixed) {
                        if (state.isMediaVolumeFixed) return@pointerInput
                        val heightPx = size.height.toFloat()
                        fun seekAt(py: Float) {
                            // Bottom = 0, top = max. Keep the small volume at the bottom.
                            val fraction = (1f - (py / heightPx).coerceIn(0f, 1f))
                            val target = (fraction * maximum).roundToInt().coerceIn(0, maximum)
                            dragValue = target.toFloat()
                            if (target != state.mediaVolume) {
                                controller.setMediaVolume(target)
                            }
                        }
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown()
                                seekAt(down.position.y)
                                var released = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        released = true
                                        break
                                    }
                                    seekAt(change.position.y)
                                }
                                if (released) {
                                    dragValue = null
                                    controller.refreshSystemVolume()
                                }
                            }
                        }
                    },
            ) {
                val fraction = volume / maximum
                val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                val progressColor = if (state.isMediaVolumeFixed) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.primary
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center),
                ) {
                    val trackWidth = 5.dp.toPx()
                    val x = size.width / 2f
                    val bottom = size.height
                    val thumbY = size.height * (1f - fraction)
                    val corner = CornerRadius(trackWidth / 2f)

                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(x - trackWidth / 2f, 0f),
                        size = Size(trackWidth, size.height),
                        cornerRadius = corner,
                    )
                    if (fraction > 0f) {
                        drawRoundRect(
                            color = progressColor,
                            topLeft = Offset(x - trackWidth / 2f, thumbY),
                            size = Size(trackWidth, bottom - thumbY),
                            cornerRadius = corner,
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 7.dp.toPx(),
                            center = Offset(x, thumbY),
                        )
                        drawCircle(
                            color = progressColor,
                            radius = 5.dp.toPx(),
                            center = Offset(x, thumbY),
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Outlined.VolumeDown,
                contentDescription = stringResource(MR.strings.audio_volume_down),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
