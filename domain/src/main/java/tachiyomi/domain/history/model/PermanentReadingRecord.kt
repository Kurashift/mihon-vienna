package tachiyomi.domain.history.model

/** One permanent logical chapter. Its current file or database chapter may no longer exist. */
data class PermanentReadingRecord(
    val recordKey: String,
    val workKey: String,
    val sourceId: Long,
    val mangaUrl: String,
    val mangaTitle: String,
    val chapterUrl: String,
    val chapterName: String,
    val readAt: Long,
    val readDuration: Long,
    val hasReadActivity: Boolean,
    val completed: Boolean,
    val stateVersion: Long,
    val stateActor: String,
    val hidden: Boolean,
    val recordAliases: List<String> = emptyList(),
    val locationAliases: List<ReadingRecordLocator> = emptyList(),
)

data class ReadingOverview(
    val readWorkCount: Long,
    val completedWorkCount: Long,
    val readChapterCount: Long,
    val totalReadDuration: Long,
)

/** Only visibility is restored; reading time and changes made after hiding are never overwritten. */
data class HistoryVisibilityUndo(val entries: List<HistoryVisibilityState>)

data class HistoryVisibilityState(
    val historyId: Long,
    val hiddenVersion: Long,
)

data class ReadingRecordLocator(val sourceId: Long, val mangaUrl: String, val chapterUrl: String)

/** Merge absolute backup values; an import is never a new reading session or a new status edit. */
fun PermanentReadingRecord.mergeImportedRecord(
    incoming: PermanentReadingRecord,
    legacyMode: Boolean = false,
): PermanentReadingRecord {
    val newerState = if (legacyMode) {
        stateVersion == 0L
    } else {
        incoming.stateVersion > stateVersion ||
            (incoming.stateVersion == stateVersion && incoming.stateActor > stateActor)
    }
    return copy(
        readAt = maxOf(readAt, incoming.readAt),
        readDuration = maxOf(readDuration, incoming.readDuration),
        hasReadActivity = hasReadActivity || incoming.hasReadActivity,
        completed = if (newerState) incoming.completed else completed,
        stateVersion = if (newerState) incoming.stateVersion else stateVersion,
        stateActor = if (newerState) incoming.stateActor else stateActor,
        hidden = if (readAt >= incoming.readAt) hidden else incoming.hidden,
        recordAliases = (recordAliases + incoming.recordAliases + incoming.recordKey).distinct().filter {
            it !=
                recordKey
        },
        locationAliases = (
            locationAliases + incoming.locationAliases +
                ReadingRecordLocator(incoming.sourceId, incoming.mangaUrl, incoming.chapterUrl)
            ).distinct(),
    )
}
