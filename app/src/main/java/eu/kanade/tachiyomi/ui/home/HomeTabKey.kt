package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.ui.browse.source.local.LocalSourceTab
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab

/**
 * Stable identifiers for the home tabs, used to remember the last tab across process death.
 *
 * Voyager's tab state lives in `rememberSaveable` and is only restored when the system kept the
 * saved instance state. Coming back from the reader can rebuild MainActivity with no saved state
 * (reclaimed process, REORDER_TO_FRONT onto a destroyed instance), in which case HomeScreen used
 * to fall back to the library tab and the user landed on the shelf instead of the tab they came
 * from. Persisting the tab id makes that fallback deterministic.
 *
 * Kept free of Android/Compose types so the mapping can be exercised by a plain JVM unit test.
 */
internal object HomeTabKey {

    const val LIBRARY = "library"
    const val UPDATES = "updates"
    const val LOCAL = "local"
    const val HISTORY = "history"
    const val MORE = "more"

    /** The tab the app starts on when there is no remembered tab. */
    const val DEFAULT = LIBRARY

    /** Voyager's base tab type: TabNavigator.current is typed to this, not our sub-interface. */
    fun of(tab: cafe.adriel.voyager.navigator.tab.Tab): String = when (tab) {
        LibraryTab -> LIBRARY
        UpdatesTab -> UPDATES
        LocalSourceTab -> LOCAL
        HistoryTab -> HISTORY
        MoreTab -> MORE
        else -> DEFAULT
    }

    /** Unknown or stale values fall back to the default instead of crashing a cold start. */
    fun toTab(key: String): cafe.adriel.voyager.navigator.tab.Tab = when (key) {
        LIBRARY -> LibraryTab
        UPDATES -> UpdatesTab
        LOCAL -> LocalSourceTab
        HISTORY -> HistoryTab
        MORE -> MoreTab
        else -> LibraryTab
    }
}
