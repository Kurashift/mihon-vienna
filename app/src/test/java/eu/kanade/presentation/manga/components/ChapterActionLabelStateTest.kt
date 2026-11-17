package eu.kanade.presentation.manga.components

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterActionLabelStateTest {

    @Test
    fun `downloaded chapter delete button can read its initial label state`() = runTest {
        val labels = ChapterActionLabelState(backgroundScope)
        assertFalse(labels.isShowing(ChapterBottomAction.DELETE))
        ChapterBottomAction.entries.forEach { assertFalse(labels.isShowing(it)) }
    }

    @Test
    fun `every available action shows only its own label`() = runTest {
        val labels = ChapterActionLabelState(backgroundScope)
        ChapterBottomAction.entries.forEach { action ->
            labels.show(action)
            assertEquals(listOf(action), ChapterBottomAction.entries.filter { labels.isShowing(it) })
        }
    }

    @Test
    fun `delete label disappears after the existing one second delay`() = runTest {
        val labels = ChapterActionLabelState(backgroundScope)
        labels.show(ChapterBottomAction.DELETE)
        runCurrent()
        advanceTimeBy(999)
        assertTrue(labels.isShowing(ChapterBottomAction.DELETE))
        advanceTimeBy(1)
        runCurrent()
        assertFalse(labels.isShowing(ChapterBottomAction.DELETE))
    }

    @Test
    fun `previous timer cannot hide a different newly shown label`() = runTest {
        val labels = ChapterActionLabelState(backgroundScope)
        labels.show(ChapterBottomAction.BOOKMARK)
        runCurrent()
        advanceTimeBy(600)
        labels.show(ChapterBottomAction.DELETE)
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        assertTrue(labels.isShowing(ChapterBottomAction.DELETE))
        assertFalse(labels.isShowing(ChapterBottomAction.BOOKMARK))
        advanceTimeBy(600)
        runCurrent()
        assertFalse(labels.isShowing(ChapterBottomAction.DELETE))
    }

    @Test
    fun `repeating a long press extends that same label duration`() = runTest {
        val labels = ChapterActionLabelState(backgroundScope)
        labels.show(ChapterBottomAction.DELETE)
        runCurrent()
        advanceTimeBy(600)
        labels.show(ChapterBottomAction.DELETE)
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        assertTrue(labels.isShowing(ChapterBottomAction.DELETE))
        advanceTimeBy(600)
        runCurrent()
        assertFalse(labels.isShowing(ChapterBottomAction.DELETE))
    }
}
