package eu.kanade.presentation.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioQueueExpansionTest {

    private val keys = listOf("1|work-1", "2|work-2", "3|work-3")

    @Test
    fun `opening one section closes the others explicitly`() {
        val state = AudioQueueExpansion.toggleSection(
            key = keys[1],
            expanded = true,
            liveKeys = keys,
            expandedSections = setOf(keys[0]),
            collapsedSections = emptySet(),
        )

        assertEquals(setOf(keys[1]), state.expanded)
        assertEquals(setOf(keys[0], keys[2]), state.collapsed)
    }

    @Test
    fun `an untouched row is written as collapsed so it cannot follow the current track`() {
        // The whole point of the explicit collapsed set: without it a row with no entry falls
        // back to "is current" and would reopen itself.
        val state = AudioQueueExpansion.toggleSection(
            key = keys[0],
            expanded = true,
            liveKeys = keys,
            expandedSections = emptySet(),
            collapsedSections = emptySet(),
        )

        assertTrue(keys[1] in state.collapsed)
        assertTrue(keys[2] in state.collapsed)
    }

    @Test
    fun `opening a second section replaces the first`() {
        val first = AudioQueueExpansion.toggleSection(
            key = keys[0],
            expanded = true,
            liveKeys = keys,
            expandedSections = emptySet(),
            collapsedSections = emptySet(),
        )
        val second = AudioQueueExpansion.toggleSection(
            key = keys[2],
            expanded = true,
            liveKeys = keys,
            expandedSections = first.expanded,
            collapsedSections = first.collapsed,
        )

        assertEquals(setOf(keys[2]), second.expanded)
        assertTrue(keys[0] in second.collapsed)
        assertEquals(setOf(keys[0], keys[1]), second.collapsed)
    }

    @Test
    fun `closing the open section leaves the accordion fully closed`() {
        val opened = AudioQueueExpansion.toggleSection(
            key = keys[0],
            expanded = true,
            liveKeys = keys,
            expandedSections = emptySet(),
            collapsedSections = emptySet(),
        )
        val closed = AudioQueueExpansion.toggleSection(
            key = keys[0],
            expanded = false,
            liveKeys = keys,
            expandedSections = opened.expanded,
            collapsedSections = opened.collapsed,
        )

        assertTrue(closed.expanded.isEmpty())
        assertEquals(keys.toSet(), closed.collapsed)
    }

    @Test
    fun `closing one row does not reopen rows the user shut earlier`() {
        val state = AudioQueueExpansion.toggleSection(
            key = keys[0],
            expanded = false,
            liveKeys = keys,
            expandedSections = setOf(keys[0], keys[1]),
            collapsedSections = setOf(keys[2]),
        )

        assertEquals(setOf(keys[1]), state.expanded)
        assertEquals(setOf(keys[0], keys[2]), state.collapsed)
    }

    @Test
    fun `a persisted multi-expanded state collapses to one on the next open`() {
        // Before the accordion, several rows could be open at once and that state was persisted.
        val state = AudioQueueExpansion.toggleSection(
            key = keys[2],
            expanded = true,
            liveKeys = keys,
            expandedSections = setOf(keys[0], keys[1]),
            collapsedSections = emptySet(),
        )

        assertEquals(setOf(keys[2]), state.expanded)
        assertEquals(setOf(keys[0], keys[1]), state.collapsed)
    }

    @Test
    fun `an untouched current row opens when nothing is explicitly open`() {
        assertTrue(AudioQueueExpansion.followsCurrentTrack(emptySet(), keys[0], keys[0]))
        assertFalse(AudioQueueExpansion.followsCurrentTrack(emptySet(), keys[1], keys[0]))
    }

    @Test
    fun `a row added after the accordion is in use cannot open itself by becoming current`() {
        val opened = AudioQueueExpansion.toggleSection(
            key = keys[0],
            expanded = true,
            liveKeys = keys,
            expandedSections = emptySet(),
            collapsedSections = emptySet(),
        )
        val newWorkKey = "4|work-4"

        assertFalse(
            AudioQueueExpansion.followsCurrentTrack(opened.expanded, newWorkKey, newWorkKey),
        )
    }

    @Test
    fun `stale collapsed keys are kept until the pruning pass removes them`() {
        val state = AudioQueueExpansion.toggleSection(
            key = keys[0],
            expanded = true,
            liveKeys = keys,
            expandedSections = emptySet(),
            collapsedSections = setOf("99|gone"),
        )

        assertTrue("99|gone" in state.collapsed)
        assertTrue(keys[1] in state.collapsed)
    }

    @Test
    fun `opening a folder closes the other folders in the same work`() {
        val folderKeys = listOf("1|work-1|ch-1", "1|work-1|ch-2", "1|work-1|ch-3")
        val state = AudioQueueExpansion.toggleFolder(
            key = folderKeys[1],
            expanded = true,
            liveKeysInWork = folderKeys,
            expandedFolders = setOf(folderKeys[0]),
            collapsedFolders = emptySet(),
        )

        assertEquals(setOf(folderKeys[1]), state.expanded)
        assertEquals(setOf(folderKeys[0], folderKeys[2]), state.collapsed)
    }

    @Test
    fun `a folder in another work keeps its state`() {
        val folderKeysInWork = listOf("1|work-1|ch-1", "1|work-1|ch-2")
        val otherWorkFolder = "2|work-2|ch-1"
        val state = AudioQueueExpansion.toggleFolder(
            key = folderKeysInWork[0],
            expanded = true,
            liveKeysInWork = folderKeysInWork,
            expandedFolders = setOf(otherWorkFolder),
            collapsedFolders = emptySet(),
        )

        assertTrue(otherWorkFolder in state.expanded)
        assertEquals(setOf(folderKeysInWork[0], otherWorkFolder), state.expanded)
        assertEquals(setOf(folderKeysInWork[1]), state.collapsed)
    }

    @Test
    fun `closing a folder does not reopen the work's other folders`() {
        val folderKeys = listOf("1|work-1|ch-1", "1|work-1|ch-2")
        val state = AudioQueueExpansion.toggleFolder(
            key = folderKeys[0],
            expanded = false,
            liveKeysInWork = folderKeys,
            expandedFolders = setOf(folderKeys[0], folderKeys[1]),
            collapsedFolders = emptySet(),
        )

        assertEquals(setOf(folderKeys[1]), state.expanded)
        assertEquals(setOf(folderKeys[0]), state.collapsed)
    }
}
