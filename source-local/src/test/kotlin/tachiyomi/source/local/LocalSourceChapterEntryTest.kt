package tachiyomi.source.local

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import mihon.core.archive.ArchiveEntry
import mihon.core.archive.ArchiveReader
import mihon.core.archive.archiveReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.storage.service.LocalSourceDirectoryEntryState
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.Archive
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LOCAL_SINGLE_CHAPTER_NAME
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

class LocalSourceChapterEntryTest {

    private val context = mockk<Context> {
        every { contentResolver } returns mockk<ContentResolver>(relaxed = true)
        // The source resolves its display name eagerly, which asks the context for a string.
        every { resources } returns mockk<Resources>(relaxed = true)
    }
    private val fileSystem = mockk<LocalSourceFileSystem>(relaxed = true)
    private val coverManager = mockk<LocalCoverManager>(relaxed = true)

    private val source = LocalSource(
        context = context,
        fileSystem = fileSystem,
        coverManager = coverManager,
    )

    private val manga = SManga.create().apply { title = "Test Manga" }

    @Test
    fun `same search follows translation edits and recovers from a failed query`(
        @TempDir privateFiles: File,
    ) = runBlocking {
        val repository = mockk<ChapterRepository>()
        Injekt.addSingleton<ChapterRepository>(repository)
        val root = mockk<UniFile> { every { lastModified() } returns 100L }
        val work = mockk<UniFile> {
            every { name } returns "Work"
            every { isDirectory } returns true
            every { lastModified() } returns 99L
        }
        val chapter = chapterFile("Original.cbz")
        val snapshot = LocalSourceFileSystem.DirectorySnapshot(work, listOf(chapter), isAccessible = true)
        every { context.filesDir } returns privateFiles
        every { fileSystem.getBaseDirectoryIdentityUri() } returns "root"
        every { fileSystem.getCachedBaseDirectoryIdentityUri() } returns "root"
        every { fileSystem.getBaseDirectory() } returns root
        every { fileSystem.getBaseDirectorySnapshot(any()) } returns
            LocalSourceFileSystem.DirectorySnapshot(root, listOf(work), isAccessible = true)
        every { fileSystem.getDirectorySnapshot(work, any()) } returns snapshot
        every { fileSystem.getMangaDirectorySnapshot("Work") } returns snapshot
        every { coverManager.find("Work") } returns null
        val translations = mapOf("Work" to listOf("Translated title"))

        coEvery { repository.getTranslatedNamesBySourceId(LocalSource.ID) } returns emptyMap()
        assertEquals(listOf("Work"), source.getPopularMangaUrls())
        coVerify(exactly = 0) { repository.getTranslatedNamesBySourceId(LocalSource.ID) }
        assertEquals(emptyList<String>(), source.getSearchMangaUrls("Translated"))
        coEvery { repository.getTranslatedNamesBySourceId(LocalSource.ID) } returns translations
        assertEquals(listOf("Work"), source.getSearchMangaUrls("Translated"))
        coEvery { repository.getTranslatedNamesBySourceId(LocalSource.ID) } throws IOException("temporary failure")
        assertEquals(emptyList<String>(), source.getSearchMangaUrls("Translated"))
        coEvery { repository.getTranslatedNamesBySourceId(LocalSource.ID) } returns translations
        assertEquals(listOf("Work"), source.getSearchMangaUrls("Translated"))
        coEvery { repository.getTranslatedNamesBySourceId(LocalSource.ID) } returns emptyMap()
        assertEquals(emptyList<String>(), source.getSearchMangaUrls("Translated"))
        assertEquals(listOf("Work"), source.getSearchMangaUrls("Original"))
        verify(exactly = 1) { fileSystem.getMangaDirectorySnapshot("Work") }
    }

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
    fun `a single unreadable emoji manga folder does not block listing the rest of the local library`(
        @TempDir privateFiles: File,
    ) = runBlocking {
        val root = mockk<UniFile> { every { lastModified() } returns 100L }
        val goodWork = mockk<UniFile> {
            every { name } returns "Good Collection"
            every { isDirectory } returns true
            every { lastModified() } returns 99L
        }
        val brokenWork = mockk<UniFile> {
            every { name } returns "Broken 🔥 Collection"
            every { isDirectory } returns true
            every { lastModified() } throws IllegalStateException("unreadable emoji entry")
        }
        val chapter = chapterFile("01.cbz")
        every { context.filesDir } returns privateFiles
        every { fileSystem.getBaseDirectoryIdentityUri() } returns "root-emoji-test"
        every { fileSystem.getCachedBaseDirectoryIdentityUri() } returns "root-emoji-test"
        every { fileSystem.getBaseDirectory() } returns root
        every { fileSystem.getBaseDirectorySnapshot(any()) } returns
            LocalSourceFileSystem.DirectorySnapshot(root, listOf(brokenWork, goodWork), isAccessible = true)
        every { fileSystem.getDirectorySnapshot(goodWork, any()) } returns
            LocalSourceFileSystem.DirectorySnapshot(goodWork, listOf(chapter), isAccessible = true)
        every { coverManager.find("Good Collection") } returns null

        val urls = source.getPopularMangaUrls()

        assertEquals(listOf("Good Collection"), urls)
    }

    @Test
    fun `archive metadata and pages use one reader and one header pass`() {
        val chapter = chapterFile("Chapter 1.cbz")
        val reader = mockk<ArchiveReader>(relaxed = true)
        val unreadableInfo = object : InputStream() {
            override fun read(): Int = throw IOException("unreadable ComicInfo")
        }
        every { reader.visitEntryHeaders(any()) } answers {
            val visit = firstArg<(ArchiveEntry, InputStream) -> Unit>()
            visit(ArchiveEntry("01.JPG", true), unreadableInfo)
            visit(ArchiveEntry("ComicInfo.xml", true), unreadableInfo)
            visit(ArchiveEntry("02.webp", true), unreadableInfo)
            visit(ArchiveEntry(".thumb/03.png", true), unreadableInfo)
            visit(ArchiveEntry("04.png", false), unreadableInfo)
        }
        mockkStatic(UniFile::archiveReader) {
            every { chapter.archiveReader(context) } returns reader

            val entry = source.buildChapterEntry(manga, chapter)

            assertEquals(2, entry.pageCount)
            assertEquals("Chapter 1", entry.displayName)
            assertFalse(entry.hasComicInfo)
            verify(exactly = 1) { chapter.archiveReader(context) }
            verify(exactly = 1) { reader.visitEntryHeaders(any()) }
            verify(exactly = 0) { reader.getInputStream(any()) }
            verify(exactly = 1) { reader.close() }
        }
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

    @Test
    fun `an unavailable child read remains unknown when the provider cannot confirm absence`() = runBlocking {
        val work = mockk<UniFile>(relaxed = true)
        every { fileSystem.getDirectorySnapshot(work, any()) } returns
            LocalSourceFileSystem.DirectorySnapshot(work, emptyList(), isAccessible = false)
        every { fileSystem.createChapterEntryStateLookup(work) } returns { LocalSourceDirectoryEntryState.UNKNOWN }

        assertNull(source.observeChapterFolder(work, 5L, setOf("Story.cbz"), previouslyPopulated = true, fresh = false))
        verify(exactly = 3) { fileSystem.getDirectorySnapshot(work, fresh = true) }
    }

    @Test
    fun `a partial child list is retried when an omitted chapter still exists`() = runBlocking {
        val work = mockk<UniFile>(relaxed = true)
        val first = chapterFile("First.cbz")
        val second = chapterFile("Second.cbz")
        every { fileSystem.getDirectorySnapshot(work, fresh = false) } returns
            LocalSourceFileSystem.DirectorySnapshot(work, listOf(first), isAccessible = true)
        every { fileSystem.getDirectorySnapshot(work, fresh = true) } returns
            LocalSourceFileSystem.DirectorySnapshot(work, listOf(first, second), isAccessible = true)
        every { fileSystem.createChapterEntryStateLookup(work) } returns { LocalSourceDirectoryEntryState.EXISTS }

        val result = source.observeChapterFolder(work, 5L, setOf("First.cbz", "Second.cbz"), true, fresh = false)
        assertEquals(setOf("First.cbz", "Second.cbz"), result?.state?.chapterFileNames)
    }

    @Test
    fun `a confirmed disappearance of the last archive produces an empty work`() = runBlocking {
        val work = mockk<UniFile>(relaxed = true)
        every { fileSystem.getDirectorySnapshot(work, any()) } returns
            LocalSourceFileSystem.DirectorySnapshot(work, emptyList(), isAccessible = true)
        every { fileSystem.createChapterEntryStateLookup(work) } returns { LocalSourceDirectoryEntryState.MISSING }

        val result = source.observeChapterFolder(work, 5L, setOf("Story.cbz"), true, fresh = false)
        assertEquals(emptySet<String>(), result?.state?.chapterFileNames)
        assertTrue(result?.snapshot?.isConfirmedEmpty == true)
    }

    @Test
    fun `manual observation detects a same-name replacement without opening the archive`() = runBlocking {
        val work = mockk<UniFile>(relaxed = true)
        val chapter = chapterFile("Story.cbz")
        every { chapter.length() } returnsMany listOf(100L, 200L)
        every { fileSystem.getDirectorySnapshot(work, any()) } returns
            LocalSourceFileSystem.DirectorySnapshot(work, listOf(chapter), isAccessible = true)

        val before = source.observeChapterFolder(work, 5L, setOf("Story.cbz"), true, fresh = false)
        val after = source.observeChapterFolder(work, 5L, setOf("Story.cbz"), true, fresh = false)

        assertNotNull(before)
        assertNotNull(after)
        assertEquals(before?.state?.chapterFileNames, after?.state?.chapterFileNames)
        assertNotEquals(before?.state?.fingerprint, after?.state?.fingerprint)
        verify(exactly = 0) { context.contentResolver.openFileDescriptor(any(), any()) }
    }

    @Test
    fun `refresh generates a new work cover before anyone opens its details`() = runBlocking {
        val work = mockk<UniFile>(relaxed = true) {
            every { name } returns "New Work"
            every { isDirectory } returns true
            every { lastModified() } returns 10L
        }
        val chapter = mockk<UniFile>(relaxed = true) {
            every { name } returns "Chapter 1"
            every { isDirectory } returns true
        }
        val page = pageFile("001.png", 0L)
        val coverUri = mockk<Uri>(relaxed = true)
        val cover = mockk<UniFile> { every { uri } returns coverUri }
        every { fileSystem.getDirectorySnapshot(work, any()) } returns
            LocalSourceFileSystem.DirectorySnapshot(work, listOf(chapter), isAccessible = true)
        every { fileSystem.getFilesInDirectory(chapter) } returns listOf(page)
        every { page.openInputStream() } answers { byteArrayOf(1).inputStream() }
        every { coverManager.find("New Work") } returns null
        every { coverManager.update(any(), any()) } answers {
            secondArg<java.io.InputStream>().close()
            cover
        }

        val observation = checkNotNull(
            source.observeChapterFolder(work, 10L, emptySet(), previouslyPopulated = false, fresh = false),
        )
        assertEquals(setOf("Chapter 1"), observation.state.chapterFileNames)
        val result = source.resolveScannedMangaCover("New Work", observation)

        verify(exactly = 1) { coverManager.update(match { it.url == "New Work" }, any()) }
        assertEquals(coverUri.toString(), result)
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
