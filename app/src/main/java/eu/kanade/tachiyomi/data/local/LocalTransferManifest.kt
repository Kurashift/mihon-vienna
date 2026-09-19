package eu.kanade.tachiyomi.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One target manga with the sources that belong to it, for a grouped import. */
@Serializable
internal data class PersistedTransferGroup(
    val targetMangaId: Long,
    val uris: List<String>,
)

/**
 * The complete work order for one [LocalChapterTransferJob], kept in a file rather than in the
 * work request itself.
 *
 * WorkManager refuses input data over 10 KB once serialized, and the job's payload *is* the whole
 * list of picked sources or chapter ids: a folder import of a few dozen books with long SAF URIs,
 * or a move of a thousand chapters, exceeds that on its own. Only the file name travels in the
 * request; the job reads the rest from here.
 */
@Serializable
internal data class PersistedTransferRequest(
    val targetMangaId: Long = -1L,
    val isMove: Boolean = false,
    val uris: List<String> = emptyList(),
    val chapterIds: List<Long> = emptyList(),
    val groups: List<PersistedTransferGroup> = emptyList(),
    val folderOutput: String = LocalChapterTransferService.FolderOutput.DIRECTORY.name,
    val deleteSource: Boolean = false,
)

internal object LocalTransferManifest {

    fun encode(request: PersistedTransferRequest): String = Json.encodeToString(request)

    fun decode(value: String): PersistedTransferRequest {
        val request = Json.decodeFromString<PersistedTransferRequest>(value)
        return request.copy(
            uris = request.uris.filter(String::isNotBlank),
            groups = request.groups
                .map { it.copy(uris = it.uris.filter(String::isNotBlank)) }
                .filter { it.targetMangaId >= 0L && it.uris.isNotEmpty() },
        )
    }
}
