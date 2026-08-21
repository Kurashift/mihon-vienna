package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageBindingGenerationTest {

    @Test
    fun `rebinding invalidates an older page load`() {
        val bindings = PageBindingGeneration()
        val first = bindings.next()
        val second = bindings.next()

        assertFalse(bindings.isCurrent(first))
        assertTrue(bindings.isCurrent(second))
    }

    @Test
    fun `recycling invalidates the active page load`() {
        val bindings = PageBindingGeneration()
        val active = bindings.next()

        bindings.invalidate()

        assertFalse(bindings.isCurrent(active))
    }
}
