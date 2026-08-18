package eu.kanade.tachiyomi.data.audio

import eu.kanade.domain.base.BasePreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AudioPlaylistGroup(
    val key: String,
    val workId: Long,
    val workTitle: String,
    val circleName: String,
    val coverUrl: String?,
    val tracks: List<AudioPlayItem>,
)

/** Persists the shared audio queue used by detail, browse, reader and quick-play surfaces. */
class AudioPlaylistStore(
    private val preferences: BasePreferences,
    private val json: Json,
) {

    @Synchronized
    fun load(): List<AudioPlayItem> {
        val raw = preferences.audioPlaylist.get()
        if (raw.isBlank()) {
            migrateIfNeeded()
            return emptyList()
        }
        if (preferences.audioPlaylistVersion.get() < PLAYLIST_VERSION) {
            // Old entries predate folderPath. Drop them once for a clean start.
            preferences.audioPlaylist.set("")
            preferences.audioPlaylistVersion.set(PLAYLIST_VERSION)
            return emptyList()
        }
        return runCatching { json.decodeFromString<List<AudioPlayItem>>(raw) }.getOrDefault(emptyList())
    }

    private fun migrateIfNeeded() {
        if (preferences.audioPlaylistVersion.get() < PLAYLIST_VERSION) {
            preferences.audioPlaylistVersion.set(PLAYLIST_VERSION)
        }
    }

    @Synchronized
    fun loadGrouped(): List<AudioPlaylistGroup> {
        return load()
            .groupByTo(linkedMapOf()) { it.workKey() }
            .map { (key, tracks) ->
                val first = tracks.first()
                AudioPlaylistGroup(
                    key = key,
                    workId = first.workId,
                    workTitle = first.workTitle,
                    circleName = first.circleName,
                    coverUrl = first.coverUrl,
                    tracks = tracks,
                )
            }
    }

    /** Adds [item] unless already present; returns true when it was added. */
    @Synchronized
    fun toggle(item: AudioPlayItem): Boolean {
        val current = load().toMutableList()
        val existingIndex = current.indexOfFirst { it.mediaStreamUrl == item.mediaStreamUrl }
        return if (existingIndex >= 0) {
            current.removeAt(existingIndex)
            save(current)
            false
        } else {
            current.add(0, item)
            save(current)
            true
        }
    }

    /** Removes the track with [url]; returns true when it was present. */
    @Synchronized
    fun remove(url: String): Boolean {
        val current = load().toMutableList()
        val changed = current.removeAll { it.mediaStreamUrl == url }
        if (changed) save(current)
        return changed
    }

    @Synchronized
    fun removeWork(workId: Long, workTitle: String): Boolean {
        val current = load().toMutableList()
        val changed = current.removeAll { it.workId == workId && it.workTitle == workTitle }
        if (changed) save(current)
        return changed
    }

    /**
     * Adds every [items] track (deduplicated by url) to the front of the list, preserving their
     * order. Returns the number of tracks actually added.
     */
    @Synchronized
    fun addAll(items: List<AudioPlayItem>): Int {
        if (items.isEmpty()) return 0
        val current = load().toMutableList()
        val existing = current.map { it.mediaStreamUrl }.toHashSet()
        val toAdd = items.filter { it.mediaStreamUrl !in existing }.distinctBy { it.mediaStreamUrl }
        if (toAdd.isEmpty()) return 0
        current.addAll(0, toAdd)
        save(current)
        return toAdd.size
    }

    /** Removes every track whose url is in [urls]; returns true when anything was removed. */
    @Synchronized
    fun removeAll(urls: Collection<String>): Boolean {
        if (urls.isEmpty()) return false
        val toRemove = urls.toHashSet()
        val current = load().toMutableList()
        val changed = current.removeAll { it.mediaStreamUrl in toRemove }
        if (changed) save(current)
        return changed
    }

    @Synchronized
    fun clear() {
        preferences.audioPlaylist.set("")
    }

    private fun save(items: List<AudioPlayItem>) {
        preferences.audioPlaylist.set(json.encodeToString(items))
    }

    private companion object {
        const val PLAYLIST_VERSION = 1
    }
}

private fun AudioPlayItem.workKey(): String = "$workId\u0000$workTitle"
