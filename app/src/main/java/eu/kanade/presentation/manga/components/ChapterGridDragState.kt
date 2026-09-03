package eu.kanade.presentation.manga.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import eu.kanade.tachiyomi.ui.manga.ChapterList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val AutoScrollEdgeBand: Dp = 96.dp
private val AutoScrollMaxSpeed: Dp = 12.dp
private val HitSlop: Dp = 6.dp
private const val AUTO_SCROLL_FRAME_MILLIS = 16L

/**
 * Natural frequency of the glide that carries a displaced card into its new cell, in rad/s.
 * The spring is critically damped, so a card leaves at zero speed, never overshoots and reaches
 * its cell in roughly 5 / [SETTLE_OMEGA] seconds. Lower is slower and softer.
 */
private const val SETTLE_OMEGA = 14f

/** Longest integration step, so a dropped frame cannot make the spring blow up. */
private const val SETTLE_MAX_STEP_MILLIS = 32f

/** Below this distance a card counts as arrived and its glide is dropped. */
private const val SETTLE_EPSILON_PX = 0.5f

/** Slowest speed still worth animating, in px/s; below it the last half pixel is not visible. */
private const val SETTLE_VELOCITY_EPSILON_PX = 12f

/**
 * How far past the halfway mark the dragged card has to travel before an immediately reversed
 * swap is honoured. Without it a finger resting on the seam between two cells makes the pair
 * trade places on every frame.
 */
private const val SWAP_REVERSAL_HYSTERESIS_FRACTION = 0.35f

/**
 * Shortest gap between two swaps, in milliseconds. One frame at most: enough to stop a pointer
 * event and a frame callback from stepping twice for the same gesture, short enough that a fast
 * drag still tracks the finger.
 */
private const val SWAP_THROTTLE_MILLIS = 16L

/**
 * Cap on how many cell rectangles are tracked at once.
 *
 * A drag that triggers auto-scroll lays out far more rows than fit on screen, and none of them
 * ever report leaving, so the map has to be swept from time to time rather than left to grow.
 */
private const val MAX_TRACKED_SLOTS = 96

/**
 * Drag state for the chapter grid.
 *
 * The grid is a [androidx.compose.foundation.lazy.LazyColumn] of rows, three cards each, so the
 * framework has no notion of a card changing cell: rows hold their position and only their
 * content changes. This state therefore tracks where every visible cell sits on screen and hands
 * each card an offset to draw itself at, which is what makes a cell change look like a glide.
 *
 * The marker is what lets a grid card be skipped during recomposition: its mutable parts are all
 * snapshot state, so a card that has not moved can be left alone instead of being rebuilt.
 */
@Stable
class ChapterGridDragState(
    private val listState: LazyListState,
    private val items: SnapshotStateList<ChapterList.Item>,
    private val columns: Int,
    private val onCommit: () -> Unit,
) {
    var draggingId by mutableStateOf<Long?>(null)
        private set

    val isDragging: Boolean get() = draggingId != null

    /** Cell rectangles in root coordinates, keyed by slot. Only visible cells are registered. */
    private val slotBounds = SnapshotStateMap<Int, Rect>()

    /**
     * Distance between the left edges of two neighbouring cells, in px.
     *
     * Every row is laid out the same way, so one measurement is enough to name the column a
     * finger is over even where no cell was ever laid out — the empty half of a ragged last row.
     */
    private var columnPitch = 0f

    /** Title rectangles in root coordinates, keyed by chapter id. */
    private val titleBounds = SnapshotStateMap<Long, Rect>()

    private var listTopLeft = Offset.Zero
    private var listHeight = 0f

    private var pointerRoot by mutableStateOf(Offset.Zero)
    private var dragStartRoot = Offset.Zero
    private var dragSlopPx = 0f
    private var hitSlopPx = 0f

    /** Pointer offset inside the card at the moment it was picked up. */
    private var grabOffset = Offset.Zero

    private var pressedId: Long? = null

    /** How far each card is currently drawn away from the cell it occupies. */
    private val settleShift = SnapshotStateMap<Int, Offset>()

    /** Speed of each glide. Kept across swaps so a re-targeted card never restarts from rest. */
    private val settleVelocity = SnapshotStateMap<Int, Offset>()

    internal val settleSignal = Channel<Unit>(Channel.CONFLATED)

    private var lastSwapFrom = -1
    private var lastSwapTo = -1
    private var lastSwapNanos = 0L

    var movedAfterLongPress: Boolean = false
        private set

    var orderChanged: Boolean = false
        private set

    fun onListPlaced(coordinates: LayoutCoordinates) {
        listTopLeft = coordinates.positionInRoot()
        listHeight = coordinates.size.height.toFloat()
        // Dragging down a long list lays out far more rows than stay on screen, and a slot that
        // is gone never reports again. Without pruning the map grows for the whole drag while
        // every hit test walks all of it.
        if (slotBounds.size > MAX_TRACKED_SLOTS) {
            slotBounds.entries.removeAll { !isVisible(it.value) }
        }
    }

    fun onSlotPlaced(slot: Int, coordinates: LayoutCoordinates) {
        val rect = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
        slotBounds[slot] = rect
        learnColumnPitch(slot, rect)
    }

    /**
     * Re-measures the column pitch from a cell and a neighbour on its row.
     *
     * Cells of one row are laid out together and rows scroll in and out as a unit, so a pitch
     * learned from any row holds for every other row — including the ragged last one, which has
     * no neighbour of its own to be measured against.
     */
    private fun learnColumnPitch(slot: Int, rect: Rect) {
        if (columns < 2) return
        val row = slot / columns
        val column = slot % columns
        for (other in (row * columns) until (row * columns + columns)) {
            val otherColumn = other % columns
            if (otherColumn == column) continue
            val otherLeft = slotBounds[other]?.left ?: continue
            val pitch = (rect.left - otherLeft) / (column - otherColumn)
            // A column is a cell and the gutter after it; anything narrower is not a column.
            if (pitch > rect.width) {
                columnPitch = pitch
                return
            }
        }
    }

    fun onTitlePlaced(id: Long, coordinates: LayoutCoordinates) {
        titleBounds[id] = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
    }

    fun clearBounds() {
        slotBounds.clear()
        titleBounds.clear()
        columnPitch = 0f
    }

    fun beginPress(id: Long, downRoot: Offset, moveSlopPx: Float, slopPx: Float) {
        pressedId = id
        pointerRoot = downRoot
        dragStartRoot = downRoot
        dragSlopPx = moveSlopPx
        hitSlopPx = slopPx
        movedAfterLongPress = false
        orderChanged = false
        settleShift.clear()
        settleVelocity.clear()
        lastSwapFrom = -1
        lastSwapTo = -1
        lastSwapNanos = 0L
        val slot = items.indexOfFirst { it.id == id }
        grabOffset = downRoot - (slotBounds[slot]?.center ?: downRoot)
    }

    fun endDrag() {
        draggingId?.let { id ->
            val slot = items.indexOfFirst { it.id == id }
            // 卡片此刻画在手指下，离它落定的格子还差一段。把这段距离交给挤开用的那条弹簧，
            // 松手就有了柔和落位，也免得跟手位移和挤开位移两套时钟叠在一起打架。
            val remaining = offsetFor(id)
            if (remaining == Offset.Zero) {
                settleShift.remove(slot)
            } else {
                settleShift[slot] = remaining
            }
            settleVelocity.remove(slot)
        }
        draggingId = null
        pressedId = null
        grabOffset = Offset.Zero
        movedAfterLongPress = false
        orderChanged = false
        lastSwapFrom = -1
        lastSwapTo = -1
        lastSwapNanos = 0L
        // 落位还要走一段，唤醒帧循环把它跑完。
        settleSignal.trySend(Unit)
    }

    fun commit() {
        onCommit()
    }

    fun toRoot(local: Offset): Offset = listTopLeft + local

    fun cellIdAt(point: Offset, slopPx: Float): Long? =
        slotAt(point, slopPx)?.let { items.getOrNull(it)?.id }

    private fun slotAt(point: Offset, slopPx: Float): Int? =
        gridSlotAt(
            point = point,
            slopPx = slopPx,
            bounds = slotBounds,
            allowFallback = true,
            columns = columns,
            columnPitchPx = columnPitch,
            isVisible = ::isVisible,
        )

    fun isOnTitle(id: Long): Boolean {
        val rect = titleBounds[id] ?: return false
        if (!isVisible(rect)) return false
        return rect.inflate(hitSlopPx).contains(pointerRoot)
    }

    fun onDragMove(local: Offset) {
        pointerRoot = toRoot(local)
        movedAfterLongPress = movedAfterLongPress ||
            (pointerRoot - dragStartRoot).getDistance() > dragSlopPx
        if (draggingId == null && pressedId != null) settleSignal.trySend(Unit)
        draggingId = pressedId
        if (movedAfterLongPress) moveToTargetAtPointer()
    }

    /**
     * Nudges the list while the dragged card is held near an edge. Returns the scroll distance
     * that was actually consumed.
     */
    suspend fun autoScroll(edgeBand: Float, maxSpeed: Float): Float {
        if (!movedAfterLongPress) return 0f
        val top = listTopLeft.y
        val bottom = top + listHeight
        val band = min(edgeBand, listHeight / 3f)
        if (band <= 0f) return 0f
        val y = pointerRoot.y
        val ratio = when {
            y < top + band -> ((y - (top + band)) / band).coerceIn(-1f, 0f)
            y > bottom - band -> ((y - (bottom - band)) / band).coerceIn(0f, 1f)
            else -> return 0f
        }
        return listState.dispatchRawDelta(ratio * maxSpeed)
    }

    fun moveToTargetAtPointer() {
        val id = draggingId ?: return
        // Hit test with the card's own centre, not the bare pointer: grabbing a card near its
        // edge would otherwise let the finger reach the next cell while the card is still behind.
        val dragCenter = pointerRoot - grabOffset
        val target = slotAt(dragCenter, hitSlopPx) ?: return
        val from = items.indexOfFirst { it.id == id }
        if (from < 0 || from == target) return
        val to = target.coerceIn(0, items.lastIndex)
        if (to == from) return
        // One cell at a time. A single jump across the whole range would displace every card in
        // between at once, which reads as the grid exploding rather than making room.
        //
        // The step also has to stay inside the list: a cell past its end was never laid out, so
        // it can neither be drawn nor swapped into, and the step down from the row above a ragged
        // last row overshoots into exactly such a cell.
        val step = gridStepTowards(from, to, columns, items.lastIndex) { slotBounds[it]?.center }
        // A swap only reads as a glide when both cells have reported a rectangle. A row that has
        // just scrolled in has not run onGloballyPositioned yet; swapping without the pair leaves
        // settleMove nothing to measure, so the card jumps straight to its new cell instead of
        // being pushed aside. Skip and let the frame loop retry once the geometry lands.
        if (!hasGeometry(from, step)) return
        if (step == lastSwapFrom && from == lastSwapTo && !crossedBackOverSeam(from, step)) return
        val now = System.nanoTime()
        if (now - lastSwapNanos < SWAP_THROTTLE_MILLIS * 1_000_000L) return
        lastSwapFrom = from
        lastSwapTo = step
        lastSwapNanos = now
        swapOnce(from, step)
    }

    private fun crossedBackOverSeam(from: Int, to: Int): Boolean {
        val fromCenter = slotBounds[from]?.center ?: return true
        val toCenter = slotBounds[to]?.center ?: return true
        return crossedBackOverSeam(
            cardCenter = pointerRoot - grabOffset,
            fromCenter = fromCenter,
            toCenter = toCenter,
            hysteresis = SWAP_REVERSAL_HYSTERESIS_FRACTION,
        )
    }

    /** Offset that pins the dragged card to the pointer. */
    fun offsetFor(id: Long): Offset {
        if (draggingId != id) return Offset.Zero
        val slot = items.indexOfFirst { it.id == id }
        val center = slotBounds[slot]?.center ?: Offset.Zero
        return (pointerRoot - grabOffset) - center
    }

    /** Offset the card sitting in [slot] still has to glide through. */
    fun settleFor(slot: Int): Offset = settleShift[slot] ?: Offset.Zero

    /**
     * Distance a card has to travel because it moved from [source] to [slot].
     *
     * Two cells on one row share a baseline; any vertical difference between them is the list
     * scrolling between two position callbacks rather than a real change of row. Pinning it to
     * zero keeps a card that stays on its row sliding sideways, which is what "being pushed
     * along" looks like. Only a card that actually crosses a row boundary travels diagonally.
     */
    private fun settleMove(source: Int, slot: Int): Offset {
        val sourceCenter = slotBounds[source]?.center ?: return Offset.Zero
        val slotCenter = slotBounds[slot]?.center ?: return Offset.Zero
        val move = sourceCenter - slotCenter
        return if (source / columns == slot / columns) Offset(move.x, 0f) else move
    }

    private fun swapOnce(from: Int, to: Int) {
        val range = min(from, to)..max(from, to)
        // Snapshot the glides before the list moves: each card keeps whatever distance and speed
        // it had left, but measured against the cell it is about to occupy.
        val carried = HashMap<Int, Offset>(range.last - range.first + 1)
        val carriedSpeed = HashMap<Int, Offset>(range.last - range.first + 1)
        for (slot in range) {
            val source = gridSlotSource(slot, from, to)
            carried[slot] = settleShift[source] ?: Offset.Zero
            carriedSpeed[slot] = settleVelocity[source] ?: Offset.Zero
        }

        val item = items.removeAt(from)
        items.add(to, item)

        for (slot in range) {
            val move = settleMove(gridSlotSource(slot, from, to), slot)
            // The card does not jump: the list moved it by -move, so the shift grows by +move and
            // it stays exactly where it was drawn a moment ago.
            val shift = (carried[slot] ?: Offset.Zero) + move
            if (shift == Offset.Zero) {
                settleShift.remove(slot)
                settleVelocity.remove(slot)
                continue
            }
            // Aim the speed down the line that is left to travel. Speed across that line is what
            // turns a straight glide into an arc, and dropping the position onto the line instead
            // would teleport the card sideways.
            val speed = projectShiftOnAxis(carriedSpeed[slot] ?: Offset.Zero, shift)
            settleShift[slot] = shift
            if (speed == Offset.Zero) {
                settleVelocity.remove(slot)
            } else {
                settleVelocity[slot] = speed
            }
        }
        orderChanged = true
        settleSignal.trySend(Unit)
    }

    /**
     * Advances every outstanding glide, one frame at a time. Must run in a scope that owns a
     * [androidx.compose.runtime.MonotonicFrameClock]; a pointer input scope does not have one.
     *
     * It also keeps re-evaluating the drop target: auto-scroll and a finger held still both change
     * which cell the pointer is over without producing a pointer event, and without this the order
     * would stall part-way through a catch-up.
     */
    internal suspend fun runSettleLoop() {
        var lastNanos = 0L
        while (isDragging || settleShift.isNotEmpty()) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    val elapsedMillis = (nanos - lastNanos) / 1_000_000f
                    if (elapsedMillis > 0f) advanceSettle(elapsedMillis)
                }
                lastNanos = nanos
                if (isDragging) moveToTargetAtPointer()
            }
        }
    }

    private fun advanceSettle(elapsedMillis: Float) {
        val step = (elapsedMillis / 1_000f).coerceAtMost(SETTLE_MAX_STEP_MILLIS / 1_000f)
        val arrived = ArrayList<Int>()
        for ((slot, shift) in settleShift) {
            val next = settleStep(shift, settleVelocity[slot] ?: Offset.Zero, step)
            if (next.settled) {
                arrived += slot
            } else {
                settleShift[slot] = next.shift
                settleVelocity[slot] = next.speed
            }
        }
        arrived.forEach {
            settleShift.remove(it)
            settleVelocity.remove(it)
        }
    }

    private fun isVisible(rect: Rect): Boolean {
        val top = rect.top - listTopLeft.y
        return top < listHeight && rect.bottom - listTopLeft.y > 0f
    }

    private fun hasGeometry(from: Int, to: Int): Boolean =
        slotBounds.containsKey(from) && slotBounds.containsKey(to)
}

/**
 * The neighbouring cell a card passes through on its way from [from] to [to].
 *
 * Walking the flat index one step at a time only behaves while the two cells share a row. From
 * the right-hand column of a three-wide grid, `from - 1` is the cell to the *left*, so a finger
 * dragged upwards shoves its own row sideways for two swaps before it ever reaches the row the
 * finger is actually over — and with the swap throttle in the way the finger outruns the walk,
 * which is why the cell above sometimes never moves at all.
 *
 * The step therefore follows the grid: along the row while rows match, along the column while
 * columns match, and down the longer leg when the move is diagonal. Either leg leaves the other
 * axis untouched and shrinks its own by exactly one cell, so the walk always arrives.
 */
fun gridStepTowards(
    from: Int,
    to: Int,
    columns: Int,
    lastIndex: Int = Int.MAX_VALUE,
    centerOf: (Int) -> Offset? = { null },
): Int {
    val fromRow = from / columns
    val toRow = to / columns
    val fromCol = from % columns
    val toCol = to % columns
    val step = when {
        fromRow == toRow -> from + if (toCol > fromCol) 1 else -1
        fromCol == toCol -> from + if (toRow > fromRow) columns else -columns
        // Diagonal. Compare the legs in pixels rather than in cells: a card is taller than it
        // is wide, so one row is a longer trip than one column and the row is crossed first.
        else -> {
            val fromCenter = centerOf(from)
            val toCenter = centerOf(to)
            val dx: Float
            val dy: Float
            if (fromCenter != null && toCenter != null) {
                dx = abs(toCenter.x - fromCenter.x)
                dy = abs(toCenter.y - fromCenter.y)
            } else {
                dx = abs(toCol - fromCol).toFloat()
                dy = abs(toRow - fromRow).toFloat()
            }
            if (dx >= dy) {
                from + if (toCol > fromCol) 1 else -1
            } else {
                from + if (toRow > fromRow) columns else -columns
            }
        }
    }
    // A row that is not full ends before its last column does, so the step down onto it lands on
    // a cell that was never laid out. Land on the last real cell instead — that is the cell the
    // card was aimed at anyway — and where it is already there, take the plain one-slot step,
    // which on a ragged row is always the cell beside it.
    val clamped = step.coerceIn(0, lastIndex)
    return if (clamped != from) clamped else (from + if (to > from) 1 else -1).coerceIn(0, lastIndex)
}

/**
 * Which slot the card now sitting at [index] came from, after `removeAt(from) + add(to, item)`.
 *
 * For `L = [A, B, C, D, E]` with `from = 1 (B)` and `to = 3 (D)`:
 * `removeAt(1) -> [A, C, D, E]`; `add(3, B) -> [A, C, D, B, E]`, so index 1 holds what used to
 * be at 2, index 2 what used to be at 3, and index 3 what used to be at 1. In other words the
 * range `[from, to - 1]` shifts down by one and `to` receives [from].
 */
fun gridSlotSource(index: Int, from: Int, to: Int): Int {
    val range = min(from, to)..max(from, to)
    return when (index) {
        to -> from
        in range -> if (from < to) index + 1 else index - 1
        else -> index
    }
}

/**
 * Keeps only the part of [shift] that runs along [axis].
 *
 * A card can still be mid-glide from an earlier swap when the next one moves it along a
 * different axis. Carrying the whole vector over would bend the path; carrying the along-axis
 * component keeps every leg straight.
 */
fun projectShiftOnAxis(shift: Offset, axis: Offset): Offset {
    val lengthSquared = axis.x * axis.x + axis.y * axis.y
    if (lengthSquared <= 0f) return shift
    val scale = (shift.x * axis.x + shift.y * axis.y) / lengthSquared
    if (scale.isInfinite()) return shift
    return Offset(axis.x * scale, axis.y * scale)
}

/** One frame of a glide: where the card is, how fast it is moving, and whether it has arrived. */
internal data class SettleSample(
    val shift: Offset,
    val speed: Offset,
    val settled: Boolean,
)

/**
 * Advances a glide by [stepSeconds]. Critically damped, so a card pulls away from rest instead of
 * being thrown, covers the remaining distance without overshooting, and arrives at rest — which
 * is what lets a re-targeted card carry its speed into the new direction instead of restarting.
 */
internal fun settleStep(shift: Offset, speed: Offset, stepSeconds: Float): SettleSample {
    val acceleration = shift * (-SETTLE_OMEGA * SETTLE_OMEGA) + speed * (-2f * SETTLE_OMEGA)
    val nextSpeed = speed + acceleration * stepSeconds
    val nextShift = shift + nextSpeed * stepSeconds
    val settled = abs(nextShift.x) < SETTLE_EPSILON_PX &&
        abs(nextShift.y) < SETTLE_EPSILON_PX &&
        abs(nextSpeed.x) < SETTLE_VELOCITY_EPSILON_PX &&
        abs(nextSpeed.y) < SETTLE_VELOCITY_EPSILON_PX
    return SettleSample(nextShift, nextSpeed, settled)
}

/**
 * Whether the dragged card has travelled far enough towards [toCenter] to justify undoing the
 * swap that just put it at [fromCenter]. Hovering on the seam leaves [progress] near zero and
 * the reversal is refused.
 */
fun crossedBackOverSeam(
    cardCenter: Offset,
    fromCenter: Offset,
    toCenter: Offset,
    hysteresis: Float,
): Boolean {
    val axisX = toCenter.x - fromCenter.x
    val axisY = toCenter.y - fromCenter.y
    val lengthSquared = axisX * axisX + axisY * axisY
    // With no geometry to judge by, let the swap through rather than stranding the card.
    if (lengthSquared <= 0f) return true
    val relativeX = cardCenter.x - fromCenter.x
    val relativeY = cardCenter.y - fromCenter.y
    val progress = (relativeX * axisX + relativeY * axisY) / lengthSquared
    return progress >= 0.5f * (1f + hysteresis)
}

/**
 * The cell under [point], or null when there is nothing to go on.
 *
 * Cells that scrolled out of the list are ignored even if their rectangle is still cached:
 * during a fast scroll the geometry lags behind the composited position and would answer for a
 * cell the user cannot see.
 */
fun gridSlotAt(
    point: Offset,
    slopPx: Float,
    bounds: Map<Int, Rect>,
    allowFallback: Boolean,
    columns: Int = 1,
    columnPitchPx: Float = 0f,
    isVisible: (Rect) -> Boolean,
): Int? {
    var slot: Int? = null
    var bestDistance = Float.MAX_VALUE
    var fallback: Int? = null
    var fallbackDistance = Float.MAX_VALUE
    for ((index, rect) in bounds) {
        if (!isVisible(rect)) continue
        val distance = (rect.center - point).getDistance()
        if (distance < fallbackDistance) {
            fallbackDistance = distance
            fallback = index
        }
        if (rect.inflate(slopPx).contains(point) && distance < bestDistance) {
            bestDistance = distance
            slot = index
        }
    }
    if (slot != null) return slot
    if (!allowFallback) return null
    // The gaps between cards belong to no cell, and so does the trailing space of a row that is
    // not full. The nearest centre is not the answer: beside the last card of such a row the
    // nearest centre sits on the row above, so the drag would land in that row instead of the one
    // it is over. Rebuilding the column from the lattice names the row the drag is over.
    val lattice = gridLatticeSlot(point, bounds, columns, columnPitchPx, isVisible)
    if (lattice != null) {
        // The lattice also names cells that were never laid out: the trailing space of a row that
        // is not full. No card sits in one, so it is not a target, and that includes the cell
        // right beside the last card — reaching the card is what should move it, and a blank that
        // answers for it shifts it while the finger is still beside it, not on it.
        //
        // It matters because a caller that clamps a target into the list turns a blank cell into
        // the last real one, which slips past every "was it ever laid out" check.
        return lattice.takeIf { bounds.containsKey(it) }
    }
    return fallback
}

/**
 * The cell [point] would land in if the row it is over were full.
 *
 * Rows share one lattice, so the band the point is in plus the column pitch is enough to name a
 * cell that was never laid out: a gutter between two cards, or the trailing space of a row that
 * is not full. Only some of those hold a card, so what such a cell means is left to the caller.
 * Null when the lattice cannot be rebuilt, leaving the caller its own fallback.
 */
internal fun gridLatticeSlot(
    point: Offset,
    bounds: Map<Int, Rect>,
    columns: Int,
    columnPitchPx: Float,
    isVisible: (Rect) -> Boolean,
): Int? {
    if (columns < 2 || columnPitchPx <= 0f) return null
    var anchor: Int? = null
    var anchorRect: Rect? = null
    var anchorDistance = Float.MAX_VALUE
    for ((index, rect) in bounds) {
        if (!isVisible(rect)) continue
        // The row is the band the point is closest to vertically. Inside a band the distance is
        // zero, so the row the card is actually over wins however near the other rows' centres
        // are — which is exactly the case the nearest centre gets wrong.
        val distance = verticalDistance(point.y, rect)
        if (distance < anchorDistance) {
            anchorDistance = distance
            anchor = index
            anchorRect = rect
        }
    }
    val slot = anchor ?: return null
    val rect = anchorRect ?: return null
    val row = slot / columns
    val rowLeft = rect.left - (slot % columns) * columnPitchPx
    // Columns are named by their centres, so a point in a gutter goes to the nearer side.
    val column = ((point.x - rowLeft - rect.width / 2f) / columnPitchPx)
        .roundToInt()
        .coerceIn(0, columns - 1)
    return row * columns + column
}

/** How far [y] lies outside [rect] vertically; zero while it is inside the band. */
private fun verticalDistance(y: Float, rect: Rect): Float = when {
    y < rect.top -> rect.top - y
    y > rect.bottom -> y - rect.bottom
    else -> 0f
}

fun Modifier.chapterGridSlotBounds(
    state: ChapterGridDragState,
    slot: Int,
): Modifier = this.onGloballyPositioned { state.onSlotPlaced(slot, it) }

/**
 * Long-press arbiter for the chapter grid.
 *
 * It owns the long press while it is enabled so that the press is classified once, by a single
 * owner that also sees the movement: long press in place copies a title or toggles selection,
 * long press and move reorders. Both hold whether or not a selection is open, so a card can be
 * dragged out of an existing selection exactly as in the list.
 */
@Composable
fun Modifier.chapterGridDragSource(
    state: ChapterGridDragState,
    enabled: Boolean,
    onLongPressInPlace: (Long, Boolean) -> Unit,
): Modifier {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    // The pointer input scope is suspension-restricted, so the auto-scroll job has to live on a
    // scope that is free to call ordinary suspending functions.
    val scope = rememberCoroutineScope()
    // This has to outlive the composition that created it and must never become a key of the
    // gesture below: a long press in place opens or closes the selection bar, and making that a
    // key restarts the gesture mid-press, the up event goes through unconsumed and the card's
    // own clickable opens the reader.
    val onLongPress by rememberUpdatedState(onLongPressInPlace)
    val edgePx = with(density) { AutoScrollEdgeBand.toPx() }
    val maxSpeedPx = with(density) { AutoScrollMaxSpeed.toPx() }
    val hitSlopPx = with(density) { HitSlop.toPx() }

    DisposableEffect(state) {
        onDispose { state.clearBounds() }
    }
    LaunchedEffect(state) {
        for (signal in state.settleSignal) {
            state.runSettleLoop()
        }
    }

    if (!enabled) return this

    return this.pointerInput(
        state,
        edgePx,
        maxSpeedPx,
        hitSlopPx,
        haptic,
        scope,
    ) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            val id = state.cellIdAt(state.toRoot(down.position), hitSlopPx)
                ?: return@awaitEachGesture
            val slopPx = viewConfiguration.touchSlop
            val timeoutMillis = viewConfiguration.longPressTimeoutMillis

            // Wait out the long press, bailing out the moment the finger travels far enough to
            // be a scroll or a swipe, or lifts early. Without the timeout a finger held perfectly
            // still would never resolve, since no further pointer event arrives.
            var aborted = false
            withTimeoutOrNull(timeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val delta = change.position - down.position
                    if (max(abs(delta.x), abs(delta.y)) > slopPx) break
                }
                aborted = true
            }
            if (aborted) return@awaitEachGesture

            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            state.beginPress(id, state.toRoot(down.position), slopPx, hitSlopPx)

            down.consume()
            val scrollJob = scope.launch {
                while (isActive) {
                    state.autoScroll(edgePx, maxSpeedPx)
                    delay(AUTO_SCROLL_FRAME_MILLIS)
                }
            }
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // 抬起也要吃掉。卡片自己身上的 clickable 早就看到了按下，只要抬起没被
                    // 消费它就会当成一次点击，长按原地选中或复制完标题就顺势打开阅读器。
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    // 从长按成立那一刻起，每一次移动都要吃掉。是否算"移动过"是在下面这次
                    // onDragMove 里才算出来的，所以第一个移动事件上 movedAfterLongPress 必然
                    // 还是 false —— 等到它变 true 再吃，那第一个事件就漏给了 LazyColumn 的
                    // 滚动手势。它一路累积着长按期间手指的微小位移，正好在这一帧跨过
                    // touchSlop，于是把这一次位移当成滚动吃掉：卡片刚被拎起，列表就向上抖
                    // 一下。抖完我们才吃，所以它只发生一次。拖拽中想滚列表走的是边缘自动
                    // 滚动那条路，不受影响。
                    change.consume()
                    state.onDragMove(change.position)
                }
            } finally {
                scrollJob.cancel()
            }

            if (state.movedAfterLongPress) {
                state.commit()
            } else {
                onLongPress(id, state.isOnTitle(id))
            }
            state.endDrag()
        }
    }
}
