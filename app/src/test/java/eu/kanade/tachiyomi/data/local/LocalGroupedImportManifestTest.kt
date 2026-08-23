package eu.kanade.tachiyomi.data.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalGroupedImportManifestTest {

    @Test
    fun `manifest round trip preserves target ids and source uris`() {
        val groups = listOf(
            PersistedGroupedImport(12L, listOf("content://tree/author-a/book-1", "content://tree/author-a/book-2")),
            PersistedGroupedImport(34L, listOf("content://tree/author-b/book-3")),
        )

        assertEquals(groups, LocalGroupedImportManifest.decode(LocalGroupedImportManifest.encode(groups)))
    }

    @Test
    fun `invalid manifest entries are ignored`() {
        val manifest = """
            {"groups":[
                {"targetMangaId":-1,"uris":["content://invalid"]},
                {"targetMangaId":12,"uris":[]},
                {"targetMangaId":34,"uris":["content://valid"]}
            ]}
        """.trimIndent()

        assertEquals(
            listOf(PersistedGroupedImport(34L, listOf("content://valid"))),
            LocalGroupedImportManifest.decode(manifest),
        )
    }
}
