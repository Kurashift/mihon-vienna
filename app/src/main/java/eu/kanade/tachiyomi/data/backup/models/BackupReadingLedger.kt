package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.history.model.PermanentReadingRecord
import tachiyomi.domain.history.model.ReadingRecordLocator
import java.io.IOException

/** Nullable at the top level: absent means legacy, present-empty is authoritative. */
@Serializable
data class BackupReadingLedger(
    @ProtoNumber(1) val schemaVersion: Int,
    @ProtoNumber(2) val records: List<BackupReadingRecord> = emptyList(),
) {
    fun validate() {
        if (schemaVersion != SCHEMA_VERSION) throw IOException("Unsupported reading statistics version: $schemaVersion")
        val keys = HashSet<String>(records.size)
        records.forEach { record ->
            if (record.recordKey.isBlank() || record.workKey.isBlank() || record.mangaUrl.isBlank() ||
                record.chapterUrl.isBlank() || (record.stateVersion > 0 && record.stateActor.isBlank()) ||
                record.readAt < 0 ||
                record.readDuration < 0 || record.stateVersion < 0 || !keys.add(record.recordKey) ||
                record.recordAliases.any { it.isBlank() } ||
                record.locationAliases.any { it.mangaUrl.isBlank() || it.chapterUrl.isBlank() }
            ) {
                throw IOException("Invalid permanent reading record: " + record.recordKey)
            }
        }
        val keyOwners = HashMap<String, String>()
        records.forEach { record ->
            (record.recordAliases + record.recordKey).forEach { key ->
                val owner = keyOwners.putIfAbsent(key, record.recordKey)
                if (owner != null &&
                    owner != record.recordKey
                ) {
                    throw IOException("Conflicting reading record alias: $key")
                }
            }
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class BackupReadingRecord(
    @ProtoNumber(1) val recordKey: String,
    @ProtoNumber(2) val workKey: String,
    @ProtoNumber(3) val sourceId: Long,
    @ProtoNumber(4) val mangaUrl: String,
    @ProtoNumber(5) val mangaTitle: String,
    @ProtoNumber(6) val chapterUrl: String,
    @ProtoNumber(7) val chapterName: String,
    @ProtoNumber(8) val readAt: Long,
    @ProtoNumber(9) val readDuration: Long,
    @ProtoNumber(10) val hasReadActivity: Boolean,
    @ProtoNumber(11) val completed: Boolean,
    @ProtoNumber(12) val stateVersion: Long,
    @ProtoNumber(13) val stateActor: String,
    @ProtoNumber(14) val hidden: Boolean,
    @ProtoNumber(15) val recordAliases: List<String> = emptyList(),
    @ProtoNumber(16) val locationAliases: List<BackupReadingLocator> = emptyList(),
) {
    fun toPermanentRecord() = PermanentReadingRecord(
        recordKey, workKey, sourceId, mangaUrl, mangaTitle, chapterUrl, chapterName,
        readAt, readDuration, hasReadActivity, completed, stateVersion, stateActor, hidden,
        recordAliases, locationAliases.map { ReadingRecordLocator(it.sourceId, it.mangaUrl, it.chapterUrl) },
    )
}

fun PermanentReadingRecord.toBackupReadingRecord() = BackupReadingRecord(
    recordKey, workKey, sourceId, mangaUrl, mangaTitle, chapterUrl, chapterName,
    readAt, readDuration, hasReadActivity, completed, stateVersion, stateActor, hidden,
    recordAliases, locationAliases.map { BackupReadingLocator(it.sourceId, it.mangaUrl, it.chapterUrl) },
)

@Serializable
data class BackupReadingLocator(
    @ProtoNumber(1) val sourceId: Long,
    @ProtoNumber(2) val mangaUrl: String,
    @ProtoNumber(3) val chapterUrl: String,
)
