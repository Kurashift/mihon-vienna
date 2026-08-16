package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.storage.service.StorageManager

class LocalSourceFileSystemTest {

    @Test
    fun `missing directory is unavailable`() {
        val storageManager = mockk<StorageManager>()
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

    private fun createFileSystem(directory: UniFile): LocalSourceFileSystem {
        val storageManager = mockk<StorageManager>()
        every { storageManager.getLocalSourceDirectory() } returns directory
        return LocalSourceFileSystem(storageManager)
    }
}
