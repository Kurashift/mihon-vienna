package eu.kanade.tachiyomi.data.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalLibraryStateStoresTest {

    @Test
    fun `cover update only replaces the target manga`() {
        val store = MangaCoverUpdateStore()
        val first = MangaCoverUpdate("cover-a", 10L)
        val second = MangaCoverUpdate("cover-b", 20L)
        val replacement = MangaCoverUpdate("cover-a-2", 30L)

        store.publish(1L, first)
        store.publish(2L, second)
        store.publish(1L, replacement)

        assertEquals(mapOf(1L to replacement, 2L to second), store.covers.value)
    }
}
