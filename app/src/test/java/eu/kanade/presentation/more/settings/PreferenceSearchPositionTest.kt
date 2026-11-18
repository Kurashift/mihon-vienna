package eu.kanade.presentation.more.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreferenceSearchPositionTest {
    private fun group(title: String, enabled: Boolean, vararg rows: String) = Preference.PreferenceGroup(
        title = title,
        enabled = enabled,
        preferenceItems = rows.map { Preference.PreferenceItem.TextPreference(it) },
    )

    @Test
    fun `highlight follows rendered rows including headers and spacers`() {
        val items = listOf(
            group("Hidden", false, "Crop"),
            group("Webtoon", true, "Tap zones", "Crop"),
            Preference.PreferenceItem.TextPreference("Default mode"),
        )
        assertEquals(2, items.findHighlightedIndex("Crop"))
        assertEquals(4, items.findHighlightedIndex("Default mode"))
        assertEquals(-1, items.findHighlightedIndex("Hidden"))
    }
}
