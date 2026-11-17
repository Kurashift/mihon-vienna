package tachiyomi.source.local.image

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class ChapterCoverFileTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `validated complete cover replaces the previous file`() {
        val target = File(directory, "chapter.webp").apply { writeText("old") }
        writeChapterCover(target, { it.write("new".toByteArray()) }, { it.readText() == "new" })
        assertEquals("new", target.readText())
        assertEquals(listOf(target.name), directory.list()!!.toList())
    }

    @Test
    fun `partial encoding failure preserves the previous cover`() {
        val target = File(directory, "chapter.webp").apply { writeText("old") }
        assertThrows<IOException> {
            writeChapterCover(target, {
                it.write("partial".toByteArray())
                throw IOException("write failed")
            }, { true })
        }
        assertEquals("old", target.readText())
        assertEquals(listOf(target.name), directory.list()!!.toList())
    }

    @Test
    fun `invalid encoded cover preserves the previous cover`() {
        val target = File(directory, "chapter.webp").apply { writeText("old") }
        assertThrows<IllegalStateException> {
            writeChapterCover(target, { it.write("invalid".toByteArray()) }, { false })
        }
        assertEquals("old", target.readText())
        assertEquals(listOf(target.name), directory.list()!!.toList())
    }

    @Test
    fun `failed atomic replacement preserves the previous cover`() {
        val target = File(directory, "chapter.webp").apply { writeText("old") }
        assertThrows<IOException> {
            writeChapterCover(target, { it.write("new".toByteArray()) }, { true }, replace = { _, _ ->
                throw IOException("replace failed")
            })
        }
        assertEquals("old", target.readText())
        assertEquals(listOf(target.name), directory.list()!!.toList())
    }

    @Test
    fun `cancellation before replacement preserves the previous cover`() {
        val target = File(directory, "chapter.webp").apply { writeText("old") }
        assertThrows<CancellationException> {
            writeChapterCover(target, { it.write("new".toByteArray()) }, { true }, beforeCommit = {
                throw CancellationException("cancelled")
            })
        }
        assertEquals("old", target.readText())
        assertEquals(listOf(target.name), directory.list()!!.toList())
    }

    @Test
    fun `failed first cover write leaves no partial file`() {
        val target = File(directory, "chapter.webp")
        assertThrows<IllegalStateException> {
            writeChapterCover(target, { it.write("invalid".toByteArray()) }, { false })
        }
        assertFalse(target.exists())
        assertEquals(0, directory.list()!!.size)
    }
}
