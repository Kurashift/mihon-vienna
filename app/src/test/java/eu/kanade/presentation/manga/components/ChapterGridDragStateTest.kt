package eu.kanade.presentation.manga.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The grid drag layer decides, from geometry alone, which card ends up in which cell and how it
 * travels there. Those decisions are pure functions so they can be pinned down here; this is the
 * logic behind the two complaints that keep coming back — cards flying off in the wrong direction,
 * and cards tracing an arc instead of sliding to the next cell.
 */
class ChapterGridDragStateTest {

    private fun cell(index: Int, left: Float, top: Float, size: Float = 100f): Pair<Int, Rect> =
        index to Rect(left, top, left + size, top + size * 1.5f)

    /** A laid-out grid of cells, left to right then top to bottom. */
    private fun gridBounds(
        columns: Int,
        rows: Int,
        width: Float,
        height: Float,
    ): Map<Int, Rect> = buildMap {
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val left = col * (width + 10f)
                val top = row * (height + 10f)
                put(row * columns + col, Rect(left, top, left + width, top + height))
            }
        }
    }

    @Test
    fun `source mapping matches the real list operation for every from and to`() {
        val size = 9
        for (from in 0 until size) {
            for (to in 0 until size) {
                if (from == to) continue
                val before = (0 until size).map { "item-$it" }
                val after = before.toMutableList().apply {
                    val item = removeAt(from)
                    add(to, item)
                }
                for (index in 0 until size) {
                    assertEquals(
                        before.indexOf(after[index]),
                        gridSlotSource(index, from, to),
                        "index $index after moving $from to $to",
                    )
                }
            }
        }
    }

    @Test
    fun `swapping two neighbouring cells trades their content`() {
        assertEquals(1, gridSlotSource(0, 0, 1))
        assertEquals(0, gridSlotSource(1, 0, 1))
    }

    @Test
    fun `dragging right hands each cell the content of the one after it`() {
        assertEquals(2, gridSlotSource(1, 1, 3))
        assertEquals(3, gridSlotSource(2, 1, 3))
        assertEquals(1, gridSlotSource(3, 1, 3))
    }

    @Test
    fun `dragging left hands each cell the content of the one before it`() {
        assertEquals(3, gridSlotSource(1, 3, 1))
        assertEquals(1, gridSlotSource(2, 3, 1))
        assertEquals(2, gridSlotSource(3, 3, 1))
    }

    @Test
    fun `a reorder across a row boundary still trades only the two cells it straddles`() {
        // Slot 2 is the last cell of row 0 and slot 3 the first of row 1, so stepping between
        // them is the one case that moves a card diagonally.
        assertEquals(3, gridSlotSource(2, 2, 3))
        assertEquals(2, gridSlotSource(3, 2, 3))
    }

    @Test
    fun `a step straight up lands on the cell above instead of on the neighbour to the left`() {
        // Slot 5 is the right-hand cell of row 1 and slot 2 the one directly above it. Stepping
        // by one in the flat index answers 4, which is sideways: a finger dragged up would shove
        // its own row around twice before ever reaching the row it is actually over.
        assertEquals(2, gridStepTowards(5, 2, 3))
        assertEquals(8, gridStepTowards(5, 8, 3))
    }

    @Test
    fun `a step along a row stays on that row`() {
        assertEquals(4, gridStepTowards(5, 3, 3))
        assertEquals(4, gridStepTowards(3, 5, 3))
    }

    @Test
    fun `a diagonal step crosses whichever leg is longer`() {
        val bounds = gridBounds(columns = 3, rows = 3, width = 100f, height = 150f)
        // One row up is a longer trip than one column across, so the card climbs first.
        assertEquals(2, gridStepTowards(5, 1, 3) { bounds[it]?.center })
        // Two columns across is a longer trip than one row up, so the card turns first.
        assertEquals(4, gridStepTowards(5, 0, 3) { bounds[it]?.center })
    }

    @Test
    fun `walking one cell at a time always arrives`() {
        val columns = 3
        // A ragged last row, so a walk that runs off the end of the grid is caught too.
        val lastIndex = 29
        val bounds = gridBounds(columns = columns, rows = 10, width = 100f, height = 150f)
        val limit = columns + (lastIndex + 1) / columns
        for (from in 0..lastIndex) {
            for (to in 0..lastIndex) {
                var slot = from
                var steps = 0
                while (slot != to) {
                    slot = gridStepTowards(slot, to, columns) { bounds[it]?.center }
                    assertTrue(slot in 0..lastIndex, "stepped out of the grid walking $from to $to")
                    steps++
                    assertTrue(steps <= limit, "the walk from $from to $to is not getting closer")
                }
            }
        }
    }

    @Test
    fun `a walk onto a ragged last row never steps onto a cell that does not exist`() {
        val columns = 3
        // 29 cards: the last row holds two, so the third cell of row 9 was never laid out.
        val lastIndex = 28
        val bounds = gridBounds(columns = columns, rows = 10, width = 100f, height = 150f)
        val limit = columns + (lastIndex + 1) / columns
        for (end in 0..lastIndex) {
            for ((from, to) in listOf(end to lastIndex, lastIndex to end)) {
                var slot = from
                var steps = 0
                while (slot != to) {
                    slot = gridStepTowards(slot, to, columns, lastIndex) { bounds[it]?.center }
                    assertTrue(slot in 0..lastIndex, "stepped out of the grid walking $from to $to")
                    steps++
                    assertTrue(steps <= limit, "the walk from $from to $to is not getting closer")
                }
            }
        }
    }

    @Test
    fun `a point inside a cell resolves to that cell`() {
        val bounds = mapOf(cell(0, 0f, 0f), cell(1, 110f, 0f))

        assertEquals(0, gridSlotAt(Offset(40f, 70f), 6f, bounds, true) { true })
        assertEquals(1, gridSlotAt(Offset(150f, 70f), 6f, bounds, true) { true })
    }

    @Test
    fun `a press in the gutter answers no cell at all`() {
        val bounds = mapOf(cell(0, 0f, 0f), cell(1, 120f, 0f))
        // 108 sits in the 20px gutter, wider than the slop on either side.
        val inGutter = Offset(108f, 70f)

        assertNull(gridSlotAt(inGutter, 6f, bounds, allowFallback = false) { true })
    }

    @Test
    fun `a drag in the gutter falls back to the nearest cell`() {
        val bounds = mapOf(cell(0, 0f, 0f), cell(1, 120f, 0f))

        assertEquals(0, gridSlotAt(Offset(108f, 70f), 6f, bounds, allowFallback = true) { true })
    }

    @Test
    fun `overlapping slop prefers the cell whose centre is nearer`() {
        // Both cells contain the point once inflated by the slop; the nearer centre wins.
        val bounds = mapOf(cell(0, 0f, 0f), cell(1, 90f, 0f))

        assertEquals(1, gridSlotAt(Offset(97f, 70f), 6f, bounds, true) { true })
    }

    @Test
    fun `cells that scrolled away are never hit`() {
        val onScreen = Rect(0f, 0f, 100f, 150f)
        val scrolledAway = Rect(0f, -400f, 100f, -250f)
        val bounds = mapOf(0 to onScreen, 1 to scrolledAway)
        val insideScrolledAway = Offset(50f, -320f)

        assertEquals(
            0,
            gridSlotAt(insideScrolledAway, 6f, bounds, true) { it != scrolledAway },
        )
        assertNull(gridSlotAt(insideScrolledAway, 6f, bounds, true) { false })
    }

    @Test
    fun `a drag into the empty cells beside the last card of a ragged row moves nothing`() {
        val columns = 3
        // Row 0 is full, row 1 holds a single card in its first column, so the two cells beside
        // it were never laid out. Naming either of them a target displaces that last card: the
        // caller clamps a slot into the list, which turns a blank cell into the last real one and
        // slips past every "was it ever laid out" check, so the card shifts while the finger is
        // still beside it rather than on it. Reaching the card is what should move it.
        val bounds = gridBounds(columns = columns, rows = 2, width = 100f, height = 150f)
            .filterKeys { it <= 3 }

        // Both blanks: the one right beside the card, and the one past it.
        for (x in listOf(160f, 270f)) {
            assertNull(
                gridSlotAt(
                    Offset(x, 235f),
                    6f,
                    bounds,
                    allowFallback = true,
                    columns = columns,
                    columnPitchPx = 110f,
                ) { true },
                "the blank at x=$x must not answer for the card beside it",
            )
        }
    }

    @Test
    fun `a drag onto the only card of a ragged last row displaces it`() {
        val columns = 3
        val bounds = gridBounds(columns = columns, rows = 2, width = 100f, height = 150f)
            .filterKeys { it <= 3 }
        val onLastCard = Offset(50f, 235f)

        // Reaching the card is what moves it, so keeping the blank beside it inert must not put
        // the card itself out of reach.
        assertEquals(
            3,
            gridSlotAt(
                onLastCard,
                6f,
                bounds,
                allowFallback = true,
                columns = columns,
                columnPitchPx = 110f,
            ) { true },
        )
    }

    @Test
    fun `a drag below the last row aims at that row`() {
        val columns = 3
        val bounds = gridBounds(columns = columns, rows = 2, width = 100f, height = 150f)
        val belowTheGrid = Offset(160f, 500f)

        assertEquals(
            4,
            gridSlotAt(
                belowTheGrid,
                6f,
                bounds,
                allowFallback = true,
                columns = columns,
                columnPitchPx = 110f,
            ) { true },
        )
    }

    @Test
    fun `without a column pitch the nearest cell still answers`() {
        val bounds = mapOf(cell(0, 0f, 0f), cell(1, 120f, 0f))

        assertEquals(
            0,
            gridSlotAt(Offset(108f, 70f), 6f, bounds, allowFallback = true, columns = 3) { true },
        )
    }

    @Test
    fun `no geometry at all answers no cell`() {
        assertNull(gridSlotAt(Offset(10f, 10f), 6f, emptyMap(), true) { true })
    }

    @Test
    fun `displaced card keeps only the along-axis glide`() {
        // A card still holding a vertical glide is re-targeted along a horizontal swap.
        val kept = projectShiftOnAxis(Offset(30f, 40f), Offset(100f, 0f))

        assertEquals(30f, kept.x, 0.001f)
        assertEquals(0f, kept.y, 0.001f)
    }

    @Test
    fun `speed across the remaining path is dropped`() {
        val kept = projectShiftOnAxis(Offset(50f, 90f), Offset(0f, 100f))

        assertEquals(0f, kept.x, 0.001f)
        assertEquals(90f, kept.y, 0.001f)
    }

    @Test
    fun `missing axis never snaps the glide`() {
        val glide = Offset(30f, 40f)

        assertEquals(glide, projectShiftOnAxis(glide, Offset.Zero))
    }

    @Test
    fun `seam hover does not undo the swap`() {
        val from = Offset(100f, 100f)
        val to = Offset(200f, 100f)

        assertFalse(crossedBackOverSeam(cardCenter = from, fromCenter = from, toCenter = to, hysteresis = 0.35f))
        // Still short of the threshold at 60% of the way back.
        assertFalse(
            crossedBackOverSeam(
                cardCenter = Offset(160f, 100f),
                fromCenter = from,
                toCenter = to,
                hysteresis = 0.35f,
            ),
        )
    }

    @Test
    fun `being clearly over the next cell undoes the swap`() {
        val from = Offset(100f, 100f)
        val to = Offset(200f, 100f)

        assertTrue(
            crossedBackOverSeam(
                cardCenter = Offset(180f, 100f),
                fromCenter = from,
                toCenter = to,
                hysteresis = 0.35f,
            ),
        )
    }

    @Test
    fun `degenerate geometry never blocks the drag`() {
        val same = Offset(100f, 100f)

        assertTrue(
            crossedBackOverSeam(
                cardCenter = Offset(120f, 100f),
                fromCenter = same,
                toCenter = same,
                hysteresis = 0.35f,
            ),
        )
    }

    @Test
    fun `a glide pulls away from rest and never overshoots`() {
        val frame = 16f / 1_000f
        val distance = 300f
        var shift = Offset(distance, 0f)
        var speed = Offset.Zero
        var frames = 0
        var firstFrameTravel = 0f

        while (frames < 200) {
            val before = shift.x
            val next = settleStep(shift, speed, frame)
            if (next.settled) break
            shift = next.shift
            speed = next.speed
            if (frames == 0) firstFrameTravel = before - shift.x
            assertTrue(shift.x >= 0f, "overshot the cell to ${shift.x}")
            frames++
        }

        assertTrue(frames < 80, "took $frames frames to cover one cell")
        // Exponential decay covers about 6.5% of the distance on its first frame. Starting below
        // that is the whole point: the card is pulled into place rather than thrown.
        assertTrue(
            firstFrameTravel < distance * 0.065f,
            "first frame covered $firstFrameTravel px",
        )
    }

    @Test
    fun `a glide arrives at rest`() {
        val frame = 16f / 1_000f
        var shift = Offset(0f, 220f)
        var speed = Offset.Zero
        var frames = 0

        while (frames < 200) {
            val next = settleStep(shift, speed, frame)
            if (next.settled) {
                shift = next.shift
                speed = next.speed
                break
            }
            shift = next.shift
            speed = next.speed
            frames++
        }

        assertTrue(frames < 120, "took $frames frames to settle")
        assertTrue(abs(shift.y) < 0.5f, "left ${shift.y} px to travel")
        assertTrue(abs(speed.y) < 12f, "still moving at ${speed.y} px/s")
    }
}
