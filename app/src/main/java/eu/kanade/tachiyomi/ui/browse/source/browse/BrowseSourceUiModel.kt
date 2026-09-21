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
    data class Header(
        /**
         * Which list this separator belongs to, as counted by the reader's own choices. See
         * [Item.listGeneration]; the screen reads it off the first row it is handed.
         */
        val bucket: DateHeaderBucket,
        val listGeneration: Long = 0L,
    ) : BrowseSourceUiModel

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
        /**
         * Which list this row belongs to, as counted by the reader's own choices.
         *
         * It rides on the rows themselves so the screen can tell one list from the next at the
         * moment the rows are on screen - see [presentedListGeneration]. Reading it from anywhere
         * earlier would act before the rows arrive.
         */
        val listGeneration: Long = 0L,
    ) : BrowseSourceUiModel
}
