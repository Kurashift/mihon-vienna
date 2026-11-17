package eu.kanade.tachiyomi.data.download

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class DownloadIndexFileTest {

    @TempDir
    lateinit var directory: File

    @Test
    fun `complete snapshot replaces old file and can be read after reopening`() {
        val target = File(directory, "download-index")
        target.writeText("old")
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        writeDownloadIndex(target, bytes)
        assertArrayEquals(bytes, File(directory, target.name).readBytes())
        assertEquals(listOf(target.name), directory.list()!!.toList())
    }

    @Test
    fun `failed replacement leaves previous snapshot and removes staging file`() {
        val target = File(directory, "download-index")
        target.writeText("old")
        assertThrows(IOException::class.java) {
            writeDownloadIndex(target, "new".toByteArray(), replace = { _, _ -> throw IOException("replace failed") })
        }
        assertEquals("old", target.readText())
        assertEquals(listOf(target.name), directory.list()!!.toList())
    }

    @Test
    fun `canceled writer cannot replace last good snapshot`() {
        val target = File(directory, "download-index")
        target.writeText("old")
        assertThrows(CancellationException::class.java) {
            writeDownloadIndex(target, "new".toByteArray(), beforeCommit = { throw CancellationException() })
        }
        assertEquals("old", target.readText())
        assertEquals(listOf(target.name), directory.list()!!.toList())
    }

    @Test
    fun `abandoned staging file is ignored and reused by the next write`() {
        val target = File(directory, "download-index")
        target.writeText("old")
        File(directory, "download-index.tmp").writeText("incomplete")
        assertEquals("old", File(directory, target.name).readText())
        writeDownloadIndex(target, "complete".toByteArray())
        assertEquals("complete", target.readText())
        assertEquals(listOf(target.name), directory.list()!!.toList())
    }

    @Test
    fun `failed first write does not leave a partial index`() {
        val target = File(directory, "download-index")
        assertThrows(IOException::class.java) {
            writeDownloadIndex(target, byteArrayOf(1), replace = { _, _ -> throw IOException("replace failed") })
        }
        assertFalse(target.exists())
        assertEquals(0, directory.list()!!.size)
    }
}
