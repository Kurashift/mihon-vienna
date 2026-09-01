package eu.kanade.presentation.audio.components

import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import eu.kanade.tachiyomi.data.audio.AudioPlaylistGroup

/** A row in a flattened audio queue: either a collapsible folder header or a track. */
sealed interface AudioQueueRow {
    val key: String

    /** [trackUrls] is the flat selection unit for this folder, so long-pressing it selects them all. */
    data class Folder(val path: String, val trackCount: Int, val trackUrls: List<String>) : AudioQueueRow {
        override val key: String get() = "folder:$path"
    }

    data class Track(val item: AudioPlayItem, val number: Int) : AudioQueueRow {
        override val key: String get() = "track:${item.mediaStreamUrl}"
    }
}

/**
 * Flattens [tracks] into folder headers and track rows, collapsing the folders that are not in
 * [expandedFolders].
 *
 * Numbers are assigned against the position in [tracks] before grouping, so they stay unique and
 * continuous across folders instead of restarting at 1 inside each of them.
 */
fun buildAudioQueueRows(
    tracks: List<AudioPlayItem>,
    expandedFolders: Set<String>,
): List<AudioQueueRow> {
    val byFolder = tracks
        .mapIndexed { index, item -> (index + 1) to item }
        .groupBy { (_, item) -> item.folderPath }

    return buildList(tracks.size) {
        byFolder.forEach { (path, numbered) ->
            if (path.isBlank()) {
                numbered.forEach { (number, item) -> add(AudioQueueRow.Track(item, number)) }
            } else {
                add(
                    AudioQueueRow.Folder(
                        path = path,
                        trackCount = numbered.size,
                        trackUrls = numbered.map { (_, item) -> item.mediaStreamUrl },
                    ),
                )
                if (path in expandedFolders) {
                    numbered.forEach { (number, item) -> add(AudioQueueRow.Track(item, number)) }
                }
            }
        }
    }
}

/** The work currently playing, when it is part of [groups]. */
fun currentWorkKey(
    groups: List<AudioPlaylistGroup>,
    currentItem: AudioPlayItem?,
): String? = currentItem?.let { item ->
    groups.firstOrNull { it.workId == item.workId && it.workTitle == item.workTitle }?.key
}

/**
 * Folders that should start expanded so the playing track is not hidden. Callers apply this once
 * and then leave the expansion state to the user.
 */
fun initialExpandedFolders(currentItem: AudioPlayItem?): Set<String> =
    currentItem?.folderPath
        ?.takeIf { it.isNotBlank() }
        ?.let(::setOf)
        ?: emptySet()
