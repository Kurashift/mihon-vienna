package eu.kanade.tachiyomi.ui.browse.source.browse

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.model.MangaProgress
import tachiyomi.domain.manga.model.MangaProgressByMangaId
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Preference key prefix for the browse list reading filter, keyed by source id. */
private const val READING_FILTER_PREF_PREFIX = "browse_reading_filter_"

/**
 * The single source of truth for the local browse reading filter.
 *
 * The browse list and the random pickers have to stay inside the same pool, so they read the
 * same preference and apply the same predicate instead of each keeping a private copy that
 * can drift.
 */
internal object LocalReadingFilter {

    fun read(
        sourceId: Long,
        store: PreferenceStore = Injekt.get(),
    ): BrowseSourceViewModel.ReadingFilter {
        val raw = store
            .getString(READING_FILTER_PREF_PREFIX + sourceId, BrowseSourceViewModel.ReadingFilter.ALL.name)
            .get()
        return runCatching { BrowseSourceViewModel.ReadingFilter.valueOf(raw) }
            .getOrDefault(BrowseSourceViewModel.ReadingFilter.ALL)
    }

    fun matches(
        filter: BrowseSourceViewModel.ReadingFilter,
        progress: MangaProgress,
    ): Boolean = when (filter) {
        BrowseSourceViewModel.ReadingFilter.ALL -> true
        BrowseSourceViewModel.ReadingFilter.UNREAD -> !progress.hasFinished
        BrowseSourceViewModel.ReadingFilter.IN_PROGRESS -> progress.hasBeenRead && !progress.hasFinished
        BrowseSourceViewModel.ReadingFilter.FINISHED -> progress.hasFinished
    }

    /**
     * Ids eligible for a random pick among [progress].
     *
     * [progress] has to come from a query that joins chapters, so a manga whose files were
     * deleted leaves no row at all and can never be picked into an empty details page.
     */
    fun randomPickIds(
        progress: List<MangaProgressByMangaId>,
        filter: BrowseSourceViewModel.ReadingFilter,
    ): List<Long> = progress
        .filter { matches(filter, it.progress) }
        .map { it.mangaId }
}
