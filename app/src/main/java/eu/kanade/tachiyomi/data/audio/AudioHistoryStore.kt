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
    val durationMs: Long = 0,
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

    @Synchronized
    fun upsert(entry: AudioHistoryEntry) {
        val updated = load().toMutableList()
        updated.removeAll { it.item.mediaStreamUrl == entry.item.mediaStreamUrl }
        updated.add(0, entry)
        preferences.audioHistory.set(json.encodeToString(updated.take(MAX_ENTRIES)))
    }

    @Synchronized
    fun clear() {
        preferences.audioHistory.set("")
    }

    /** Removes the history entry whose track url matches [url]; returns true when removed. */
    @Synchronized
    fun remove(url: String): Boolean {
        val updated = load().toMutableList()
        val changed = updated.removeAll { it.item.mediaStreamUrl == url }
        if (changed) preferences.audioHistory.set(json.encodeToString(updated))
        return changed
    }

    @Synchronized
    fun removeWork(workId: Long, workTitle: String): Boolean {
        val updated = load().toMutableList()
        val changed = updated.removeAll { it.item.workId == workId && it.item.workTitle == workTitle }
        if (changed) preferences.audioHistory.set(json.encodeToString(updated))
        return changed
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
