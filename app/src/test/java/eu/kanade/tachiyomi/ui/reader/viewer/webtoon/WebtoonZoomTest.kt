package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class WebtoonZoomTest {
    @Test
    fun `wide page that cannot fill viewport height still has two levels`() {
        val levels = WebtoonZoom.levels(1920, 1280, 1080, false)
        assertEquals(1f, levels.reading)
        assertEquals(0.8f, levels.nextScale(1f))
        assertEquals(1f, levels.nextScale(0.8f))
    }

    @Test
    fun `normal landscape page keeps full height and eighty percent height`() {
        val levels = WebtoonZoom.levels(2400, 1080, 2160, false)
        assertEquals(0.5f, levels.reading)
        assertEquals(0.4f, levels.alternate)
        assertEquals(levels.alternate, levels.nextScale(0.505f))
    }

    @Test
    fun `all landscape dimensions produce different bounded reachable levels`() {
        for (height in listOf(1, 720, 1080, 1280)) {
            for (pageHeight in listOf(1, 20, 720, 1080, 4000, 100_000, Int.MAX_VALUE)) {
                val levels = WebtoonZoom.levels(height * 2, height, pageHeight, false)
                assertTrue(levels.reading in WebtoonZoom.MIN_LANDSCAPE_SCALE..1f)
                assertTrue(levels.alternate in WebtoonZoom.MIN_LANDSCAPE_SCALE..1f)
                assertTrue(kotlin.math.abs(levels.reading - levels.alternate) >= levels.reading * 0.19f)
                assertEquals(levels.alternate, levels.nextScale(levels.reading))
                assertEquals(levels.reading, levels.nextScale(levels.alternate))
            }
        }
    }

    @Test
    fun `portrait and disabled zoom out use normal enlargement and return`() {
        for (levels in listOf(
            WebtoonZoom.levels(1080, 2400, 1600, false),
            WebtoonZoom.levels(2400, 1080, 1600, true),
            WebtoonZoom.levels(1080, 2400, 1600, true),
        )) {
            assertEquals(2f, levels.nextScale(1f))
            assertEquals(1f, levels.nextScale(2f))
            assertEquals(1f, levels.minimum)
        }
    }

    @Test
    fun `pinch zoom returns to reading scale before toggling again`() {
        val levels = WebtoonZoom.levels(2400, 1080, 2160, false)
        assertEquals(levels.reading, levels.nextScale(1.7f))
        assertEquals(levels.reading, levels.nextScale(0.2f))
        assertEquals(levels.alternate, levels.nextScale(levels.reading))
    }

    @Test
    fun `double tap reverses current animation target without waiting for completion`() {
        val levels = WebtoonZoom.levels(1920, 1280, 1080, false)
        assertEquals(levels.reading, levels.nextScale(0.93f, levels.alternate))
        assertEquals(levels.alternate, levels.nextScale(0.93f, levels.reading))
    }

    @Test
    fun `viewport changes recalculate levels using new dimensions`() {
        val portrait = WebtoonZoom.levels(1080, 2400, 1080, false)
        val landscape = WebtoonZoom.levels(2400, 1080, 2400, false)
        val window = WebtoonZoom.levels(1920, 1280, 1920, false)
        assertEquals(1f, portrait.reading)
        assertEquals(0.45f, landscape.reading)
        assertEquals(1280f / 1920, window.reading)
    }

    @Test
    fun `unmeasured pages use normal zoom instead of swallowing double tap`() {
        assertEquals(2f, WebtoonZoom.levels(1920, 1280, 0, false).nextScale(1f))
        assertEquals(2f, WebtoonZoom.levels(0, 0, 0, false).nextScale(1f))
    }

    @Test
    fun `default preferences enable double tap and allow smaller images`() {
        val preferences = ReaderPreferences(InMemoryPreferenceStore())
        assertTrue(preferences.webtoonDoubleTapZoomEnabled.get())
        assertFalse(preferences.webtoonDisableZoomOut.get())
    }

    @Test
    fun `existing preferences are preserved for every switch combination`() {
        for (doubleTap in listOf(false, true)) {
            for (disableZoomOut in listOf(false, true)) {
                val store = InMemoryPreferenceStore(
                    sequenceOf(
                        InMemoryPreferenceStore.InMemoryPreference(
                            "pref_enable_double_tap_zoom_webtoon",
                            doubleTap,
                            true,
                        ),
                        InMemoryPreferenceStore.InMemoryPreference("webtoon_disable_zoom_out", disableZoomOut, false),
                    ),
                )
                val preferences = ReaderPreferences(store)
                assertEquals(doubleTap, preferences.webtoonDoubleTapZoomEnabled.get())
                assertEquals(disableZoomOut, preferences.webtoonDisableZoomOut.get())
            }
        }
    }
}
