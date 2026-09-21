package eu.kanade.tachiyomi.ui.local

import java.text.Normalizer
import java.util.Locale

internal fun localMangaDirectoryName(value: String): String {
    return value.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
}

internal fun localMangaDirectoryIdentity(value: String): String {
    return Normalizer.normalize(localMangaDirectoryName(value), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
}

internal fun localGroupedImportNameCollisionCount(names: List<String>): Int {
    return names
        .groupBy(::localMangaDirectoryIdentity)
        .count { (_, groupedNames) ->
            groupedNames
                .map { Normalizer.normalize(it.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT) }
                .distinct()
                .size > 1
        }
}

internal fun hasInvalidLocalGroupedImportName(names: List<String>): Boolean {
    return names.any { localMangaDirectoryName(it).isBlank() }
}

internal data class LocalGroupedImportTarget(
    val url: String,
    val exists: Boolean,
)

internal fun resolveLocalGroupedImportTarget(
    proposedName: String,
    existingUrls: List<String>,
): LocalGroupedImportTarget? {
    val proposedUrl = localMangaDirectoryName(proposedName)
    if (proposedUrl.isBlank()) return null
    existingUrls.firstOrNull { it == proposedUrl }?.let {
        return LocalGroupedImportTarget(it, exists = true)
    }
    val normalizedMatches = existingUrls
        .filter { localMangaDirectoryIdentity(it) == localMangaDirectoryIdentity(proposedUrl) }
        .distinct()
    return when (normalizedMatches.size) {
        0 -> LocalGroupedImportTarget(proposedUrl, exists = false)
        1 -> LocalGroupedImportTarget(normalizedMatches.single(), exists = true)
        else -> null
    }
}

/**
 * One picked source as the collection rules need it: what it is called, whether it is a folder,
 * and the first-level folders inside it that are themselves containers.
 *
 * Kept free of `Uri` and of the source itself so the rules below are plain decisions that can be
 * tested without a document provider.
 */
internal data class LocalImportSourceShape(
    val displayName: String,
    val isDirectory: Boolean,
    val groupNames: List<String>,
)

/**
 * The collections a picked batch contributes, by the names they are to be created or reused under,
 * in order and de-duplicated.
 *
 * A folder *is* a collection: the reader picked it because it holds works, so its name is the
 * collection's name. Several folders picked together are therefore several collections, each named
 * after its own folder - the same rule that already named the author folders of a 根目录/作者/本子
 * layout.
 *
 * This used to depend on whether the folder's children were themselves containers, which left the
 * most ordinary case out: a folder whose contents are directly the works (archives, or one folder
 * per work) has no groups, so it fell through to a manual name - and several such folders were
 * merged into one collection instead of becoming one each.
 *
 * A file contributes no name: it is one chapter of a collection the reader names, so the picker's
 * file mode keeps its manual target, and a batch holding one keeps it for the whole batch.
 */
internal fun localImportCollectionNames(sources: List<LocalImportSourceShape>): List<String> {
    if (!isLocalCollectionImport(sources)) return emptyList()
    return sources
        .flatMap { source -> source.groupNames.ifEmpty { listOf(source.displayName) } }
        .map(::localMangaDirectoryName)
        .distinct()
}

/**
 * Whether a batch is imported as collections rather than into one manually named target.
 *
 * Decided by the whole batch rather than per source, because a batch is imported through one target
 * choice: a file among the picks means the reader is assembling chapters under a name of their own,
 * and the folders that came along join that one target exactly as they always have.
 */
internal fun isLocalCollectionImport(sources: List<LocalImportSourceShape>): Boolean {
    return sources.isNotEmpty() && sources.all { it.isDirectory }
}
