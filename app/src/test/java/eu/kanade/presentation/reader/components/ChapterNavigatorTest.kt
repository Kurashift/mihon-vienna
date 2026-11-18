package eu.kanade.presentation.reader.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChapterNavigatorTest {
    @Test
    fun `navigation falls back only when the minimum rail cannot fit`() {
        assertEquals(0, sideRailHeight(199, 0.65f, 200, hasTrack = true))
        assertEquals(200, sideRailHeight(200, 0.65f, 200, hasTrack = true))
        assertEquals(260, sideRailHeight(400, 0.65f, 200, hasTrack = true))
    }

    @Test
    fun `a single page only reserves space for the chapter buttons`() {
        assertEquals(104, sideRailHeight(400, 1f, 104, hasTrack = false))
        assertEquals(0, sideRailHeight(103, 1f, 104, hasTrack = false))
    }
}
