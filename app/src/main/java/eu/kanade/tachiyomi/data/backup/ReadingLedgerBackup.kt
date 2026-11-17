package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupReadingLedger
import eu.kanade.tachiyomi.data.backup.models.toBackupReadingRecord
import tachiyomi.domain.history.model.PermanentReadingRecord
import tachiyomi.domain.history.repository.HistoryRepository
import java.util.UUID

/** Backup snapshots use absolute durations; only the core import transaction merges them. */
class ReadingLedgerBackup(private val repository: HistoryRepository) {
    suspend fun export(): BackupReadingLedger = BackupReadingLedger(
        schemaVersion = BackupReadingLedger.SCHEMA_VERSION,
        records = repository.exportPermanentRecords().map { it.toBackupReadingRecord() },
    )

    suspend fun restore(ledger: BackupReadingLedger) {
        ledger.validate()
        repository.importPermanentRecords(ledger.records.map { it.toPermanentRecord() })
    }

    suspend fun importLegacy(records: List<PermanentReadingRecord>) {
        repository.importPermanentRecords(records, legacyMode = true)
    }

    suspend fun restoreContext(seedChapterStates: Boolean = false): ReadingRestoreContext = ReadingRestoreContext(
        repository,
        repository.exportPermanentRecords(),
        seedChapterStates,
    )
}

/** Restore must not manufacture a new read/unread command from legacy chapter metadata. */
class ReadingRestoreContext(
    private val repository: HistoryRepository,
    records: List<PermanentReadingRecord>,
    private val seedChapterStates: Boolean = false,
) {
    private val recordsByLocator = buildMap {
        records.forEach { record ->
            put(Triple(record.sourceId, record.mangaUrl, record.chapterUrl), record)
            record.locationAliases.forEach { locator ->
                put(Triple(locator.sourceId, locator.mangaUrl, locator.chapterUrl), record)
            }
        }
    }

    fun hasExplicitUnread(sourceId: Long, mangaUrl: String, chapterUrl: String): Boolean {
        val record = recordsByLocator[Triple(sourceId, mangaUrl, chapterUrl)] ?: return false
        return record.stateVersion > 0 && !record.completed
    }

    suspend fun seedCurrentChapterStates(manga: BackupManga) {
        if (seedChapterStates) {
            repository.importPermanentRecords(manga.toLegacyReadingRecords(includeHistory = false), legacyMode = true)
        }
    }

    suspend fun <T> withoutStateChanges(block: suspend () -> T): T =
        repository.withoutReadingStateChanges(block)
}

/** Legacy locators are deterministic; the core resolves them to any existing canonical identity. */
internal fun BackupManga.toLegacyReadingRecords(includeHistory: Boolean = true): List<PermanentReadingRecord> {
    val chaptersByUrl = chapters.associateBy { it.url }
    val historyByUrl = if (includeHistory) history.groupBy { it.url } else emptyMap()
    val urls = chaptersByUrl.keys + historyByUrl.keys
    val workKey = legacyIdentity("work", source.toString(), url)
    return urls.map { chapterUrl ->
        val chapter = chaptersByUrl[chapterUrl]
        val entries = historyByUrl[chapterUrl].orEmpty()
        val readAt = entries.maxOfOrNull { it.lastRead.coerceAtLeast(0) } ?: 0L
        PermanentReadingRecord(
            recordKey = legacyIdentity("chapter", source.toString(), url, chapterUrl),
            workKey = workKey,
            sourceId = source,
            mangaUrl = url,
            mangaTitle = title,
            chapterUrl = chapterUrl,
            chapterName = chapter?.name ?: chapterUrl,
            readAt = readAt,
            readDuration = entries.maxOfOrNull { it.readDuration.coerceAtLeast(0) } ?: 0L,
            hasReadActivity = (chapter?.lastPageRead ?: 0L) > 2L &&
                (entries.isNotEmpty() || (!includeHistory && chapter?.read == false)),
            completed = chapter?.read == true,
            stateVersion = 0,
            stateActor = "legacy",
            hidden = readAt == 0L,
        )
    }
}

private fun legacyIdentity(vararg components: String): String = UUID.nameUUIDFromBytes(
    components.joinToString(separator = "\u0000").toByteArray(Charsets.UTF_8),
).toString()
