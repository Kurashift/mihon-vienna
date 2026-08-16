package eu.kanade.tachiyomi.data.manga

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One-shot anchor for restoring the local-library viewport after a detail screen is popped.
 *
 * Only navigation that starts on the library list may update this value. Reader history and
 * random navigation are separate concepts and must not move the user's library viewport.
 */
class LibraryReturnAnchorStore {

    private val mangaId = MutableStateFlow<Long?>(null)

    val mangaIdToRestore: StateFlow<Long?> = mangaId

    fun remember(mangaId: Long) {
        if (mangaId > 0L) this.mangaId.value = mangaId
    }

    fun consume() {
        mangaId.value = null
    }
}
