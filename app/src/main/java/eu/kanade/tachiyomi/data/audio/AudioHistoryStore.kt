package eu.kanade.tachiyomi.data.audio

import eu.kanade.domain.base.BasePreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Serializable as JavaSerializable

/** A single playable audio track together with the work it belongs to. */
@Serializable
data class AudioPlayItem(
    val workId: Long = 0,
    val workTitle: String = "",
    val circleName: String = "",
    val coverUrl: String? = null,
    val trackTitle: String = "",
    val mediaStreamUrl: String = "",
    val subtitleUrl: String? = null,
    val subtitleFallbackUrl: String? = null,
    val durationMs: Long = 0,
    /** Slash-separated folder path inside the work, e.g. "mp3" or "第1章/本篇". */
    val folderPath: String = "",
) : JavaSerializable

@Serializable
data class AudioHistoryEntry(
    val item: AudioPlayItem,
    val positionMs: Long,
    val lastPlayedAt: Long,
)

data class AudioHistoryGroup(
    val key: String,
    val workId: Long,
    val workTitle: String,
    val circleName: String,
    val coverUrl: String?,
    val entries: List<AudioHistoryEntry>,
) {
    val latest: AudioHistoryEntry
        get() = entries.maxBy { it.lastPlayedAt }
}

/** Persists play history as a JSON list inside a single app preference. */
class AudioHistoryStore(
    private val preferences: BasePreferences,
    private val json: Json,
) {

    @Synchronized
    fun load(): List<AudioHistoryEntry> {
        val raw = preferences.audioHistory.get()
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString<List<AudioHistoryEntry>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun loadGrouped(): List<AudioHistoryGroup> {
        return load()
            .groupByTo(linkedMapOf()) { "${it.item.workId}\u0000${it.item.workTitle}" }
            .map { (key, entries) ->
                val first = entries.first().item
                AudioHistoryGroup(
                    key = key,
                    workId = first.workId,
                    workTitle = first.workTitle,
                    circleName = first.circleName,
                    coverUrl = first.coverUrl,
                    entries = entries.sortedByDescending { it.lastPlayedAt },
                )
            }
            .sortedByDescending { it.latest.lastPlayedAt }
    }

    /**
     * Where [item] should pick up from, or null when it has to start from the beginning.
     *
     * A track that was heard all the way through counts as finished and starts over: dropping the
     * listener back into the last seconds of a track they already finished is not what resuming is
     * for, and those few seconds are as likely to be silence or credits as anything else.
     */
    @Synchronized
    fun resumePositionMs(item: AudioPlayItem): Long? {
        val entry = load().firstOrNull { it.item.trackKey == item.trackKey } ?: return null
        val positionMs = entry.positionMs
        if (positionMs <= 0) return null
        // The stored item knows the duration the track had when it was last played; the current one
        // is only a fallback, for works whose listing never carried a duration.
        val durationMs = item.durationMs.takeIf { it > 0 } ?: entry.item.durationMs
        if (durationMs <= 0) return positionMs
        val isFinished = positionMs >= durationMs - FINISHED_TAIL_MS ||
            positionMs >= durationMs * FINISHED_RATIO
        if (isFinished) return null
        return positionMs.coerceIn(0, durationMs)
    }

    /**
     * Remembers where [entry]'s track was left.
     *
     * A track keeps a single record, looked up by its [AudioPlayItem.trackKey] rather than by its
     * stream URL: listening to a track again, or to another encoding of it after the audio quality
     * was switched, replaces the record instead of adding a second one.
     */
    @Synchronized
    fun upsert(entry: AudioHistoryEntry) {
        val updated = load().toMutableList()
        updated.removeAll { it.item.trackKey == entry.item.trackKey }
        updated.add(0, entry)
        preferences.audioHistory.set(json.encodeToString(updated.take(MAX_ENTRIES)))
    }

    @Synchronized
    fun clear() {
        preferences.audioHistory.set("")
    }

    @Synchronized
    fun removeWork(workId: Long, workTitle: String): Boolean {
        val updated = load().toMutableList()
        val changed = updated.removeAll { it.item.workId == workId && it.item.workTitle == workTitle }
        if (changed) preferences.audioHistory.set(json.encodeToString(updated))
        return changed
    }

    private companion object {
        // One entry serialises to roughly 400–600 bytes: the work and track titles, the stream and
        // subtitle addresses and the folder path dominate. The whole history is a single preference
        // string read into memory in one go, so the cap is what keeps it bounded however many
        // different tracks get played; 500 entries lands in the region of 200–300 KB.
        const val MAX_ENTRIES = 500

        /** How close to the end still counts as "heard all the way through". */
        const val FINISHED_TAIL_MS = 30_000L
        const val FINISHED_RATIO = 0.95
    }
}
