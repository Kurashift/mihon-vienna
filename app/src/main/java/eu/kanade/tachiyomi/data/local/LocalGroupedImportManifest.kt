package eu.kanade.tachiyomi.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class PersistedGroupedImport(
    val targetMangaId: Long,
    val uris: List<String>,
)

@Serializable
private data class PersistedGroupedImportManifest(
    val groups: List<PersistedGroupedImport>,
)

internal object LocalGroupedImportManifest {

    fun encode(groups: List<PersistedGroupedImport>): String {
        return Json.encodeToString(PersistedGroupedImportManifest(groups))
    }

    fun decode(value: String): List<PersistedGroupedImport> {
        return Json.decodeFromString<PersistedGroupedImportManifest>(value).groups
            .map { it.copy(uris = it.uris.filter(String::isNotBlank)) }
            .filter { it.targetMangaId >= 0L && it.uris.isNotEmpty() }
    }
}
