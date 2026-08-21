package eu.kanade.tachiyomi.ui.browse.source.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalDirectoryChangeConfirmationTest {

    @Test
    fun `addition can be applied immediately`() {
        assertEquals(
            true,
            localDirectoryChangeCanApplyImmediately(
                observedUrls = setOf("a", "b", "c"),
                listingUrls = setOf("a", "b"),
            ),
        )
    }

    @Test
    fun `possible partial scan waits for a later poll`() {
        assertEquals(
            false,
            localDirectoryChangeCanApplyImmediately(
                observedUrls = setOf("a"),
                listingUrls = setOf("a", "b"),
            ),
        )
    }

    @Test
    fun `replacement waits for a later poll`() {
        assertEquals(
            false,
            localDirectoryChangeCanApplyImmediately(
                observedUrls = setOf("a", "c"),
                listingUrls = setOf("a", "b"),
            ),
        )
    }
}
