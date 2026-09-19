package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.runtime.Immutable
import tachiyomi.domain.manga.model.Manga

@Immutable
sealed interface BrowseSourceUiModel {
    /**
     * A separator between runs of entries under the date ordering.
     *
     * The bucket says whether it stands for one recent day or a whole older month; carrying it
     * rather than a formatted label keeps the formatting in the UI layer, where the user's date
     * format and locale are available.
     */
    @Immutable
    data class Header(val bucket: DateHeaderBucket) : BrowseSourceUiModel

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
    ) : BrowseSourceUiModel
}
