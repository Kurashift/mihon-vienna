package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.ui.browse.source.local.LocalSourceTab
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomeTabKeyTest {

    @Test
    fun `every home tab round-trips through its stable id`() {
        val tabs = listOf(LibraryTab, UpdatesTab, LocalSourceTab, HistoryTab, MoreTab)

        tabs.forEach { tab ->
            assertEquals(tab, HomeTabKey.toTab(HomeTabKey.of(tab)))
        }
    }

    @Test
    fun `ids are distinct`() {
        val ids = listOf(
            HomeTabKey.of(LibraryTab),
            HomeTabKey.of(UpdatesTab),
            HomeTabKey.of(LocalSourceTab),
            HomeTabKey.of(HistoryTab),
            HomeTabKey.of(MoreTab),
        )

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `an unknown persisted id falls back to the default tab`() {
        // A stale value from an older build must not crash a cold start.
        assertEquals(LibraryTab, HomeTabKey.toTab("some_removed_tab"))
        assertEquals(HomeTabKey.DEFAULT, HomeTabKey.of(HomeTabKey.toTab("any-unknown")))
    }

    @Test
    fun `the historical local id still resolves to the local tab`() {
        // Existing installs may hold the old default value "local".
        assertEquals(LocalSourceTab, HomeTabKey.toTab("local"))
    }

    @Test
    fun `the default is the library tab`() {
        assertEquals(LibraryTab, HomeTabKey.toTab(HomeTabKey.DEFAULT))
    }
}
