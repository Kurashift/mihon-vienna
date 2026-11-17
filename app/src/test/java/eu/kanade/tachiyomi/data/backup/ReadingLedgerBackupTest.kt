package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupReadingLedger
import eu.kanade.tachiyomi.data.backup.models.toBackupReadingRecord
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.history.model.PermanentReadingRecord
import tachiyomi.domain.history.model.ReadingRecordLocator
import tachiyomi.domain.history.model.mergeImportedRecord
import tachiyomi.domain.history.repository.HistoryRepository
import java.io.IOException

@OptIn(ExperimentalSerializationApi::class)
class ReadingLedgerBackupTest {
    @Test
    fun `export includes hidden detached and explicitly unread records without filtering`() = runTest {
        val records = listOf(record(), record().copy(recordKey = "unread", completed = false, hidden = true))
        val repository = mockk<HistoryRepository> { coEvery { exportPermanentRecords() } returns records }
        val ledger = ReadingLedgerBackup(repository).export()
        ledger.validate()
        assertEquals(records, ledger.records.map { it.toPermanentRecord() })
    }

    @Test
    fun `wire roundtrip preserves unread tombstones time and identity aliases`() {
        val expected = record().copy(
            completed = false,
            recordAliases = listOf("old-record"),
            locationAliases = listOf(ReadingRecordLocator(0, "old-work", "old-work/old.cbz")),
        )
        val ledger = BackupReadingLedger(1, listOf(expected.toBackupReadingRecord()))
        val bytes = ProtoBuf.encodeToByteArray(BackupReadingLedger.serializer(), ledger)
        val actual = ProtoBuf.decodeFromByteArray(BackupReadingLedger.serializer(), bytes)
        actual.validate()
        assertEquals(expected, actual.records.single().toPermanentRecord())
    }

    @Test
    fun `migration baseline without actor roundtrips but a versioned state requires an actor`() {
        val baseline = record().copy(stateVersion = 0, stateActor = "")
        val ledger = BackupReadingLedger(1, listOf(baseline.toBackupReadingRecord()))
        ledger.validate()
        val bytes = ProtoBuf.encodeToByteArray(BackupReadingLedger.serializer(), ledger)
        assertEquals(
            baseline,
            ProtoBuf.decodeFromByteArray(BackupReadingLedger.serializer(), bytes).records.single().toPermanentRecord(),
        )
        assertThrows<IOException> {
            BackupReadingLedger(1, listOf(baseline.copy(stateVersion = 1).toBackupReadingRecord())).validate()
        }
    }

    @Test
    fun `repeated new format restore delegates absolute values without summing`() = runTest {
        val repository = mockk<HistoryRepository>(relaxed = true)
        val records = listOf(record().copy(completed = false, stateVersion = 7))
        val backup = ReadingLedgerBackup(repository)
        val ledger = BackupReadingLedger(1, records.map { it.toBackupReadingRecord() })
        repeat(2) { backup.restore(ledger) }
        coVerify(exactly = 2) { repository.importPermanentRecords(records, legacyMode = false) }
    }

    @Test
    fun `actual core merge keeps newer unread and repeated restore never doubles duration`() {
        val local = record().copy(completed = false, stateVersion = 7, readDuration = 2000)
        val older = record().copy(completed = true, stateVersion = 3, readDuration = 1000)
        val first = local.mergeImportedRecord(older)
        val repeated = first.mergeImportedRecord(older)
        assertFalse(first.completed)
        assertEquals(2000L, first.readDuration)
        assertEquals(first, repeated)
        assertFalse(local.mergeImportedRecord(older.copy(stateVersion = 0), legacyMode = true).completed)
    }

    @Test
    fun `legacy input goes through the core legacy merge policy`() = runTest {
        val repository = mockk<HistoryRepository>(relaxed = true)
        val records = listOf(record().copy(stateVersion = 0, stateActor = "legacy"))
        ReadingLedgerBackup(repository).importLegacy(records)
        coVerify(exactly = 1) { repository.importPermanentRecords(records, legacyMode = true) }
    }

    @Test
    fun `invalid and unsupported envelopes fail before touching the repository`() = runTest {
        val repository = mockk<HistoryRepository>(relaxed = true)
        val backup = ReadingLedgerBackup(repository)
        val valid = record().toBackupReadingRecord()
        listOf(
            BackupReadingLedger(2),
            BackupReadingLedger(1, listOf(valid.copy(readDuration = -1))),
            BackupReadingLedger(1, listOf(valid, valid)),
        ).forEach { ledger ->
            assertThrows<IOException> { backup.restore(ledger) }
        }
        coVerify(exactly = 0) { repository.importPermanentRecords(any(), any()) }
    }

    @Test
    fun `explicit unread is protected from old read while an unversioned baseline is not`() {
        val repository = mockk<HistoryRepository>()
        val unread = record().copy(completed = false, stateVersion = 4)
        val context = ReadingRestoreContext(repository, listOf(unread))
        assertTrue(context.hasExplicitUnread(unread.sourceId, unread.mangaUrl, unread.chapterUrl))
        assertFalse(context.hasExplicitUnread(unread.sourceId, unread.mangaUrl, "another"))
        assertFalse(
            ReadingRestoreContext(repository, listOf(unread.copy(stateVersion = 0)))
                .hasExplicitUnread(unread.sourceId, unread.mangaUrl, unread.chapterUrl),
        )
    }

    @Test
    fun `legacy records retain zero state roster and history without current chapters`() {
        val manga = BackupManga(
            source = 0,
            url = "work",
            title = "Work",
            chapters = listOf(BackupChapter("work/a.cbz", "A", read = false)),
            history = listOf(
                BackupHistory("work/removed.cbz", 0, 30),
                BackupHistory("work/removed.cbz", 0, 50),
            ),
        )
        val records = manga.toLegacyReadingRecords()
        assertEquals(2, records.size)
        assertTrue(records.all { it.hidden && it.stateVersion == 0L && !it.completed })
        assertEquals(50L, records.single { it.chapterUrl == "work/removed.cbz" }.readDuration)
        assertEquals(records, manga.toLegacyReadingRecords())
    }

    @Test
    fun `history backup and restore can be selected without library entries`() {
        val backup = BackupOptions(
            libraryEntries = false,
            categories = false,
            appSettings = false,
            extensionStores = false,
            sourceSettings = false,
            history = true,
        )
        assertTrue(backup.canCreate())
        val entry = BackupOptions.libraryOptions.single {
            it.getter(backup) &&
                it.getter(backup.copy(history = false)).not()
        }
        assertTrue(entry.enabled(backup))
        val restore = RestoreOptions(
            libraryEntries = false,
            categories = false,
            appSettings = false,
            extensionStores = false,
            sourceSettings = false,
            history = true,
        )
        assertTrue(restore.canRestore())
        assertEquals(restore, RestoreOptions.fromBooleanArray(restore.asBooleanArray()))
    }

    private fun record() = PermanentReadingRecord(
        recordKey = "record", workKey = "work", sourceId = 0, mangaUrl = "work", mangaTitle = "Work",
        chapterUrl = "work/chapter.cbz", chapterName = "Chapter", readAt = 500, readDuration = 1000,
        hasReadActivity = true, completed = true, stateVersion = 3, stateActor = "device", hidden = true,
    )
}
