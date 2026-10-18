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
        val stored = runCatching {
            json.decodeFromString<List<AudioPlayItem>>(raw)
        }.getOrDefault(emptyList())
        // The queue is presented as work sections with folder rows. Grouping it here keeps the
        // stored order and the visible order the same, including for queues written before the
        // screen grouped by work. The equality check makes this a one-time migration in practice.
        val grouped = groupByWorkAndFolder(stored)
        if (grouped != stored) save(grouped)
        return grouped
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
            save(groupByWorkAndFolder(current))
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
     * Queues every [items] track (deduplicated by url) at the front, preserving their order. The
     * whole work they belong to moves to the front with them, and the new folder is placed before
     * the work's existing folders. Other works keep their relative order.
     *
     * Returns the number of requested tracks, or 0 when the queue did not change.
     */
    @Synchronized
    fun addAll(items: List<AudioPlayItem>): Int {
        if (items.isEmpty()) return 0
        val requested = items
            .filter { it.mediaStreamUrl.isNotBlank() }
            .distinctBy { it.mediaStreamUrl }
        if (requested.isEmpty()) return 0
        val requestedUrls = requested.mapTo(hashSetOf()) { it.mediaStreamUrl }
        val current = load()
        val next = groupByWorkAndFolder(
            requested + current.filterNot { it.mediaStreamUrl in requestedUrls },
        )
        if (next == current) return 0
        save(next)
        return requested.size
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

/**
 * Keeps every work's tracks together, and every folder's tracks together inside that work.
 *
 * The order is stable: works appear in the order they first appear, folders in the order they
 * first appear inside their work, and tracks keep their order inside a folder. This is what lets
 * the queue screen show one section per work without lying about playback order.
 */
internal fun groupByWorkAndFolder(tracks: List<AudioPlayItem>): List<AudioPlayItem> {
    if (tracks.size < 2) return tracks
    val byWork = linkedMapOf<String, MutableList<AudioPlayItem>>()
    tracks.forEach { item ->
        byWork.getOrPut(item.workKey()) { mutableListOf() }.add(item)
    }
    val grouped = ArrayList<AudioPlayItem>(tracks.size)
    byWork.values.forEach { workTracks ->
        val byFolder = linkedMapOf<String, MutableList<AudioPlayItem>>()
        workTracks.forEach { item ->
            byFolder.getOrPut(item.folderPath) { mutableListOf() }.add(item)
        }
        byFolder.values.forEach { folderTracks -> grouped.addAll(folderTracks) }
    }
    return grouped
}

/** Rebuilds the work a group was built from, without having to reach for one of its tracks. */
fun AudioPlaylistGroup.toWorkSnapshot(): Work = Work(
    id = workId,
    title = workTitle,
    name = circleName,
    mainCoverUrl = coverUrl,
)
