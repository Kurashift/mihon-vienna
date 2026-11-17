package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class DownloadDirectoryListingTest {

    @Test
    fun `unreadable directory cannot clear a valid snapshot`() {
        val directory = mockk<UniFile>()
        every { directory.listFiles() } returns null
        assertThrows(IOException::class.java) { readDownloadDirectory(directory) }
        assertThrows(IOException::class.java) { readDownloadDirectory(null) }
    }

    @Test
    fun `empty listing with existing known files is rejected`() {
        val directory = mockk<UniFile>()
        every { directory.listFiles() } returns emptyArray()
        assertThrows(IOException::class.java) { readDownloadDirectory(directory) { true } }
    }

    @Test
    fun `initial empty directory and confirmed deletion are accepted`() {
        val directory = mockk<UniFile>()
        every { directory.listFiles() } returns emptyArray()
        assertEquals(emptyList<UniFile>(), readDownloadDirectory(directory))
        assertEquals(emptyList<UniFile>(), readDownloadDirectory(directory) { false })
    }

    @Test
    fun `large directory has no cap and does not probe known entries`() {
        val directory = mockk<UniFile>()
        val entry = mockk<UniFile>()
        every { directory.listFiles() } returns Array(12008) { entry }
        assertEquals(12008, readDownloadDirectory(directory) { error("Unexpected existence probe") }.size)
    }
}
