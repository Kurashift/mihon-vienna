package eu.kanade.tachiyomi.ui.reader

import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mihon.core.archive.ArchiveFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class ReaderArchiveReplacementTest {
    private val archive = mockk<UniFile>(relaxed = true)
    private val staged = mockk<UniFile>(relaxed = true)
    private val parent = mockk<UniFile>(relaxed = true)
    private val backup = mockk<UniFile>(relaxed = true)
    private val originalName = "chapter.cbz"
    private val stagedName = ".rewrite.tmp"
    private val backupName = ".original.bak"

    init {
        every { archive.renameTo(backupName) } returns true
        every { staged.renameTo(originalName) } returns true
        every { parent.findFile(backupName) } returns backup
        every { parent.findFile(stagedName) } returns staged
        every { backup.renameTo(originalName) } returns true
    }

    private fun replace() =
        replaceReaderArchive(archive, staged, parent, originalName, stagedName, backupName)

    @Test
    fun `successful replacement never deletes the renamed staged handle`() {
        assertTrue(replace())
        verify(exactly = 1) { backup.delete() }
        verify(exactly = 0) { staged.delete() }
        verify(exactly = 0) { archive.delete() }
    }

    @Test
    fun `failure to move the original leaves it intact`() {
        every { archive.renameTo(backupName) } returns false
        assertFalse(replace())
        verify(exactly = 0) { staged.renameTo(originalName) }
        verify(exactly = 0) { archive.delete() }
        verify(exactly = 1) { staged.delete() }
    }

    @Test
    fun `failure to place replacement restores original by its current name`() {
        every { staged.renameTo(originalName) } returns false
        assertFalse(replace())
        verify(exactly = 1) { backup.renameTo(originalName) }
        verify(exactly = 0) { backup.delete() }
    }

    @Test
    fun `rollback failure retains the original backup`() {
        every { staged.renameTo(originalName) } returns false
        every { backup.renameTo(originalName) } returns false
        assertFalse(replace())
        verify(exactly = 0) { backup.delete() }
        verify(exactly = 0) { archive.delete() }
    }

    @Test
    fun `provider exception still rolls back the original`() {
        every { staged.renameTo(originalName) } throws IOException("provider failed")
        assertThrows(IOException::class.java) { replace() }
        verify(exactly = 1) { backup.renameTo(originalName) }
        verify(exactly = 0) { backup.delete() }
    }

    @Test
    fun `backup cleanup failure does not report a successful replacement as failed`() {
        every { backup.delete() } throws IOException("provider failed")
        assertTrue(replace())
        verify(exactly = 0) { staged.delete() }
    }

    @Test
    fun `archive targets retain their source identity after a provider mutates its handle`() {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://chapters/original"
        every { archive.uri } returns uri
        every { archive.name } returns originalName
        every { archive.parentFile } returns parent
        val target = ReaderPage.DeletionTarget.ArchiveEntry(archive, "001.png", ArchiveFormat.ZIP)

        every { archive.name } returns backupName
        every { uri.toString() } returns "content://chapters/backup"

        assertEquals(originalName, target.archiveName)
        assertEquals("content://chapters/original", target.archiveUri)
        assertSame(parent, target.archiveParent)
    }
}
