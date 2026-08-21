package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.storage.service.StorageManager

class LocalSourceFileSystemTest {

    @Test
    fun `missing directory is unavailable`() {
        val storageManager = mockk<StorageManager>()
        every { storageManager.getDirectLocalSourceDirectory() } returns null
        every { storageManager.getLocalSourceDirectory() } returns null

        val snapshot = LocalSourceFileSystem(storageManager).getBaseDirectorySnapshot()

        assertFalse(snapshot.isAccessible)
        assertTrue(snapshot.files.isEmpty())
    }

    @Test
    fun `readable empty directory stays a valid empty library`() {
        val directory = mockk<UniFile>()
        every { directory.exists() } returns true
        every { directory.isDirectory } returns true
        every { directory.listFiles() } returns emptyArray()

        val snapshot = createFileSystem(directory).getBaseDirectorySnapshot()

        assertTrue(snapshot.isAccessible)
        assertTrue(snapshot.files.isEmpty())
    }

    @Test
    fun `failed directory listing is unavailable rather than empty`() {
        val directory = mockk<UniFile>()
        every { directory.exists() } returns true
        every { directory.isDirectory } returns true
        every { directory.listFiles() } returns null

        val snapshot = createFileSystem(directory).getBaseDirectorySnapshot()

        assertFalse(snapshot.isAccessible)
        assertTrue(snapshot.files.isEmpty())
    }

    @Test
    fun `directory listing exception is unavailable rather than empty`() {
        val directory = mockk<UniFile>()
        every { directory.exists() } returns true
        every { directory.isDirectory } returns true
        every { directory.listFiles() } throws SecurityException("revoked")

        val snapshot = createFileSystem(directory).getBaseDirectorySnapshot()

        assertFalse(snapshot.isAccessible)
        assertTrue(snapshot.files.isEmpty())
    }

    @Test
    fun `failed manga directory listing is unavailable rather than empty`() {
        val baseDirectory = mockk<UniFile>()
        val mangaDirectory = mockk<UniFile>()
        every { baseDirectory.findFile("Author") } returns mangaDirectory
        every { mangaDirectory.exists() } returns true
        every { mangaDirectory.isDirectory } returns true
        every { mangaDirectory.listFiles() } returns null

        val snapshot = createFileSystem(baseDirectory).getMangaDirectorySnapshot("Author")

        assertFalse(snapshot.isAccessible)
        assertTrue(snapshot.files.isEmpty())
    }

    @Test
    fun `readable empty manga directory remains distinguishable`() {
        val baseDirectory = mockk<UniFile>()
        val mangaDirectory = mockk<UniFile>()
        every { baseDirectory.findFile("Author") } returns mangaDirectory
        every { mangaDirectory.exists() } returns true
        every { mangaDirectory.isDirectory } returns true
        every { mangaDirectory.listFiles() } returns emptyArray()

        val snapshot = createFileSystem(baseDirectory).getMangaDirectorySnapshot("Author")

        assertTrue(snapshot.isAccessible)
        assertTrue(snapshot.files.isEmpty())
    }

    @Test
    fun `direct local directory is preferred when available`() {
        val storageManager = mockk<StorageManager>()
        val directDirectory = mockk<UniFile>()
        val safDirectory = mockk<UniFile>()
        every { storageManager.getDirectLocalSourceDirectory() } returns directDirectory
        every { storageManager.getLocalSourceDirectory() } returns safDirectory

        val fileSystem = LocalSourceFileSystem(storageManager)

        assertTrue(fileSystem.getBaseDirectory() === directDirectory)
    }

    @Test
    fun `malformed direct file names are excluded without losing valid names`() {
        val validFirst = "/storage/emulated/0/MIHON/local/Full Exist".toByteArray()
        val malformed = byteArrayOf(
            '/'.code.toByte(),
            'b'.code.toByte(),
            'a'.code.toByte(),
            'd'.code.toByte(),
            0xE5.toByte(),
            0x84.toByte(),
        )
        val validSecond = "/storage/emulated/0/MIHON/local/Another".toByteArray()
        val bytes = validFirst + byteArrayOf(0) + malformed + byteArrayOf(0) + validSecond + byteArrayOf(0)

        val decoded = decodeNullSeparatedUtf8Paths(bytes)

        assertEquals(
            listOf(
                "/storage/emulated/0/MIHON/local/Full Exist",
                "/storage/emulated/0/MIHON/local/Another",
            ),
            decoded.paths,
        )
        assertEquals(1, decoded.malformedCount)
    }

    private fun createFileSystem(directory: UniFile): LocalSourceFileSystem {
        val storageManager = mockk<StorageManager>()
        every { storageManager.getDirectLocalSourceDirectory() } returns null
        every { storageManager.getLocalSourceDirectory() } returns directory
        return LocalSourceFileSystem(storageManager)
    }
}
