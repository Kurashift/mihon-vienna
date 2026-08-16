package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.runtime.Immutable
import tachiyomi.domain.manga.model.Manga

@Immutable
sealed interface BrowseSourceUiModel {
    @Immutable
    data class Header(val timestamp: Long) : BrowseSourceUiModel

    /**
     * Immutable list entry. Progress and last-read state are intentionally NOT stored here:
     * they change as the user reads and are resolved at render time from the current
     * [ProgressContext], so a progress change recomposes only the affected card instead of
     * rebuilding the whole paged list.
     */
    @Immutable
    data class Item(
        val manga: Manga,
        val matchedChapter: String? = null,
        val latestChapterAddedAt: Long = 0L,
    ) : BrowseSourceUiModel
}
