package tachiyomi.source.local

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.Archive
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LOCAL_SINGLE_CHAPTER_NAME
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.FileNotFoundException
import java.io.IOException

class LocalSourceChapterEntryTest {

    private val context = mockk<Context> {
        every { contentResolver } returns mockk<ContentResolver>(relaxed = true)
        // The source resolves its display name eagerly, which asks the context for a string.
        every { resources } returns mockk<Resources>(relaxed = true)
    }
    private val fileSystem = mockk<LocalSourceFileSystem>(relaxed = true)

    private val source = LocalSource(
        context = context,
        fileSystem = fileSystem,
        coverManager = mockk<LocalCoverManager>(relaxed = true),
    )

    private val manga = SManga.create().apply { title = "Test Manga" }

    @Test
    fun `an unreadable archive keeps its file-name entry instead of failing the scan`() {
        // The archive reader throws on a truncated or corrupt file. That chapter must degrade to
        // an entry with no page count, because the alternative is the whole manga losing its
        // chapter list - one bad file is not a reason to hide the twenty good ones next to it.
        val chapter = chapterFile("Chapter 1.cbz")
        every { context.contentResolver.openFileDescriptor(any(), any()) } throws FileNotFoundException()

        val entry = source.buildChapterEntry(manga, chapter)

        assertEquals("Chapter 1.cbz", entry.name)
        assertEquals("Chapter 1", entry.displayName)
        assertEquals(0, entry.pageCount)
        assertFalse(entry.hasComicInfo)
    }

    @Test
    fun `an unreadable epub also degrades instead of failing the scan`() {
        val chapter = chapterFile("Chapter 1.epub")
        every { context.contentResolver.openFileDescriptor(any(), any()) } throws FileNotFoundException()

        val entry = source.buildChapterEntry(manga, chapter)

        assertEquals("Chapter 1", entry.displayName)
        assertEquals(0, entry.pageCount)
    }

    @Test
    fun `a directory chapter whose listing fails also degrades`() {
        // Same recovery for the folder form: a document provider that refuses the listing must
        // cost this one chapter its page count, not the whole manga its chapter list.
        val chapter = mockk<UniFile>(relaxed = true) {
            every { name } returns "Chapter 1"
            every { isDirectory } returns true
            every { lastModified() } returns 0L
            every { findFile(any()) } returns null
        }
        every { fileSystem.getFilesInDirectory(chapter) } throws IOException("directory listing failed")

        val entry = source.buildChapterEntry(manga, chapter)

        assertEquals("Chapter 1", entry.displayName)
        assertEquals(0, entry.pageCount)
    }

    @Test
    fun `a folder of loose pages becomes one chapter under the virtual name`() {
        val pages = listOf(pageFile("001.webp", 100L), pageFile("002.webp", 200L))

        val entry = source.buildSingleChapterEntry(pages)

        assertEquals(LOCAL_SINGLE_CHAPTER_NAME, entry.name)
        assertEquals(2, entry.pageCount)
        assertEquals(200L, entry.dateUpload)
        // The display name is a UI label; the identity must stay something no file can be called.
        assertFalse(Archive.isChapterEntryName(entry.name, isDirectory = false))
        assertFalse(Archive.isChapterEntryName(entry.name, isDirectory = true))
    }

    @Test
    fun `the virtual chapter resolves to the work directory itself`() {
        val baseDirectory = mockk<UniFile>(relaxed = true)
        val workDirectory = mockk<UniFile>(relaxed = true)
        every { fileSystem.getBaseDirectory() } returns baseDirectory
        every { baseDirectory.findFile("Work A") } returns workDirectory

        val format = source.getFormat(
            SChapter.create().apply { url = "Work A/$LOCAL_SINGLE_CHAPTER_NAME" },
        )

        assertTrue(format is Format.Directory)
        assertEquals(workDirectory, (format as Format.Directory).file)
    }

    @Test
    fun `a flat folder becomes one chapter in the manga update`() = runBlocking {
        val workDirectory = mockk<UniFile>(relaxed = true)
        every { fileSystem.getMangaDirectorySnapshot("Work A") } returns
            LocalSourceFileSystem.DirectorySnapshot(
                directory = workDirectory,
                files = listOf(
                    pageFile("cover.jpg", 1L),
                    pageFile("001.webp", 2L),
                    pageFile("002.webp", 3L),
                ),
                isAccessible = true,
            )

        val update = source.getMangaUpdate(
            manga = SManga.create().apply {
                url = "Work A"
                title = "Work A"
            },
            chapters = emptyList(),
            fetchDetails = false,
            fetchChapters = true,
        )

        assertEquals(listOf("Work A/$LOCAL_SINGLE_CHAPTER_NAME"), update.chapters.map { it.url })
        // The cover the app wrote into the folder is not one of the pages.
        assertEquals(
            JsonPrimitive(2),
            update.chapters.single().memo?.get("mihon.pageCount"),
        )
    }

    @Test
    fun `a folder with its own chapter keeps it and ignores loose pages`() = runBlocking {
        val workDirectory = mockk<UniFile>(relaxed = true)
        val chapterDirectory = mockk<UniFile>(relaxed = true) {
            every { name } returns "01篇"
            every { isDirectory } returns true
        }
        every { fileSystem.getMangaDirectorySnapshot("Work A") } returns
            LocalSourceFileSystem.DirectorySnapshot(
                directory = workDirectory,
                files = listOf(chapterDirectory, pageFile("stray.webp", 1L)),
                isAccessible = true,
            )

        val update = source.getMangaUpdate(
            manga = SManga.create().apply {
                url = "Work A"
                title = "Work A"
            },
            chapters = emptyList(),
            fetchDetails = false,
            fetchChapters = true,
        )

        assertEquals(listOf("Work A/01篇"), update.chapters.map { it.url })
    }

    private fun pageFile(name: String, lastModified: Long): UniFile = mockk(relaxed = true) {
        every { this@mockk.name } returns name
        every { isDirectory } returns false
        every { this@mockk.lastModified() } returns lastModified
        every { length() } returns 10L
    }

    private fun chapterFile(name: String): UniFile = mockk(relaxed = true) {
        every { this@mockk.name } returns name
        every { isDirectory } returns false
        every { lastModified() } returns 0L
        every { length() } returns 0L
        every { uri } returns mockk(relaxed = true)
    }

    companion object {
        /**
         * LocalSource resolves its preference store eagerly while constructing, before any test
         * body runs, so the store has to be in place before the first instance is built.
         */
        @BeforeAll
        @JvmStatic
        fun registerPreferenceStore() {
            Injekt.addSingleton<PreferenceStore>(InMemoryPreferenceStore())
        }
    }
}
