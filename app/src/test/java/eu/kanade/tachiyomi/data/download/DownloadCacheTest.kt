package eu.kanade.tachiyomi.data.download

import android.app.Application
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File

class DownloadCacheTest {

    @TempDir
    lateinit var directory: File

    private val nodes = mutableMapOf<String, Node>()
    private val uris = mutableMapOf<String, Uri>()
    private val app = mockk<Application>()
    private val source = mockk<HttpSource>()
    private val provider = mockk<DownloadProvider>()
    private val sourceManager = mockk<SourceManager>()
    private val extensionManager = mockk<ExtensionManager>()
    private val storage = mockk<StorageManager>()
    private val ready = MutableStateFlow(true)
    private lateinit var root: Node
    private lateinit var sourceDir: Node
    private lateinit var mangaDir: Node

    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class)
        mockkStatic(UniFile::class)
        every { Uri.parse(any()) } answers { uri(firstArg()) }
        every { UniFile.fromUri(any(), any()) } answers { nodes[secondArg<Uri>().toString()]?.file }
        every { app.cacheDir } returns directory
        Injekt.addSingleton<Application>(app)
        root = node("downloads")
        sourceDir = node("source")
        mangaDir = node("manga")
        root.children = listOf(sourceDir)
        sourceDir.children = listOf(mangaDir)
        mangaDir.children = listOf(node("first"), node("second.cbz", isDirectory = false))
        every { source.id } returns 1L
        every { sourceManager.isInitialized } returns ready
        every { sourceManager.getOnlineSources() } returns listOf(source)
        every { sourceManager.getStubSources() } returns emptyList()
        every { sourceManager.get(1L) } returns source
        every { extensionManager.isInitialized } returns MutableStateFlow(true)
        every { storage.getDownloadsDirectory() } answers { root.file }
        every { storage.changes } returns MutableSharedFlow<Unit>()
        every { provider.getSourceDirName(source) } returns "source"
        every { provider.getMangaDirName(any()) } answers { firstArg() }
        every { provider.getValidChapterDirNames(any(), any(), any()) } answers { listOf(firstArg()) }
        every { provider.findSourceDir(source) } returns sourceDir.file
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(UniFile::class)
        unmockkStatic(Uri::class)
    }

    @Test
    fun `normal reads use snapshot without rescanning and ignore incomplete downloads`() = runTest {
        mangaDir.children = mangaDir.children!! + node("third_tmp") + node("cover.jpg", isDirectory = false)
        val cache = cache(backgroundScope)
        cache.getTotalDownloadCount()
        runCurrent()
        assertEquals(2, cache.getTotalDownloadCount())
        val scans = nodes.values.sumOf { it.scans }
        repeat(100) {
            assertEquals(2, cache.getDownloadCount(manga()))
            assertTrue(downloaded(cache, "first"))
            assertFalse(downloaded(cache, "third_tmp"))
        }
        runCurrent()
        assertEquals(scans, nodes.values.sumOf { it.scans })
    }

    @Test
    fun `unreadable and transient empty scans preserve the last snapshot on every level`() = runTest {
        val cache = cache(backgroundScope)
        cache.getTotalDownloadCount()
        runCurrent()
        for (target in listOf(root, sourceDir, mangaDir)) {
            val children = target.children
            target.children = null
            cache.invalidateCache()
            runCurrent()
            assertEquals(2, cache.getTotalDownloadCount())
            target.children = emptyList()
            cache.invalidateCache()
            runCurrent()
            assertEquals(2, cache.getTotalDownloadCount())
            target.children = children
        }
        mangaDir.children = mangaDir.children!! + node("third")
        cache.invalidateCache()
        runCurrent()
        assertEquals(3, cache.getTotalDownloadCount())
    }

    @Test
    fun `confirmed external deletions can empty chapter manga and source directories`() = runTest {
        val cache = cache(backgroundScope)
        cache.getTotalDownloadCount()
        runCurrent()
        mangaDir.children!!.forEach { it.exists = false }
        mangaDir.children = emptyList()
        cache.invalidateCache()
        runCurrent()
        assertEquals(0, cache.getTotalDownloadCount())
        cache.addChapter("new", mangaDir.file, manga())
        assertEquals(1, cache.getTotalDownloadCount())
        mangaDir.exists = false
        sourceDir.children = emptyList()
        cache.invalidateCache()
        runCurrent()
        assertEquals(0, cache.getTotalDownloadCount())
        cache.addChapter("new", mangaDir.file, manga())
        sourceDir.exists = false
        root.children = emptyList()
        cache.invalidateCache()
        runCurrent()
        assertEquals(0, cache.getTotalDownloadCount())
    }

    @Test
    fun `chapter rename batch removal and manga move keep counts and names consistent`() = runTest {
        val cache = cache(backgroundScope)
        cache.getTotalDownloadCount()
        runCurrent()
        cache.renameChapter(chapter("first"), chapter("renamed"), setOf("renamed"), mangaDir.file, manga())
        assertFalse(downloaded(cache, "first"))
        assertTrue(downloaded(cache, "renamed"))
        assertEquals(2, cache.getDownloadCount(manga()))
        val moved = node("moved")
        cache.renameManga(manga(), moved.file, "moved")
        assertEquals(0, cache.getDownloadCount(manga()))
        assertEquals(2, cache.getDownloadCount(manga("moved")))
        cache.removeChapters(listOf(chapter("renamed"), chapter("second")), manga("moved"))
        assertEquals(0, cache.getTotalDownloadCount())
        cache.addChapter("added", moved.file, manga("moved"))
        cache.removeManga(manga("moved"))
        assertEquals(0, cache.getTotalDownloadCount())
        cache.addChapter("again", moved.file, manga("moved"))
        cache.removeSource(source)
        assertEquals(0, cache.getTotalDownloadCount())
    }

    @Test
    fun `cold restore precedes mutation and latest snapshot survives another restart`() = runTest {
        val first = cache(backgroundScope)
        first.getTotalDownloadCount()
        runCurrent()
        advanceTimeBy(1001)
        runCurrent()
        val scans = nodes.values.sumOf { it.scans }
        val restored = cache(backgroundScope)
        restored.getTotalDownloadCount()
        val mutation =
            launch(start = CoroutineStart.UNDISPATCHED) { restored.addChapter("third", mangaDir.file, manga()) }
        assertTrue(mutation.isActive)
        runCurrent()
        assertEquals(3, restored.getTotalDownloadCount())
        advanceTimeBy(1001)
        runCurrent()
        val restarted = cache(backgroundScope)
        runCurrent()
        assertEquals(3, restarted.getTotalDownloadCount())
        assertTrue(downloaded(restarted, "third"))
        assertEquals(scans, nodes.values.sumOf { it.scans })
    }

    @Test
    fun `failed or canceled renewal cannot overwrite saved snapshot or later changes`() = runTest {
        val cache = cache(backgroundScope)
        cache.getTotalDownloadCount()
        runCurrent()
        ready.value = false
        cache.invalidateCache()
        runCurrent()
        cache.addChapter("third", mangaDir.file, manga())
        cache.invalidateCache()
        runCurrent()
        advanceTimeBy(30001)
        runCurrent()
        assertEquals(3, cache.getTotalDownloadCount())
        advanceTimeBy(1001)
        runCurrent()
        val restored = cache(backgroundScope)
        runCurrent()
        assertEquals(3, restored.getTotalDownloadCount())
        ready.value = true
        mangaDir.children = mangaDir.children!! + node("fourth")
        cache.invalidateCache()
        runCurrent()
        assertEquals(3, cache.getTotalDownloadCount())
        assertTrue(downloaded(cache, "fourth"))
        assertFalse(downloaded(cache, "third"))
    }

    @Test
    fun `explicit invalidation before cold restore still scans changed files`() = runTest {
        val first = cache(backgroundScope)
        first.getTotalDownloadCount()
        runCurrent()
        advanceTimeBy(1001)
        runCurrent()
        mangaDir.children = listOf(node("replacement"))
        val restored = cache(backgroundScope)
        restored.invalidateCache()
        runCurrent()
        assertEquals(1, restored.getTotalDownloadCount())
        assertTrue(downloaded(restored, "replacement"))
        assertFalse(downloaded(restored, "first"))
    }

    @Test
    fun `invalidation during an old scan publishes only the replacement scan`() = runTest {
        val cache = cache(backgroundScope)
        cache.getTotalDownloadCount()
        runCurrent()
        mangaDir.onList = {
            mangaDir.onList = null
            mangaDir.children = listOf(node("replacement"))
            cache.invalidateCache()
        }
        cache.invalidateCache()
        runCurrent()
        assertEquals(1, cache.getTotalDownloadCount())
        assertTrue(downloaded(cache, "replacement"))
        assertFalse(downloaded(cache, "first"))
        advanceTimeBy(1001)
        runCurrent()
        val restored = cache(backgroundScope)
        runCurrent()
        assertEquals(1, restored.getTotalDownloadCount())
        assertTrue(downloaded(restored, "replacement"))
    }

    @Test
    fun `changing download root cannot restore a snapshot from the previous root`() = runTest {
        val first = cache(backgroundScope)
        first.getTotalDownloadCount()
        runCurrent()
        advanceTimeBy(1001)
        runCurrent()
        root = node("new-root")
        val changed = cache(backgroundScope)
        runCurrent()
        assertEquals(0, changed.getTotalDownloadCount())
        runCurrent()
        assertEquals(0, changed.getTotalDownloadCount())
    }

    @Test
    fun `large library batch moves deletes and cold recovery do not trigger ordinary scans`() = runTest {
        val chapters = List(4) { node("chapter-$it.cbz", isDirectory = false) }
        val entries = List(3002) { node("manga-$it").apply { children = chapters } }
        sourceDir.children = entries
        val cache = cache(backgroundScope)
        cache.getTotalDownloadCount()
        runCurrent()
        assertEquals(12008, cache.getTotalDownloadCount())
        repeat(100) { index ->
            val oldName = "manga-$index"
            val newName = "moved-$index"
            cache.renameManga(manga(oldName), node(newName).file, newName)
            assertEquals(0, cache.getDownloadCount(manga(oldName)))
            assertEquals(4, cache.getDownloadCount(manga(newName)))
        }
        repeat(100) { cache.removeManga(manga("moved-$it")) }
        assertEquals(11608, cache.getTotalDownloadCount())
        val scans = nodes.values.sumOf { it.scans }
        advanceTimeBy(1001)
        runCurrent()
        val restored = cache(backgroundScope)
        runCurrent()
        assertEquals(11608, restored.getTotalDownloadCount())
        assertEquals(4, restored.getDownloadCount(manga("manga-3001")))
        assertEquals(scans, nodes.values.sumOf { it.scans })
    }

    private fun cache(scope: CoroutineScope) = DownloadCache(
        app,
        provider,
        sourceManager,
        extensionManager,
        storage,
        scope,
    )

    private fun manga(title: String = "manga") = Manga.create().copy(id = 1L, source = 1L, title = title)

    private fun chapter(name: String) = Chapter.create().copy(name = name, url = name)

    private fun downloaded(cache: DownloadCache, name: String) = cache.isChapterDownloaded(
        name,
        null,
        name,
        "manga",
        1L,
        false,
    )

    private fun uri(value: String): Uri = uris.getOrPut(value) {
        mockk<Uri>().also { uri -> every { uri.toString() } returns value }
    }

    private fun node(name: String, isDirectory: Boolean = true): Node = Node(name, isDirectory).also {
        nodes[it.address] = it
    }

    private inner class Node(name: String, isDirectory: Boolean) {
        val address = "content://downloads/$name"
        val file = mockk<UniFile>()
        var children: List<Node>? = emptyList()
        var exists = true
        var scans = 0
        var onList: (() -> Unit)? = null

        init {
            every { file.name } returns name
            every { file.uri } returns uri(address)
            every { file.isDirectory } returns isDirectory
            every { file.isFile } returns !isDirectory
            every { file.exists() } answers { exists }
            every { file.listFiles() } answers {
                scans++
                val result = children?.map { it.file }?.toTypedArray()
                onList?.invoke()
                result
            }
            every { file.findFile(any()) } answers {
                children?.firstOrNull { it.file.name == firstArg<String>() && it.exists }?.file
                    ?: nodes["content://downloads/${firstArg<String>()}"]?.takeIf { it.exists }?.file
            }
        }
    }
}
