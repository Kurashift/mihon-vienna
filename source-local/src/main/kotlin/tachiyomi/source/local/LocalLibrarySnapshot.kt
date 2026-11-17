package tachiyomi.source.local

/** A projection of existing private indexes. Null means unknown, never an empty library. */
data class LocalLibrarySnapshot(
    val baseUri: String?,
    val chapterCounts: Map<String, Long>?,
    val chapterUrlsByMangaUrl: Map<String, Set<String>>,
) {
    val workCount: Long? get() = chapterCounts?.size?.toLong()
    val chapterCount: Long?
        get() = chapterCounts?.values?.takeIf { counts -> counts.all { it >= 0 } }?.sum()
}

/** Preserve full filenames, including extensions and the folder-level single-chapter sentinel. */
internal fun localLibraryChapterUrls(
    mangaUrl: String,
    chapterCount: Long,
    listingModifiedAt: Long,
    indexedModifiedAt: Long?,
    fileNames: Set<String>?,
): Set<String>? {
    if (fileNames == null || chapterCount < 0 || fileNames.size.toLong() != chapterCount) return null
    if (listingModifiedAt < 0 || listingModifiedAt != indexedModifiedAt) return null
    if (fileNames.any { it.isEmpty() || '/' in it }) return null
    return fileNames.mapTo(linkedSetOf()) { name -> "$mangaUrl/$name" }
}
