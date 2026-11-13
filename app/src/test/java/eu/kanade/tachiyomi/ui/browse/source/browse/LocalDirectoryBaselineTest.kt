package eu.kanade.tachiyomi.ui.browse.source.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalDirectoryBaselineTest {

    @Test
    fun `a refresh that just committed the new directories reports nothing`() {
        // A library holding folders without chapters never has a listing that equals the
        // directories in full, so the committed signature is what has to decide. Comparing against
        // the previous read instead reported the change the refresh had just applied, and the
        // banner asked for a refresh that had already happened.
        assertEquals(
            false,
            localDirectoryDiffersFromBaseline(
                signature = "after",
                committedSignature = "after",
                fromListingFallback = false,
                matchesListing = false,
            ),
        )
    }

    @Test
    fun `a change since the committed refresh still reports`() {
        assertEquals(
            true,
            localDirectoryDiffersFromBaseline(
                signature = "after",
                committedSignature = "before",
                fromListingFallback = false,
                matchesListing = false,
            ),
        )
    }

    @Test
    fun `a listing that already equals the directories is up to date`() {
        assertEquals(
            false,
            localDirectoryDiffersFromBaseline(
                signature = "after",
                committedSignature = "before",
                fromListingFallback = false,
                matchesListing = true,
            ),
        )
    }

    @Test
    fun `a snapshot rebuilt from the listing index confirms nothing`() {
        assertEquals(
            false,
            localDirectoryDiffersFromBaseline(
                signature = "after",
                committedSignature = "before",
                fromListingFallback = true,
                matchesListing = false,
            ),
        )
    }

    @Test
    fun `no committed baseline yet reports nothing`() {
        assertEquals(
            false,
            localDirectoryDiffersFromBaseline(
                signature = "after",
                committedSignature = "",
                fromListingFallback = false,
                matchesListing = false,
            ),
        )
    }
}
