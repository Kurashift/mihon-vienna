package eu.kanade.tachiyomi.data.local

import androidx.work.Data
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalTransferManifestTest {

    @Test
    fun `manifest round trip preserves the whole request`() {
        val request = PersistedTransferRequest(
            targetMangaId = 12L,
            uris = listOf("content://tree/author-a/book-1", "content://tree/author-a/book-2"),
            folderOutput = LocalChapterTransferService.FolderOutput.CBZ.name,
            deleteSource = true,
        )

        assertEquals(request, LocalTransferManifest.decode(LocalTransferManifest.encode(request)))
    }

    @Test
    fun `grouped manifest round trip preserves target ids and source uris`() {
        val request = PersistedTransferRequest(
            groups = listOf(
                PersistedTransferGroup(12L, listOf("content://tree/author-a/book-1")),
                PersistedTransferGroup(34L, listOf("content://tree/author-b/book-3")),
            ),
        )

        assertEquals(request, LocalTransferManifest.decode(LocalTransferManifest.encode(request)))
    }

    @Test
    fun `move manifest round trip preserves chapter ids`() {
        val request = PersistedTransferRequest(
            targetMangaId = 7L,
            chapterIds = (1L..1500L).toList(),
        )

        assertEquals(request, LocalTransferManifest.decode(LocalTransferManifest.encode(request)))
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
            listOf(PersistedTransferGroup(34L, listOf("content://valid"))),
            LocalTransferManifest.decode(manifest).groups,
        )
    }

    /**
     * The bug this guards: the request used to be passed through WorkManager input data, which
     * rejects anything over 10 KB once serialized. A folder import of a few dozen books with long
     * SAF URIs, or a move of a thousand chapters, crashed the app on enqueue. Only the manifest
     * file name may travel in the request now.
     */
    @Test
    fun `a large import payload is no longer carried in work manager input data`() {
        val uris = (1..60).map { index ->
            "content://com.android.externalstorage.documents/tree/primary%3A%E5%9B%BE%E7%89%87%2FMana" +
                "%2F%E5%8E%9F%E7%A5%9E%E7%B3%BB%E5%88%97/document/primary%3A%E5%9B%BE%E7%89%87%2FMana" +
                "%2F%E5%8E%9F%E7%A5%9E%E7%B3%BB%E5%88%97%2F%E7%AC%AC$index%E8%AF%9D"
        }
        val request = PersistedTransferRequest(targetMangaId = 12L, uris = uris)

        // The payload alone is what used to overflow the limit.
        val oversized = runCatching {
            Data.Builder().putStringArray("uris", uris.toTypedArray()).build().toByteArray().size
        }
        assertTrue(
            oversized.isFailure,
            "expected the raw URI list to exceed WorkManager's 10 KB input limit",
        )

        // The manifest absorbs it without complaint...
        val encoded = LocalTransferManifest.encode(request)
        assertEquals(uris, LocalTransferManifest.decode(encoded).uris)

        // ...and what the request carries stays far below the limit. The work request holds only
        // the manifest's file name, so its size no longer depends on how much is being imported.
        val carried = Data.Builder().putString("manifest", "local-transfer-abc.json").build()
        assertTrue(carried.toByteArray().size < 10240)
    }

    @Test
    fun `a move of a thousand chapters is no longer carried in work manager input data`() {
        val chapterIds = (1L..1300L).toList()

        assertTrue(
            runCatching {
                Data.Builder().putLongArray("chapter_ids", chapterIds.toLongArray()).build()
            }.isFailure,
        )

        val request = PersistedTransferRequest(
            targetMangaId = 7L,
            isMove = true,
            chapterIds = chapterIds,
        )
        val encoded = LocalTransferManifest.encode(request)
        assertEquals(request, LocalTransferManifest.decode(encoded))
    }

    @Test
    fun `blank uris are dropped rather than replayed`() {
        val decoded = LocalTransferManifest.decode(
            """{"targetMangaId":12,"uris":["content://a","","  ","content://b"]}""",
        )

        assertEquals(listOf("content://a", "content://b"), decoded.uris)
    }

    @Test
    fun `a malformed manifest does not escape as an exception`() {
        assertFalse(runCatching { LocalTransferManifest.decode("not json") }.isSuccess)
    }
}
