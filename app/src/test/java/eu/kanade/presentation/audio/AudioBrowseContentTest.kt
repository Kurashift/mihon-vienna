package eu.kanade.presentation.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudioBrowseContentTest {

    @Test
    fun `compact counts keep useful precision without widening cards`() {
        assertEquals("999", formatCompactCount(999))
        assertEquals("1K", formatCompactCount(1_000))
        assertEquals("49.6K", formatCompactCount(49_652))
        assertEquals("374K", formatCompactCount(374_085))
        assertEquals("1.2M", formatCompactCount(1_250_000))
    }
}
