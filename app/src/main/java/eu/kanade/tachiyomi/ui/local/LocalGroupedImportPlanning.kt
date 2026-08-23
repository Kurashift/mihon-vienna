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
