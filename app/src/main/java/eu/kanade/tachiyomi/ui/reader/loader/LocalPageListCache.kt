package eu.kanade.tachiyomi.ui.reader.loader

import java.util.LinkedHashMap

/**
 * Small in-memory cache of local archive page lists (sorted image entry names), keyed by file
 * identity. Re-opening a chapter or preloading an adjacent one reuses the cached names instead
 * of re-scanning the whole archive, which is noticeable for large CBZ/ZIP files. An entry is
 * invalidated as soon as the file size or modification time changes, and the cache evicts
 * least-recently-used entries to bound memory.
 */
internal object LocalPageListCache {

    private const val MAX_CACHED_ARCHIVES = 16

    private data class ArchiveKey(
        val uri: String,
        val size: Long,
        val lastModified: Long,
    )

    private val archivePages = object : LinkedHashMap<ArchiveKey, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ArchiveKey, List<String>>): Boolean {
            return size > MAX_CACHED_ARCHIVES
        }
    }

    /**
     * Returns the cached sorted image entry names for the archive identified by [uri], [size]
     * and [lastModified], computing them with [scan] on a cache miss.
     */
    @Synchronized
    fun archivePageNames(
        uri: String,
        size: Long,
        lastModified: Long,
        scan: () -> List<String>,
    ): List<String> {
        val key = ArchiveKey(uri, size, lastModified)
        archivePages[key]?.let { return it }
        return scan().also { archivePages[key] = it }
    }
}
