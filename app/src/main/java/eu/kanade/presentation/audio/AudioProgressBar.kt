package eu.kanade.presentation.audio

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.seekBarGestures
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import kotlin.math.roundToInt

/**
 * The slim bar both the progress and the volume control are drawn from.
 *
 * One drawing routine serves both because they are the same object to the eye — a thin track with a
 * ringed dot on it — and the two used to disagree: the player page drew a stock Material slider
 * (a 16dp pill under a 4×44dp vertical bar, where the handle does not read as a position at all),
 * while the floating reader bar drew this 4dp line. Sharing the routine is what keeps them from
 * drifting apart again.
 *
 * [onFraction] reports a drag in flight and null when it ends, so the caller's own drawing can
 * follow the finger instead of the value it reported — acting on a seek is asynchronous, and a bar
 * that redraws from a stale value fights the finger for the rest of the drag.
 */
@Composable
internal fun AudioProgressBar(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    bufferedMs: Long = 0L,
    enabled: Boolean = true,
    /** Background the thumb's ring is cut out of; must match what sits behind the bar. */
    ringColor: Color = MaterialTheme.colorScheme.surface,
    onSeek: (Long) -> Unit = {},
    onSeekFinished: () -> Unit = {},
) {
    val progress = progressFraction(positionMs, durationMs)
    val buffered = bufferedFraction(positionMs, bufferedMs, durationMs)
    var dragging by remember { mutableStateOf(false) }
    // The gesture reads these rather than using them as pointerInput keys: restarting the coroutine
    // mid-press would cancel it before onSeekFinished runs, which is how the seek used to get
    // dropped. It is also what lets the preview position update under a running drag.
    val currentDuration by rememberUpdatedState(durationMs)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentProgress by rememberUpdatedState(progress)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnSeekFinished by rememberUpdatedState(onSeekFinished)

    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = TRACK_ALPHA)
    val bufferedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = BUFFERED_ALPHA)
    val progressColor = MaterialTheme.colorScheme.primary
    val color = if (enabled) progressColor else progressColor.copy(alpha = DISABLED_ALPHA)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = progress,
                    range = 0f..1f,
                    steps = 0,
                )
                // Restores what the stock Slider this replaced used to provide: without an action,
                // the bar only announces its value and a screen reader cannot change it.
                if (enabled && durationMs > 0) {
                    setProgress { target ->
                        onSeek((target.coerceIn(0f, 1f) * durationMs).toLong())
                        onSeekFinished()
                        true
                    }
                }
            }
            .seekBarGestures(
                enabled = { currentEnabled && currentDuration > 0 },
                thumbFraction = { currentProgress },
                valueAt = { fraction -> (fraction * currentDuration).toLong() },
                // Drag reports the preview position; the caller only forwards it to the player when
                // the gesture ends, so scrubbing a long track does not issue a seek per frame.
                onValue = currentOnSeek,
                onFinished = currentOnSeekFinished,
                onDragFraction = { dragging = it != null },
            ),
    ) {
        // Grows while the finger is down: the dot alone is small enough that a drag in progress is
        // otherwise hard to tell from the track simply moving on its own.
        val thumbRadius by animateDpAsState(
            targetValue = if (dragging) THUMB_RADIUS_DRAGGING else THUMB_RADIUS,
            label = "audioThumbRadius",
        )
        Canvas(modifier = Modifier.fillMaxWidth().align(Alignment.CenterStart)) {
            drawTrack(
                fraction = progress,
                bufferedFraction = buffered,
                trackColor = trackColor,
                bufferedColor = bufferedColor,
                progressColor = color,
                ringColor = ringColor,
                thumbRadius = thumbRadius,
                showThumbAtZero = false,
            )
        }
    }
}

/**
 * The volume control's bar: the same track, without a buffered segment and without a drag preview.
 *
 * Kept separate from [AudioProgressBar] rather than folded into it with a flag, because the two
 * report in different units — milliseconds the player resolves asynchronously, versus a step index
 * the system applies immediately — and only the progress bar has anything to preview.
 */
@Composable
internal fun AudioVolumeBar(
    value: Int,
    max: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    ringColor: Color = MaterialTheme.colorScheme.surface,
    onValue: (Int) -> Unit = {},
    onValueFinished: () -> Unit = {},
) {
    val maximum = max.coerceAtLeast(1)
    val progress = (value.toFloat() / maximum.toFloat()).coerceIn(0f, 1f)
    var dragging by remember { mutableStateOf(false) }
    val currentMaximum by rememberUpdatedState(maximum)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentProgress by rememberUpdatedState(progress)
    val currentOnValue by rememberUpdatedState(onValue)
    val currentOnValueFinished by rememberUpdatedState(onValueFinished)

    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = TRACK_ALPHA)
    val progressColor = MaterialTheme.colorScheme.primary
    val color = if (enabled) progressColor else progressColor.copy(alpha = DISABLED_ALPHA)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = progress,
                    range = 0f..1f,
                    steps = (maximum - 1).coerceAtLeast(0),
                )
                // Same reason as the progress bar: the stock Slider this replaced could be adjusted
                // by a screen reader, and dropping that would be a regression.
                if (enabled) {
                    setProgress { target ->
                        onValue((target.coerceIn(0f, 1f) * maximum).roundToInt().coerceIn(0, maximum))
                        onValueFinished()
                        true
                    }
                }
            }
            .seekBarGestures(
                enabled = { currentEnabled },
                thumbFraction = { currentProgress },
                // Quantised to the system's own steps, so the drag lands on a real volume level
                // rather than a fraction the platform would have to round anyway.
                valueAt = { fraction -> (fraction * currentMaximum).roundToInt().coerceIn(0, currentMaximum) },
                onValue = currentOnValue,
                onFinished = currentOnValueFinished,
                onDragFraction = { dragging = it != null },
            ),
    ) {
        val thumbRadius by animateDpAsState(
            targetValue = if (dragging) THUMB_RADIUS_DRAGGING else THUMB_RADIUS,
            label = "audioVolumeThumbRadius",
        )
        Canvas(modifier = Modifier.fillMaxWidth().align(Alignment.CenterStart)) {
            drawTrack(
                fraction = progress,
                bufferedFraction = null,
                trackColor = trackColor,
                bufferedColor = trackColor,
                progressColor = color,
                ringColor = ringColor,
                thumbRadius = thumbRadius,
                showThumbAtZero = true,
            )
        }
    }
}

/**
 * Draws the track, its filled segments and the thumb.
 *
 * [bufferedFraction] is nullable: the volume bar has nothing buffered, and passing null keeps the
 * whole track at [trackColor] instead of painting a second layer of the same colour over it.
 *
 * The thumb is only drawn once the bar has a value to point at. At zero there is nothing to aim at,
 * and a dot parked on the very start of an empty track reads as a rendering artifact.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrack(
    fraction: Float,
    bufferedFraction: Float?,
    trackColor: Color,
    bufferedColor: Color,
    progressColor: Color,
    ringColor: Color,
    thumbRadius: Dp,
    showThumbAtZero: Boolean,
) {
    val trackHeight = TRACK_HEIGHT.toPx()
    val y = size.height / 2f
    val corner = CornerRadius(trackHeight / 2f)

    drawRoundRect(
        color = trackColor,
        topLeft = Offset(0f, y - trackHeight / 2f),
        size = Size(size.width, trackHeight),
        cornerRadius = corner,
    )

    bufferedFraction?.takeIf { it > fraction }?.let { buffered ->
        drawRoundRect(
            color = bufferedColor,
            topLeft = Offset(size.width * fraction, y - trackHeight / 2f),
            size = Size(size.width * (buffered - fraction), trackHeight),
            cornerRadius = corner,
        )
    }

    // At zero there is nothing played to point at, and a dot parked on the very start of an empty
    // track reads as a rendering artifact — so the progress bar hides it. The volume bar must not:
    // there, zero is a setting the user chose, and a control with no visible handle at all looks
    // broken rather than set to mute.
    if (fraction <= 0f && !showThumbAtZero) return

    if (fraction > 0f) {
        drawRoundRect(
            color = progressColor,
            topLeft = Offset(0f, y - trackHeight / 2f),
            size = Size(size.width * fraction, trackHeight),
            cornerRadius = corner,
        )
    }

    // The ring is not decoration: the dot sits on a line the same width as itself, so without a
    // cut-out behind it the exact position is guesswork. Painting it in the background colour is
    // what makes the thumb read as sitting on top of the track rather than being part of it.
    val center = Offset(size.width * fraction, y)
    drawCircle(color = ringColor, radius = thumbRadius.toPx() + RING_WIDTH.toPx(), center = center)
    drawCircle(color = progressColor, radius = thumbRadius.toPx(), center = center)
}

/**
 * How far along the track the played portion reaches, as a 0..1 fraction.
 *
 * An unknown duration reports 0 rather than a full bar, and the result is clamped so a position
 * reported past the end (which the player does while a seek is in flight) cannot overdraw.
 */
internal fun progressFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * How far the buffered portion reaches, never drawn behind the played portion.
 *
 * The player publishes the two from separate reads of the same player, so a position can briefly
 * report ahead of the buffer that covers it. Drawing that verbatim makes the buffered segment
 * disappear for a frame and flicker, so the played portion is the floor.
 */
internal fun bufferedFraction(positionMs: Long, bufferedMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    val buffered = (bufferedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    return buffered.coerceAtLeast(progressFraction(positionMs, durationMs))
}

private const val TRACK_ALPHA = 0.24f
private const val BUFFERED_ALPHA = 0.40f
private val TRACK_HEIGHT = 4.dp

/** Height of the gesture area, which is deliberately taller than the track it draws. */
private val BAR_HEIGHT = 28.dp
private val THUMB_RADIUS = 5.dp
private val THUMB_RADIUS_DRAGGING = 6.dp
private val RING_WIDTH = 1.5.dp
