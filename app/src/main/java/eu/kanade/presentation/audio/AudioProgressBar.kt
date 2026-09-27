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
 * The slim progress line, drawn as a continuous track with a ringed dot on it.
 *
 * The player page and the floating reader bar both draw through this, so the two cannot drift apart
 * the way they had: the player page used to draw a stock Material slider (a 16dp pill under a
 * 4×44dp vertical bar, where the handle does not read as a position at all) while the reader bar
 * drew this line.
 *
 * The volume control deliberately does *not* share this drawing — see [AudioVolumeBar].
 *
 * [onSeek] is called with a drag in flight, so the caller's own drawing can follow the finger
 * instead of the value it reported: acting on a seek is asynchronous, and a bar that redraws from a
 * stale value fights the finger for the rest of the drag.
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
            )
        }
    }
}

/**
 * The volume control: a row of discrete segments rather than the progress bar's continuous line.
 *
 * The two are deliberately different shapes, because they answer different questions. Progress is
 * *where in time* the track is — one continuous quantity, drawn as one continuous line. Volume is
 * *how many of a fixed set of levels* are set — a count, drawn as countable steps. When both were
 * the same line with a dot on it, the two controls on the same screen were indistinguishable, and
 * a glance could not tell which one the dot belonged to.
 *
 * Segments also say something the line could not: the count of filled steps *is* the volume, so it
 * can be read without knowing the maximum. Drawn with `steps = maximum`, so the boundary a drag
 * snaps to is the boundary that is visible.
 */
@Composable
internal fun AudioVolumeBar(
    value: Int,
    max: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValue: (Int) -> Unit = {},
    onValueFinished: () -> Unit = {},
) {
    val maximum = max.coerceAtLeast(1)
    val level = value.coerceIn(0, maximum)
    val progress = level.toFloat() / maximum.toFloat()
    val currentMaximum by rememberUpdatedState(maximum)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentProgress by rememberUpdatedState(progress)
    val currentOnValue by rememberUpdatedState(onValue)
    val currentOnValueFinished by rememberUpdatedState(onValueFinished)

    val filledColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.onSurface.copy(alpha = TRACK_ALPHA)
    val color = if (enabled) filledColor else filledColor.copy(alpha = DISABLED_ALPHA)

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
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().align(Alignment.CenterStart)) {
            drawSegments(filled = level, total = maximum, filledColor = color, emptyColor = emptyColor)
        }
    }
}

/**
 * Draws [total] segments across the width, the first [filled] of them in [filledColor].
 *
 * Every segment is drawn, including the unfilled ones: the row of empty slots is what tells the
 * user how many levels there are to move through, and it is why the filled count is readable on its
 * own. Segment width shrinks with the count, but the gap between them is fixed, so a segment never
 * becomes narrower than the gap separating it.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSegments(
    filled: Int,
    total: Int,
    filledColor: Color,
    emptyColor: Color,
) {
    if (total <= 0) return
    val segmentHeight = SEGMENT_HEIGHT.toPx()
    val geometry = segmentGeometry(width = size.width, total = total, gap = SEGMENT_GAP.toPx())
    val y = size.height / 2f
    val corner = CornerRadius(segmentHeight / 2f)

    for (index in 0 until total) {
        drawRoundRect(
            color = if (index < filled) filledColor else emptyColor,
            topLeft = Offset(index * geometry.slotWidth, y - segmentHeight / 2f),
            size = Size(geometry.segmentWidth, segmentHeight),
            cornerRadius = corner,
        )
    }
}

/** Where one segment sits and how wide it is, for a row of [total] segments across [width]. */
internal data class SegmentGeometry(val slotWidth: Float, val segmentWidth: Float)

/**
 * Works out the segment row's geometry.
 *
 * The gap is capped at a third of what each slot can afford: the step count comes from the platform
 * and is not ours to assume, and a device reporting a very high count would otherwise let the gaps
 * consume the whole width and leave nothing to draw. [segmentWidth] keeps a floor of 1px so a
 * segment can never vanish or invert.
 */
internal fun segmentGeometry(width: Float, total: Int, gap: Float): SegmentGeometry {
    if (total <= 0 || width <= 0f) return SegmentGeometry(0f, 0f)
    val slot = width / total
    val actualGap = gap.coerceAtMost(slot / 3f)
    return SegmentGeometry(slotWidth = slot, segmentWidth = (slot - actualGap).coerceAtLeast(1f))
}

/**
 * Draws the progress track, its buffered segment and the thumb.
 *
 * The thumb is only drawn once the bar has a value to point at: at zero there is nothing played to
 * aim at, and a dot parked on the very start of an empty track reads as a rendering artifact.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrack(
    fraction: Float,
    bufferedFraction: Float,
    trackColor: Color,
    bufferedColor: Color,
    progressColor: Color,
    ringColor: Color,
    thumbRadius: Dp,
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

    if (bufferedFraction > fraction) {
        drawRoundRect(
            color = bufferedColor,
            topLeft = Offset(size.width * fraction, y - trackHeight / 2f),
            size = Size(size.width * (bufferedFraction - fraction), trackHeight),
            cornerRadius = corner,
        )
    }

    if (fraction <= 0f) return

    drawRoundRect(
        color = progressColor,
        topLeft = Offset(0f, y - trackHeight / 2f),
        size = Size(size.width * fraction, trackHeight),
        cornerRadius = corner,
    )

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

/**
 * Volume segments are the same weight as the progress track, only broken into steps.
 *
 * Deliberately not taller: a row of thick bars reads as a row of blocks rather than as a control,
 * and what separates the two controls is the *pattern* — dashes against a solid line — not the
 * amount of ink. Matching the track's height also keeps them feeling like one family.
 *
 * The gap is close to the segment's own height so the breaks stay legible at 4dp; a tighter gap
 * reads as a solid line that happens to be rendering badly.
 */
private val SEGMENT_HEIGHT = TRACK_HEIGHT
private val SEGMENT_GAP = 3.dp
