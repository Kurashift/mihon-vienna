package eu.kanade.presentation.more.stats

import androidx.compose.runtime.Immutable
import eu.kanade.presentation.more.stats.data.StatsData

sealed interface StatsScreenState {
    @Immutable
    data object Loading : StatsScreenState

    @Immutable
    data class Success(
        val overview: StatsData.Overview = StatsData.Overview(),
        val localLibrary: StatsData.LocalLibrary = StatsData.LocalLibrary(),
        val library: StatsData.Library = StatsData.Library(),
        val downloads: StatsData.Downloads = StatsData.Downloads(),
        val trackers: StatsData.Trackers = StatsData.Trackers(),
    ) : StatsScreenState
}
