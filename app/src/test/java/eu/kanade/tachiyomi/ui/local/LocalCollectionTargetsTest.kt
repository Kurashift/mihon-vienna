package eu.kanade.tachiyomi.ui.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class LocalCollectionTargetsTest {

    private val empty = Manga.create().copy(id = 1, url = "empty", title = "Empty")
    private val source = Manga.create().copy(id = 2, url = "source", title = "Source")
    private val missing = Manga.create().copy(id = 3, url = "missing", title = "Missing")
    private val mangas = listOf(source, missing, empty)

    @Test
    fun `indexed empty work is selectable even without any chapter row`() {
        assertEquals(
            listOf(empty, source),
            localCollectionTargets(mangas, setOf(empty.url, source.url), setOf(source.id, missing.id)),
        )
    }

    @Test
    fun `moving excludes only the current collection among indexed targets`() {
        assertEquals(
            listOf(empty),
            localCollectionTargets(mangas, setOf(empty.url, source.url), emptySet(), excludedId = source.id),
        )
    }

    @Test
    fun `confirmed empty listing does not show stale database rows`() {
        assertEquals(
            emptyList<Manga>(),
            localCollectionTargets(mangas, emptySet(), setOf(source.id, missing.id)),
        )
    }

    @Test
    fun `missing index falls back to previously selectable targets`() {
        assertEquals(listOf(source), localCollectionTargets(mangas, null, setOf(source.id)))
    }
}
