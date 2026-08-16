package eu.kanade.tachiyomi.data.search

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class SearchHistoryStoreTest {

    private val store = SearchHistoryStore(InMemoryPreferenceStore(), Json)

    @Test
    fun `histories remain isolated by scope`() {
        store.add("library", "one piece")
        store.add("audio", "sleep voice")

        assertEquals(listOf("one piece"), store.get("library"))
        assertEquals(listOf("sleep voice"), store.get("audio"))
    }

    @Test
    fun `duplicate query moves to front without changing its case`() {
        store.add("library", "first")
        store.add("library", "second")
        store.add("library", "FIRST")

        assertEquals(listOf("FIRST", "second"), store.get("library"))
    }

    @Test
    fun `blank queries are ignored and entries can be removed or cleared`() {
        store.add("library", "   ")
        store.add("library", "kept")
        store.add("library", "removed")
        store.remove("library", "removed")

        assertEquals(listOf("kept"), store.get("library"))

        store.clear("library")
        assertEquals(emptyList<String>(), store.get("library"))
    }
}
