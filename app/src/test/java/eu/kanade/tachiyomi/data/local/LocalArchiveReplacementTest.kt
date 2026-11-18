package eu.kanade.tachiyomi.data.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class LocalArchiveReplacementTest {
    @TempDir lateinit var directory: Path
    private lateinit var original: Path
    private lateinit var staged: Path
    private lateinit var approval: LocalArchiveReplacement

    @BeforeEach
    fun prepare() {
        original = Files.writeString(directory.resolve("chapter.cbz"), "blue")
        staged = Files.writeString(directory.resolve(".chapter.tmp"), "red")
        approval = LocalArchiveReplacement(
            "work",
            "content://source/chapter",
            "chapter.cbz",
            original.toString(),
            requireNotNull(archiveReplacementDigest(original)),
        )
    }

    @Test
    fun `replacement keeps the original path`() {
        assertTrue(commitArchiveReplacement(approval, staged))
        assertEquals("red", Files.readString(original))
        assertFalse(Files.exists(staged))
    }

    @Test
    fun `same size and timestamp do not authorize a changed file`() {
        val stamp = Files.getLastModifiedTime(original)
        Files.writeString(original, "pink")
        Files.setLastModifiedTime(original, stamp)
        assertFalse(commitArchiveReplacement(approval, staged))
        assertEquals("pink", Files.readString(original))
    }

    @Test
    fun `atomic move failure leaves the original intact`() {
        assertThrows(IOException::class.java) {
            commitArchiveReplacement(approval, staged) { _, _ -> throw IOException("Interrupted") }
        }
        assertEquals("blue", Files.readString(original))
        assertEquals("red", Files.readString(staged))
    }

    @Test
    fun `only the archive extension is removed for conflict detection`() {
        val name = "Vol. 2"
        assertEquals(chapterImportKey(name, isFile = false), chapterImportKey("$name.CBZ", isFile = true))
        assertEquals(chapterImportKey("$name.rar", isFile = true), chapterImportKey("$name.cbz", isFile = true))
        assertFalse(chapterImportKey("Vol. 3", isFile = false) == chapterImportKey("$name.cbz", isFile = true))
    }
}
