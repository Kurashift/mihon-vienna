package tachiyomi.source.local.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File
import java.io.IOException
import java.io.OutputStream

class LocalChapterCoverRestoreTest {
    @TempDir
    lateinit var directory: File

    private lateinit var manager: LocalChapterCoverManager
    private lateinit var bitmap: Bitmap

    @BeforeEach
    fun setUp() {
        val context = mockk<Context> {
            every { filesDir } returns directory
            every { cacheDir } returns directory
        }
        manager = LocalChapterCoverManager(context, mockk<LocalSourceFileSystem>())
        bitmap = mockk<Bitmap>(relaxed = true) {
            every { width } returns 100
            every { height } returns 100
        }
        mockkConstructor(BitmapFactory.Options::class)
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeStream(any(), any(), any()) } answers {
            val options = thirdArg<BitmapFactory.Options>()
            if (options.inJustDecodeBounds) {
                options.outWidth = 100
                options.outHeight = 100
                null
            } else {
                bitmap
            }
        }
        every { bitmap.compress(any(), any(), any()) } answers {
            thirdArg<OutputStream>().write("encoded-cover".toByteArray())
            true
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(BitmapFactory::class)
        unmockkConstructor(BitmapFactory.Options::class)
    }

    @Test
    fun `restored cover can immediately be displayed and exported using its destination id`() = runBlocking {
        assertTrue(manager.restoreCustom(9002, byteArrayOf(1)))
        val target = manager.getOrCreate(LocalChapterCover(9002, "work/chapter.cbz", 0))!!
        assertEquals("chapter-9002.webp", target.name)
        assertEquals("encoded-cover", target.readText())
        assertArrayEquals("encoded-cover".toByteArray(), manager.exportCustom(9002))
        assertEquals(listOf(target.name), target.parentFile!!.list()!!.toList())
        assertEquals(listOf("local_custom_chapter_covers"), directory.list()!!.toList())
    }

    @Test
    fun `failed restore keeps the cover already displayed for this chapter`() = runBlocking {
        assertTrue(manager.restoreCustom(9002, byteArrayOf(1)))
        every { bitmap.compress(any(), any(), any()) } throws IOException("disk full")
        assertThrows<IOException> { manager.restoreCustom(9002, byteArrayOf(2)) }
        assertArrayEquals("encoded-cover".toByteArray(), manager.exportCustom(9002))
        val target = manager.getOrCreate(LocalChapterCover(9002, "work/chapter.cbz", 0))!!
        assertEquals(listOf(target.name), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun `empty oversized and undecodable cover data leave the previous cover intact`() = runBlocking {
        assertTrue(manager.restoreCustom(9002, byteArrayOf(1)))
        assertFalse(manager.restoreCustom(9002, byteArrayOf()))
        assertFalse(manager.restoreCustom(9002, ByteArray(10 * 1024 * 1024 + 1)))
        every { BitmapFactory.decodeStream(any(), any(), any()) } returns null
        assertFalse(manager.restoreCustom(9002, byteArrayOf(2)))
        assertArrayEquals("encoded-cover".toByteArray(), manager.exportCustom(9002))
        assertEquals(listOf("local_custom_chapter_covers"), directory.list()!!.toList())
    }
}
