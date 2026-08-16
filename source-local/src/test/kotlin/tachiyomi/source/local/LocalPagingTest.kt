package tachiyomi.source.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalPagingTest {

    @Test
    fun `splits a large local library into stable pages`() {
        val items = (1..125).toList()

        val first = items.localPage(page = 1, pageSize = 50)
        val second = items.localPage(page = 2, pageSize = 50)
        val third = items.localPage(page = 3, pageSize = 50)

        assertEquals(1..50, first.items.asRange())
        assertEquals(51..100, second.items.asRange())
        assertEquals(101..125, third.items.asRange())
        assertTrue(first.hasNextPage)
        assertTrue(second.hasNextPage)
        assertFalse(third.hasNextPage)
    }

    @Test
    fun `returns an empty terminal page past the end`() {
        val page = (1..10).toList().localPage(page = 2, pageSize = 10)

        assertTrue(page.items.isEmpty())
        assertFalse(page.hasNextPage)
    }

    private fun List<Int>.asRange(): IntRange = first()..last()
}
