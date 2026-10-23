package eu.kanade.presentation.components

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Narrowest row a pinch can produce, matching the low end of the library's column slider. */
internal const val MIN_PINCH_COLUMNS = 1

/** Widest row a pinch can produce, matching the high end of the library's column slider. */
internal const val MAX_PINCH_COLUMNS = 10

/** Cell width the adaptive grid holds before it adds another column. */
internal val ADAPTIVE_MIN_CELL_WIDTH = 128.dp

/**
 * Space the manga grids put around their own content, per side.
 *
 * Named rather than repeated because a pinch works out how many columns the adaptive grid is
 * showing from the width left over: if this and a grid's own padding ever disagreed, the first
 * frame of a pinch would jump a column.
 */
internal val GRID_CONTENT_PADDING = 8.dp

/**
 * Distance, in columns, a pinch has to travel past an integer before the count follows it.
 *
 * Without a band a pinch held on a boundary flickers: every frame crosses the rounding line, the
 * grid relays out at the other count, and the haptics fire on each flip. Leaving the band on the
 * side the pinch came from means one crossing per step instead of a buzz at the edge.
 */
internal const val PINCH_COLUMN_DEADBAND = 0.1f

/**
 * Columns an adaptive grid fits into [availableWidthPx], for [minCellWidthPx] cells separated by
 * [spacingPx].
 *
 * Mirrors the arithmetic `GridCells.Adaptive` uses to pick its count, so a pinch that starts from
 * "auto" begins at the number of columns actually on screen instead of jumping one on the first
 * frame.
 */
internal fun adaptiveColumnCount(
    availableWidthPx: Int,
    spacingPx: Int,
    minCellWidthPx: Int,
): Int {
    if (minCellWidthPx <= 0) return 1
    if (availableWidthPx <= minCellWidthPx) return 1
    return ((availableWidthPx + spacingPx) / (minCellWidthPx + spacingPx)).coerceAtLeast(1)
}

/**
 * Columns to show for a pinch that has accumulated [zoom] since it started, given that [base]
 * columns were on screen when it began and [current] are shown now.
 *
 * Spreading the fingers ([zoom] above 1) enlarges the covers and so takes columns away; pinching
 * them together adds columns. The result never leaves [min]..[max], and a non-finite or
 * non-positive zoom leaves the count where it was: it can only come from a pointer set that just
 * changed shape, which the gesture already discards a frame later.
 */
internal fun pinchedColumnCount(
    base: Int,
    zoom: Float,
    current: Int,
    min: Int = MIN_PINCH_COLUMNS,
    max: Int = MAX_PINCH_COLUMNS,
): Int {
    if (base <= 0 || !zoom.isFinite() || zoom <= 0f) return current.coerceIn(min, max)

    val wanted = base / zoom
    val stepped = when {
        wanted > current -> wanted - PINCH_COLUMN_DEADBAND
        wanted < current -> wanted + PINCH_COLUMN_DEADBAND
        else -> wanted
    }
    return stepped.roundToInt().coerceIn(min, max)
}

/**
 * Lets a two-finger pinch resize the grid's rows, reporting the column count through [content].
 *
 * [rawColumns] is the preference as stored, where 0 means "auto" - the caller keeps that value and
 * resolves it to `GridCells.Adaptive` exactly as before. [content] therefore receives 0 while the
 * grid is automatic, and a concrete count only while a pinch is in flight or once one has been
 * committed. Committing goes through [onColumnsCommit] once, when the last finger lifts: a grid
 * that writes its preference on every frame of a pinch writes it dozens of times.
 *
 * The preview survives until the preference reports back the committed value, so the grid never
 * snaps to the old count for the frames the write is still in flight.
 *
 * @param horizontalContentInset total horizontal padding the grid spends on its own content, which
 * is not room for cells. The measured width is this much wider than what the columns divide.
 */
@Composable
fun PinchColumns(
    rawColumns: Int,
    onColumnsCommit: (Int) -> Unit,
    enabled: Boolean,
    horizontalContentInset: Dp,
    horizontalSpacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable (columns: Int) -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    var widthPx by remember { mutableIntStateOf(0) }
    val preview = remember { mutableIntStateOf(0) }

    val spacingPx = with(density) { horizontalSpacing.roundToPx() }
    val insetPx = with(density) { horizontalContentInset.roundToPx() }
    val minCellPx = with(density) { ADAPTIVE_MIN_CELL_WIDTH.roundToPx() }
    val autoColumns = remember(widthPx, spacingPx, insetPx, minCellPx) {
        adaptiveColumnCount(widthPx - insetPx, spacingPx, minCellPx)
    }

    // What is on screen right now as a real count: the preview, a fixed preference, or the number
    // of columns the adaptive grid has laid out. This is the base a pinch measures its zoom from.
    val columnsInForce = preview.intValue.takeIf { it > 0 }
        ?: rawColumns.takeIf { it > 0 }
        ?: autoColumns

    val latestEnabled by rememberUpdatedState(enabled)
    val latestColumnsInForce by rememberUpdatedState(columnsInForce)
    val latestRawColumns by rememberUpdatedState(rawColumns)
    val latestCommit by rememberUpdatedState(onColumnsCommit)

    // The preference is the source of truth: any value it takes - this pinch's own commit
    // included - retires the preview.
    LaunchedEffect(rawColumns) {
        if (preview.intValue > 0) preview.intValue = 0
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width }
            .pinchToColumns(
                enabled = { latestEnabled },
                columnsInForce = { latestColumnsInForce },
                preview = preview,
                onFinished = { columns ->
                    // Nothing to commit when the pinch never crossed a step, and nothing to wait
                    // for when it landed on the value the preference already holds.
                    if (columns <= 0 || columns == latestRawColumns) {
                        preview.intValue = 0
                    } else {
                        latestCommit(columns)
                    }
                },
                onStep = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            ),
    ) {
        content(preview.intValue.takeIf { it > 0 } ?: rawColumns)
    }
}

/**
 * The pinch itself.
 *
 * Everything is read through lambdas captured once: the gesture lives in `pointerInput(Unit)`, and
 * restarting it on each new callback would cancel a pinch in progress.
 *
 * Events are claimed in the **initial** pass, before anything under the box gets them. That is what
 * keeps the gesture from also scrolling the grid, paging the shelf's category pager, pulling to
 * refresh, or arming a long press on the cover under the first finger - all of those handle their
 * pointers in the main pass and abandon a gesture whose changes are already consumed. One finger
 * alone is never claimed, so ordinary scrolling is untouched.
 */
private fun Modifier.pinchToColumns(
    enabled: () -> Boolean,
    columnsInForce: () -> Int,
    preview: MutableIntState,
    onFinished: (Int) -> Unit,
    onStep: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // A disabled grid leaves the gesture unclaimed so the list can still scroll with it.
            if (!enabled()) continue

            var pinching = false
            var pointers = 0
            var base = 0
            var zoom = 1f

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break

                if (pressed < 2) {
                    // Back to one finger: the count stops following, but the finger may still be
                    // dragging the list, so its events stay unclaimed.
                    pointers = pressed
                    continue
                }

                if (!pinching) {
                    pinching = true
                    pointers = pressed
                    base = columnsInForce()
                    zoom = 1f
                    event.changes.forEach { it.consume() }
                    continue
                }

                if (pressed != pointers) {
                    // A finger joined or left: this frame's ratio is measured against a different
                    // set of pointers and would jump, so only re-anchor.
                    pointers = pressed
                    event.changes.forEach { it.consume() }
                    continue
                }

                val frameZoom = event.calculateZoom()
                if (frameZoom.isFinite() && frameZoom > 0f) zoom *= frameZoom

                val target = pinchedColumnCount(base = base, zoom = zoom, current = columnsInForce())
                if (target != preview.intValue) {
                    preview.intValue = target
                    onStep()
                }
                event.changes.forEach { it.consume() }
            }

            // A pinch that ends where it started - including one that walked to another count and
            // came back - leaves the setting alone, so a pinch out and back on an automatic grid
            // stays automatic.
            if (pinching) onFinished(preview.intValue.takeIf { it != base } ?: 0)
        }
    }
}
