package eu.kanade.presentation.components

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Travel away from the bar that halves fine-seek speed.
 */
private val FINE_SEEK_LIFT = 48.dp

/** Slowest fine-seek gain, so lifting far away still scrubs instead of freezing. */
internal const val MIN_FINE_SEEK_GAIN = 0.08f

/**
 * Travel away from the bar, in dp, before fine seeking engages.
 *
 * A bare `lift > 0` test flickers: horizontal drags always wobble a pixel or two vertically, so
 * the flag toggled every frame and the thumb visibly pulsed between its two sizes. Engaging only
 * past a real lift, and disengaging only below half of it, gives the state enough hysteresis to
 * hold still.
 */
private const val FINE_SEEK_ENGAGE_DP = 12f

/**
 * Travel along the bar, in dp, before a press counts as a drag rather than a tap.
 *
 * Only one gesture lives on a seek bar, so the first movement has to pick which it is: a press
 * that never travels this far is still a tap and jumps to the touched position, while crossing it
 * starts a relative drag.
 */
private const val SEEK_DRAG_THRESHOLD_DP = 4f

/**
 * Scrub gain for a finger lifted [liftPx] clear of the bar.
 *
 * Full speed on the bar itself, falling off as the finger rises so a long chapter or track can be
 * adjusted precisely, and floored at [MIN_FINE_SEEK_GAIN] so lifting far away still moves the
 * position instead of freezing the drag.
 */
internal fun fineSeekGain(liftPx: Float, rampPx: Float): Float {
    if (liftPx <= 0f || rampPx <= 0f) return 1f
    return (1f / (1f + liftPx / rampPx)).coerceIn(MIN_FINE_SEEK_GAIN, 1f)
}

/**
 * Whether fine seeking should be engaged for a finger lifted [liftPx], given whether it is already
 * engaged.
 *
 * The band between the release threshold and the engage threshold is what stops the state from
 * flickering. A bare `lift > 0` test toggled on every frame of an ordinary horizontal drag, because
 * a finger always wobbles a pixel or two vertically; each toggle resized the thumb, which is what
 * made it pulse.
 */
internal fun fineSeekEngaged(alreadyEngaged: Boolean, liftPx: Float, engagePx: Float): Boolean {
    if (engagePx <= 0f) return liftPx > 0f
    return if (alreadyEngaged) liftPx > engagePx / 2f else liftPx > engagePx
}

/**
 * Fraction a drag of [dxPx] moves to from [fromFraction].
 *
 * Relative to where the thumb already sits rather than to the touch position: a bar is far
 * narrower than a thousand-page chapter, so mapping the touch point straight to a position made a
 * single imprecise press skip hundreds of pages.
 */
internal fun advanceFraction(fromFraction: Float, dxPx: Float, widthPx: Float, gain: Float): Float {
    if (widthPx <= 0f) return fromFraction.coerceIn(0f, 1f)
    return (fromFraction + dxPx / widthPx * gain).coerceIn(0f, 1f)
}

/** Fraction of the track a press at [xPx] selects, clamped to the track. */
internal fun tapFraction(xPx: Float, widthPx: Float): Float {
    if (widthPx <= 0f) return 0f
    return (xPx / widthPx).coerceIn(0f, 1f)
}

internal enum class SeekDirection {
    Horizontal,
    LeftRail,
    RightRail,
    ;

    fun position(point: Offset): Float = if (this == Horizontal) point.x else point.y

    fun lift(down: Offset, point: Offset): Float = when (this) {
        Horizontal -> down.y - point.y
        LeftRail -> point.x - down.x
        RightRail -> down.x - point.x
    }.coerceAtLeast(0f)
}

/**
 * The seek gesture shared by the reader's page bar and the audio progress bar.
 *
 * One press can mean two things, and only how far it travels tells them apart:
 *
 * - **Tap** (no travel past the threshold) jumps to the touched position. On a long chapter that is
 *   how a big jump is made in one move, and it is why the tap is resolved on release — pressing
 *   down never seeks by itself, so a stray touch cannot skip pages.
 * - **Drag** moves the thumb by the finger's travel, starting from wherever the thumb already is,
 *   so a press anywhere on the track grabs the thumb instead of jumping to the finger. Lifting the
 *   finger clear of the bar slows the travel down for fine adjustment.
 *
 * [valueAt] quantises a fraction into the caller's unit and [onValue] receives only changes, so a
 * drag does not report the same page or millisecond twice. [onFinished] runs on release, or on
 * cancellation after a value was reported if [finishOnCancel] is enabled.
 *
 * [onDragFraction] reports the gesture's own position while a drag is in flight, and null when it
 * ends. A caller that draws a thumb should follow this rather than the value it reported: acting on
 * a seek is asynchronous, so a long chapter can report a page, have the bar redraw from a stale
 * value, and fight the finger for the rest of the drag.
 *
 * Callbacks are captured when [direction] changes or the bar is first composed. Restarting the
 * gesture on each new callback would cancel a press in
 * progress. A callback that closes over a value read during composition therefore keeps that first
 * value for the life of the bar. Pass a lambda that reads the current state instead —
 * `{ latestOnChange(it) }` against a `rememberUpdatedState`, not `latestOnChange` — or the bar will
 * keep acting on numbers from the composition that created it.
 */
internal fun <T> Modifier.seekBarGestures(
    thumbFraction: () -> Float,
    valueAt: (Float) -> T,
    onValue: (T) -> Unit,
    onFinished: () -> Unit,
    enabled: () -> Boolean = { true },
    onFineSeekChange: (Boolean) -> Unit = {},
    onDragFraction: (Float?) -> Unit = {},
    direction: SeekDirection = SeekDirection.Horizontal,
    finishOnCancel: Boolean = false,
): Modifier = pointerInput(direction) {
    val fineRampPx = FINE_SEEK_LIFT.toPx()
    val fineEngagePx = FINE_SEEK_ENGAGE_DP.dp.toPx()
    val dragThresholdPx = SEEK_DRAG_THRESHOLD_DP.dp.toPx()

    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            // A disabled bar leaves the press unclaimed so a parent can still handle it, rather
            // than swallowing a gesture the bar is in no position to act on.
            if (!enabled()) continue
            // Claim the gesture: an unconsumed press would fall through to whatever handles taps
            // behind the bar, and an unconsumed drag would be stolen by a parent that pans.
            down.consume()

            val lengthPx = (if (direction == SeekDirection.Horizontal) size.width else size.height)
                .toFloat().coerceAtLeast(1f)
            val downPosition = direction.position(down.position)

            // Seed the drag from the thumb, not the touch point.
            var fraction = thumbFraction().coerceIn(0f, 1f)
            var dragging = false
            var lastPosition = downPosition
            var lastReported: T? = null
            var fineSeekOn = false
            var finished = false

            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        if (!dragging) onValue(valueAt(tapFraction(downPosition, lengthPx)))
                        finished = true
                        break
                    }

                    val position = direction.position(change.position)
                    if (!dragging && abs(position - downPosition) > dragThresholdPx) {
                        dragging = true
                        lastPosition = position
                        change.consume()
                        continue
                    }
                    if (!dragging) continue

                    val delta = position - lastPosition
                    lastPosition = position
                    change.consume()

                    val lift = direction.lift(down.position, change.position)
                    val fine = fineSeekEngaged(alreadyEngaged = fineSeekOn, liftPx = lift, engagePx = fineEngagePx)
                    if (fine != fineSeekOn) {
                        fineSeekOn = fine
                        onFineSeekChange(fine)
                    }

                    if (delta != 0f) {
                        fraction = advanceFraction(fraction, delta, lengthPx, fineSeekGain(lift, fineRampPx))
                        onDragFraction(fraction)
                        val value = valueAt(fraction)
                        if (value != lastReported) {
                            lastReported = value
                            onValue(value)
                        }
                    }
                }
            } finally {
                if (fineSeekOn) onFineSeekChange(false)
                onDragFraction(null)
                // The reader can move the bar while a finger is still down. Settle pages already
                // shown in the viewer without committing an interrupted audio seek.
                if (finished || (finishOnCancel && lastReported != null)) onFinished()
            }
        }
    }
}
