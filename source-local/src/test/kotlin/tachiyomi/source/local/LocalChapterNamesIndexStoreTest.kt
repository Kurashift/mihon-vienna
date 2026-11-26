package tachiyomi.source.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LocalChapterNamesIndexStoreTest {

    @Test
    fun `round trips index and validates baseUri, mtime, and maxAge`(@TempDir dir: File) {
        val indexFile = File(dir, "chapter_names.json")
        val store = LocalChapterNamesIndexStore(fileProvider = { indexFile }, version = 2)
        val expected = mapOf(
            "Work A" to listOf("Ch 1", "Ch 2"),
            "Empty Work" to emptyList(),
        )

        store.save(expected, baseUri = "content://local", baseDirLastModified = 100L, now = 1_000L)

        assertEquals(
            expected,
            store.readFresh(
                expectedBaseUri = "content://local",
                expectedBaseDirLastModified = 100L,
                now = 1_500L,
                maxAgeMillis = 10_000L,
            ),
        )
        assertNull(
            store.readFresh(
                expectedBaseUri = "content://other",
                expectedBaseDirLastModified = 100L,
                now = 1_500L,
                maxAgeMillis = 10_000L,
            ),
        )
        assertNull(
            store.readFresh(
                expectedBaseUri = "content://local",
                expectedBaseDirLastModified = 200L,
                now = 1_500L,
                maxAgeMillis = 10_000L,
            ),
        )
        assertNull(
            store.readFresh(
                expectedBaseUri = "content://local",
                expectedBaseDirLastModified = 100L,
                now = 20_000L,
                maxAgeMillis = 10_000L,
            ),
        )
        assertEquals(expected, store.readSnapshot("content://local")?.manga)
    }

    @Test
    fun `rejects wrong version and malformed json cleanly`(@TempDir dir: File) {
        val indexFile = File(dir, "chapter_names.json")
        val storeV2 = LocalChapterNamesIndexStore(fileProvider = { indexFile }, version = 2)
        val storeV3 = LocalChapterNamesIndexStore(fileProvider = { indexFile }, version = 3)

        storeV2.save(
            mapOf("Work A" to listOf("Ch 1")),
            baseUri = "content://local",
            baseDirLastModified = 10L,
            now = 50L,
        )
        assertNull(storeV3.readSnapshot("content://local"))

        indexFile.writeText("{not valid json")
        assertNull(storeV2.readSnapshot("content://local"))
    }
}
