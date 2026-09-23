package eu.kanade.presentation.manga.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntSize
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/**
 * What the grid's long-press arbiter is allowed to claim a press on.
 *
 * This is the wiring behind a chapter selecting itself while the reader was long-pressing the
 * work's own title: the arbiter resolves the card under the finger through the same hit test a
 * drag uses, and that test falls back to the nearest cell from anywhere in the list. A press is
 * not a drag — it has to land on a card.
 */
class ChapterGridPressTest {

    @Test
    fun `a press away from the cards claims no chapter`() {
        val state = gridState(rows = 2)

        // The work's title and description are laid out above the grid in the same list.
        assertNull(state.cellIdAt(Offset(50f, -60f), SLOP), "a press above the grid is not a press on a chapter")
        assertNull(state.cellIdAt(Offset(160f, -400f), SLOP), "a press far above the grid is not a press on a chapter")
        // And the space under the last row belongs to no card either.
        assertNull(state.cellIdAt(Offset(160f, 500f), SLOP), "a press below the grid is not a press on a chapter")
    }

    @Test
    fun `a press on a card still claims that chapter`() {
        val state = gridState(rows = 2)

        // The strict press test has to stay a press test rather than a "nothing is ever pressed".
        assertEquals(1L, state.cellIdAt(Offset(40f, 70f), SLOP))
        assertEquals(5L, state.cellIdAt(Offset(160f, 235f), SLOP))
    }

    @Test
    fun `a press just past the edge of a card still claims it`() {
        val state = gridState(rows = 1)

        // A finger landing a few pixels outside a card is still aiming at it, so the hit slop has
        // to keep working in the strict mode or the edges of every card go dead.
        assertEquals(1L, state.cellIdAt(Offset(-3f, 70f), SLOP))
    }

    /**
     * A grid of [rows] full rows of three cards, each 100x150 with a 10px gutter, laid out from
     * the list's own top-left corner. The list is 600x800, so two rows of cards cover y in 0..310.
     */
    private fun gridState(rows: Int): ChapterGridDragState {
        val items = SnapshotStateList<ChapterList.Item>()
        repeat(rows * COLUMNS) { index ->
            items.add(
                ChapterList.Item(
                    chapter = Chapter.create().copy(id = index + 1L, name = "Chapter ${index + 1}"),
                    downloadState = Download.State.NOT_DOWNLOADED,
                    downloadProgress = 0,
                ),
            )
        }
        val state = ChapterGridDragState(
            listState = LazyListState(0, 0),
            items = items,
            columns = COLUMNS,
            onCommit = {},
        )
        state.onListPlaced(FakeCoordinates(Offset.Zero, IntSize(600, 800)))
        for (index in 0 until rows * COLUMNS) {
            val left = (index % COLUMNS) * (CELL_WIDTH + GUTTER)
            val top = (index / COLUMNS) * (CELL_HEIGHT + GUTTER)
            state.onSlotPlaced(
                index,
                FakeCoordinates(Offset(left.toFloat(), top.toFloat()), IntSize(CELL_WIDTH, CELL_HEIGHT)),
            )
        }
        return state
    }

    private companion object {
        const val COLUMNS = 3
        const val CELL_WIDTH = 100
        const val CELL_HEIGHT = 150
        const val GUTTER = 10
        const val SLOP = 6f
    }
}

/**
 * Just enough of [LayoutCoordinates] for the grid to measure itself: where a node sits in the
 * root and how big it is. Nothing on this path reads any other part of the interface.
 */
private class FakeCoordinates(
    private val position: Offset,
    private val nodeSize: IntSize,
) : LayoutCoordinates {
    override val size: IntSize get() = nodeSize
    override val providedAlignmentLines: Set<AlignmentLine> get() = emptySet()
    override val parentLayoutCoordinates: LayoutCoordinates? get() = null
    override val parentCoordinates: LayoutCoordinates? get() = null
    override val isAttached: Boolean get() = true
    override fun localToRoot(relativeToLocal: Offset): Offset = position + relativeToLocal
    override fun windowToLocal(relativeToWindow: Offset): Offset = relativeToWindow - position
    override fun localToWindow(relativeToLocal: Offset): Offset = position + relativeToLocal
    override fun localPositionOf(sourceCoordinates: LayoutCoordinates, relativeToSource: Offset): Offset =
        relativeToSource

    override fun localBoundingBoxOf(sourceCoordinates: LayoutCoordinates, clipBounds: Boolean): Rect =
        Rect(position, Size(nodeSize.width.toFloat(), nodeSize.height.toFloat()))

    override fun get(alignmentLine: AlignmentLine): Int = AlignmentLine.Unspecified
}
