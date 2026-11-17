package eu.kanade.domain.chapter.interactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.PermanentReadingRecord
import tachiyomi.domain.history.model.mergeImportedRecord

class PermanentReadingRecordMergeTest {
    @Test
    fun `repeated backup imports are idempotent and preserve canonical identity`() {
        val backup = record(2, true, 150).copy(recordKey = "alias")
        val merged = record(1, false, 100).mergeImportedRecord(backup)
        assertEquals(merged, merged.mergeImportedRecord(backup))
        assertEquals(150L, merged.readDuration)
        assertEquals("chapter", merged.recordKey)
        assertEquals(listOf("alias"), merged.recordAliases)
    }

    @Test
    fun `older read state cannot revive a newer explicit unread`() {
        val merged = record(3, false, 100).mergeImportedRecord(record(2, true, 500))
        assertFalse(merged.completed)
        assertEquals(3L, merged.stateVersion)
        assertEquals(500L, merged.readDuration)
    }

    @Test
    fun `newer unread retracts completion but not accumulated duration`() {
        val merged = record(2, true, 500).mergeImportedRecord(record(3, false, 100))
        assertFalse(merged.completed)
        assertEquals(500L, merged.readDuration)
    }

    @Test
    fun `legacy read never overrides explicit unread`() {
        val merged = record(1, false, 100).mergeImportedRecord(record(0, true, 50), legacyMode = true)
        assertFalse(merged.completed)
        assertEquals(1L, merged.stateVersion)
    }

    @Test
    fun `concurrent state conflict has a deterministic actor tie break`() {
        val left = record(3, true, 0).copy(stateActor = "a")
        val right = record(3, false, 0).copy(stateActor = "b")
        assertFalse(left.mergeImportedRecord(right).completed)
        assertFalse(right.mergeImportedRecord(left).completed)
    }

    @Test
    fun `old visible backup does not undo a later history clear`() {
        val hidden = record(1, true, 50).copy(hidden = true)
        assertTrue(hidden.mergeImportedRecord(record(1, true, 50)).hidden)
        assertFalse(hidden.mergeImportedRecord(record(1, true, 60).copy(readAt = 200)).hidden)
    }

    @Test
    fun `manual completion followed by unread does not invent reading activity`() {
        val manual = record(1, true, 0).copy(hasReadActivity = false)
        val unread = manual.mergeImportedRecord(record(2, false, 0).copy(hasReadActivity = false))
        assertFalse(unread.completed)
        assertFalse(unread.hasReadActivity)
        assertEquals(0L, unread.readDuration)
    }

    private fun record(version: Long, completed: Boolean, duration: Long) = PermanentReadingRecord(
        "chapter", "work", 0, "work", "Work", "work/one.cbz", "One",
        100, duration, true, completed, version, "device", false,
    )
}
