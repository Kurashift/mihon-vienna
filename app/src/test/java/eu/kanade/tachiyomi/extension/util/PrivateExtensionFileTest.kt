package eu.kanade.tachiyomi.extension.util

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems

class PrivateExtensionFileTest {

    @TempDir
    lateinit var directory: File

    private fun source() = File(directory, "download.apk").apply { writeText("new extension") }

    private fun target() = File(directory, "exts/test.ext").apply {
        parentFile.mkdirs()
        writeText("old extension")
    }

    @Test
    fun `failed copy preserves old extension and removes staging file`() {
        val target = target()
        assertThrows(IOException::class.java) {
            replacePrivateExtensionFile(source(), target, copy = { _, staged ->
                staged.writeText("partial")
                throw IOException("disk write failed")
            })
        }
        assertEquals("old extension", target.readText())
        assertEquals(listOf("test.ext"), target.parentFile.list()!!.toList())
    }

    @Test
    fun `failed replacement preserves old extension`() {
        val target = target()
        assertThrows(IOException::class.java) {
            replacePrivateExtensionFile(source(), target, replace = { _, _ -> throw IOException("rename failed") })
        }
        assertEquals("old extension", target.readText())
        assertEquals(listOf("test.ext"), target.parentFile.list()!!.toList())
    }

    @Test
    fun `cancel after copy preserves old extension`() {
        val target = target()
        assertThrows(CancellationException::class.java) {
            replacePrivateExtensionFile(source(), target, beforeCommit = { throw CancellationException() })
        }
        assertEquals("old extension", target.readText())
        assertEquals(listOf("test.ext"), target.parentFile.list()!!.toList())
    }

    @Test
    fun `normal first install and repeated updates replace whole files`() {
        val source = source()
        val target = File(directory, "exts/test.ext")
        replacePrivateExtensionFile(source, target)
        assertEquals("new extension", target.readText())
        source.writeText("next extension")
        target.setWritable(true)
        replacePrivateExtensionFile(source, target)
        assertEquals("next extension", target.readText())
        assertEquals(listOf("test.ext"), target.parentFile.list()!!.toList())
    }

    @Test
    fun `read only installed extension can be replaced on Android style filesystems`() {
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
        val target = target()
        target.setReadOnly()
        replacePrivateExtensionFile(source(), target)
        assertEquals("new extension", target.readText())
    }
}
