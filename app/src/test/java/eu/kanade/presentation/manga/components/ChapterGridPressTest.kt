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
import org.junit.jupiter.api.Assertions.assertTrue
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
     * 长按落定但一张卡片都没换位：这是"卡片跟着手指动了一下"，不是排序。
     *
     * 以前这条路径会无条件提交，于是手指抖过 touchSlop 就重写整份 custom_order、还把作品的
     * 排序方式切成「手动」——一次什么都没改的手势，却把用户选的排序弄丢了。
     */
    @Test
    fun `a press that never swaps writes no order`() {
        var commits = 0
        val state = gridState(rows = 2) { commits++ }

        state.beginPress(1L, Offset(40f, 70f), SLOP, SLOP)
        state.commit()

        assertEquals(0, commits, "a press that never swapped must not write an order")
    }

    /** 真的换过位才提交，而且只提交一次。 */
    @Test
    fun `a press that swaps a card writes the order`() {
        var commits = 0
        val state = gridState(rows = 2) { commits++ }

        state.beginPress(1L, Offset(40f, 70f), SLOP, SLOP)
        // 拖到第二排中间那张卡片上：越过邻格中线，库会把两张换位。
        state.onDragMove(Offset(160f, 235f))

        assertTrue(state.orderChanged, "crossing the neighbour must rearrange the cards")
        state.commit()
        assertEquals(1, commits)
    }

    /**
     * 拖出去再拖回来，松手时顺序必须还原。
     *
     * 拖动中的防回摆要求卡片越过接缝 35% 才允许换回去，手指停回原格右半边时那一次换位会被
     * 吃掉：卡片看着已经回到原格，顺序却还差一格。按这个顺序提交，"拖出去又拖回来"就被当成
     * 真的改了顺序，作品的排序方式跟着切成「手动」，而屏幕上什么都没变。
     */
    @Test
    fun `dragging a card back to its cell restores the order`() {
        var commits = 0
        val items = gridItems(rows = 2)
        val state = gridState(rows = 2, onCommit = { commits++ }, items = items)
        val before = items.map { it.id }

        state.beginPress(1L, Offset(50f, 75f), SLOP, SLOP)
        state.onDragMove(Offset(160f, 75f))
        assertEquals(listOf(2L, 1L, 3L, 4L, 5L, 6L), items.map { it.id }, "拖到右邻格应当换位")

        // 停回原格右半边：换位判定认这一格，防回摆却还差一点才放行。
        Thread.sleep(20)
        state.onDragMove(Offset(90f, 75f))
        assertEquals(listOf(2L, 1L, 3L, 4L, 5L, 6L), items.map { it.id }, "防回摆会吃掉这一次换位")

        state.commit()
        assertEquals(before, items.map { it.id }, "松手落点在原格，顺序必须还原")
        assertEquals(0, commits, "顺序没变就不该提交")
    }

    private fun gridItems(rows: Int): SnapshotStateList<ChapterList.Item> {
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
        return items
    }

    /**
     * A grid of [rows] full rows of three cards, each 100x150 with a 10px gutter, laid out from
     * the list's own top-left corner. The list is 600x800, so two rows of cards cover y in 0..310.
     */
    private fun gridState(
        rows: Int,
        items: SnapshotStateList<ChapterList.Item> = gridItems(rows),
        onCommit: () -> Unit = {},
    ): ChapterGridDragState {
        val state = ChapterGridDragState(
            listState = LazyListState(0, 0),
            items = items,
            columns = COLUMNS,
            onCommit = onCommit,
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
