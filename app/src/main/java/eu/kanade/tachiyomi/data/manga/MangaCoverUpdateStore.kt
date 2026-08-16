package eu.kanade.tachiyomi.data.manga

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MangaCoverUpdate(
    val url: String?,
    val lastModified: Long,
)

/** Lightweight UI overlay for local covers changed during this app process. */
class MangaCoverUpdateStore {

    private val _covers = MutableStateFlow<Map<Long, MangaCoverUpdate>>(emptyMap())
    val covers: StateFlow<Map<Long, MangaCoverUpdate>> = _covers.asStateFlow()

    fun publish(mangaId: Long, cover: MangaCoverUpdate) {
        _covers.value = _covers.value + (mangaId to cover)
    }
}
