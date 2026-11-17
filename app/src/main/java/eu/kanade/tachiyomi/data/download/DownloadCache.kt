package eu.kanade.tachiyomi.data.download

import android.app.Application
import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Cache where we dump the downloads directory from the filesystem. This class is needed because
 * directory checking is expensive and it slows down the app. The cache is invalidated by the time
 * defined in [renewInterval] as we don't have any control over the filesystem and the user can
 * delete the folders at any time without the app noticing.
 */
class DownloadCache(
    private val context: Context,
    private val provider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .onStart { emit(Unit) }
        .shareIn(scope, SharingStarted.Lazily, 1)

    /**
     * The interval after which this cache should be invalidated. 1 hour shouldn't cause major
     * issues, as the cache is only used for UI feedback.
     */
    private val renewInterval = 1.hours.inWholeMilliseconds

    /**
     * The last time the cache was refreshed.
     */
    @Volatile
    private var lastRenew = 0L

    @Volatile
    private var renewalJob: Job? = null

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing = _isInitializing
        .debounce(1.seconds) // Don't notify if it finishes quickly enough
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val diskCacheFile: File
        get() = File(context.cacheDir, "dl_index_cache_v3")

    private val rootDownloadsDirMutex = Mutex()

    @Volatile
    private var rootDownloadsDir = RootDirectory(storageManager.getDownloadsDirectory())

    private val initJob = scope.launch {
        rootDownloadsDirMutex.withLock {
            try {
                if (diskCacheFile.exists()) {
                    val diskCache = diskCacheFile.inputStream().use {
                        ProtoBuf.decodeFromByteArray<RootDirectory>(it.readBytes())
                    }
                    if (diskCache.dir?.uri == rootDownloadsDir.dir?.uri) {
                        rootDownloadsDir = diskCache
                        lastRenew = System.currentTimeMillis()
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Failed to initialize from disk cache" }
                diskCacheFile.delete()
            }
        }
    }

    init {
        storageManager.changes
            .onEach { invalidateCache() }
            .launchIn(scope)
    }

    /**
     * Returns true if the chapter is downloaded.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param chapterUrl the url of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param sourceId the id of the source of the chapter.
     * @param skipCache whether to skip the directory cache and check in the filesystem.
     */
    fun isChapterDownloaded(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        sourceId: Long,
        skipCache: Boolean,
    ): Boolean {
        if (skipCache) {
            val source = sourceManager.getOrStub(sourceId)
            return provider.findChapterDir(chapterName, chapterScanlator, chapterUrl, mangaTitle, source) != null
        }

        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[sourceId]
        if (sourceDir != null) {
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(mangaTitle)]
            if (mangaDir != null) {
                return provider.getValidChapterDirNames(
                    chapterName,
                    chapterScanlator,
                    chapterUrl,
                ).any { it in mangaDir.chapterDirs }
            }
        }
        return false
    }

    /**
     * Returns the amount of downloaded chapters.
     */
    fun getTotalDownloadCount(): Int {
        renewCache()

        return rootDownloadsDir.sourceDirs.values.sumOf { sourceDir ->
            sourceDir.mangaDirs.values.sumOf { mangaDir ->
                mangaDir.chapterDirs.size
            }
        }
    }

    /**
     * Returns the amount of downloaded chapters for a manga.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCount(manga: Manga): Int {
        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[manga.source]
        if (sourceDir != null) {
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.title)]
            if (mangaDir != null) {
                return mangaDir.chapterDirs.size
            }
        }
        return 0
    }

    /**
     * Adds a chapter that has just been download to this cache.
     *
     * @param chapterDirName the downloaded chapter's directory name.
     * @param mangaUniFile the directory of the manga.
     * @param manga the manga of the chapter.
     */
    suspend fun addChapter(chapterDirName: String, mangaUniFile: UniFile, manga: Manga) {
        updateChapterCache(setOf(chapterDirName), mangaUniFile, manga, emptySet())
    }

    suspend fun renameChapter(
        oldChapter: Chapter,
        newChapter: Chapter,
        chapterDirNames: Set<String>,
        mangaUniFile: UniFile,
        manga: Manga,
    ) {
        val removedNames = buildSet {
            addAll(provider.getValidChapterDirNames(oldChapter.name, oldChapter.scanlator, oldChapter.url))
            addAll(provider.getValidChapterDirNames(newChapter.name, newChapter.scanlator, newChapter.url))
        }
        updateChapterCache(chapterDirNames, mangaUniFile, manga, removedNames)
    }

    private suspend fun updateChapterCache(
        chapterDirNames: Set<String>,
        mangaUniFile: UniFile,
        manga: Manga,
        removedNames: Set<String>,
    ) {
        initJob.join()
        rootDownloadsDirMutex.withLock {
            // Retrieve the cached source directory or cache a new one
            var sourceDir = rootDownloadsDir.sourceDirs[manga.source]
            if (sourceDir == null) {
                val source = sourceManager.get(manga.source) ?: return
                val sourceUniFile = provider.findSourceDir(source) ?: return
                sourceDir = SourceDirectory(sourceUniFile)
            }

            // Retrieve the cached manga directory or cache a new one
            val mangaDirName = provider.getMangaDirName(manga.title)
            var mangaDir = sourceDir.mangaDirs[mangaDirName]
            if (mangaDir == null) {
                mangaDir = MangaDirectory(mangaUniFile)
            }

            // Save the chapter directory
            val updatedManga = mangaDir.copy(chapterDirs = mangaDir.chapterDirs - removedNames + chapterDirNames)
            val updatedSource = sourceDir.copy(mangaDirs = sourceDir.mangaDirs + (mangaDirName to updatedManga))
            rootDownloadsDir =
                rootDownloadsDir.copy(sourceDirs = rootDownloadsDir.sourceDirs + (manga.source to updatedSource))
        }

        notifyChanges()
    }

    /**
     * Removes a chapter that has been deleted from this cache.
     *
     * @param chapter the chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapter(chapter: Chapter, manga: Manga) {
        removeChapters(listOf(chapter), manga)
    }

    /**
     * Removes a list of chapters that have been deleted from this cache.
     *
     * @param chapters the list of chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapters(chapters: List<Chapter>, manga: Manga) {
        initJob.join()
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaName = provider.getMangaDirName(manga.title)
            val mangaDir = sourceDir.mangaDirs[mangaName] ?: return
            val removed = chapters.flatMap { provider.getValidChapterDirNames(it.name, it.scanlator, it.url) }.toSet()
            val updatedSource = sourceDir.copy(
                mangaDirs =
                sourceDir.mangaDirs + (mangaName to mangaDir.copy(chapterDirs = mangaDir.chapterDirs - removed)),
            )
            rootDownloadsDir =
                rootDownloadsDir.copy(sourceDirs = rootDownloadsDir.sourceDirs + (manga.source to updatedSource))
        }

        notifyChanges()
    }

    /**
     * Removes a manga that has been deleted from this cache.
     *
     * @param manga the manga to remove.
     */
    suspend fun removeManga(manga: Manga) {
        initJob.join()
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDirName = provider.getMangaDirName(manga.title)
            rootDownloadsDir = rootDownloadsDir.copy(
                sourceDirs =
                rootDownloadsDir.sourceDirs +
                    (manga.source to sourceDir.copy(mangaDirs = sourceDir.mangaDirs - mangaDirName)),
            )
        }

        notifyChanges()
    }

    /**
     * Renames a manga in this cache.
     *
     * @param manga the manga being renamed.
     * @param mangaUniFile the manga's new directory.
     * @param newTitle the manga's new title.
     */
    suspend fun renameManga(manga: Manga, mangaUniFile: UniFile, newTitle: String) {
        initJob.join()
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val oldMangaDirName = provider.getMangaDirName(manga.title)
            var oldChapterDirs: Set<String>? = null
            // Save the old name's cached chapter dirs
            if (sourceDir.mangaDirs.containsKey(oldMangaDirName)) {
                oldChapterDirs = sourceDir.mangaDirs[oldMangaDirName]?.chapterDirs
            }

            // Retrieve/create the cached manga directory for new name
            val newMangaDirName = provider.getMangaDirName(newTitle)
            var mangaDir = sourceDir.mangaDirs[newMangaDirName]
            if (mangaDir == null) {
                mangaDir = MangaDirectory(mangaUniFile)
            }

            // Add the old chapters to new name's cache
            val updatedManga = mangaDir.copy(
                dir = mangaUniFile,
                chapterDirs =
                mangaDir.chapterDirs + oldChapterDirs.orEmpty(),
            )
            val updatedSource = sourceDir.copy(
                mangaDirs =
                sourceDir.mangaDirs - oldMangaDirName + (newMangaDirName to updatedManga),
            )
            rootDownloadsDir =
                rootDownloadsDir.copy(sourceDirs = rootDownloadsDir.sourceDirs + (manga.source to updatedSource))
        }

        notifyChanges()
    }

    suspend fun removeSource(source: Source) {
        initJob.join()
        rootDownloadsDirMutex.withLock {
            rootDownloadsDir = rootDownloadsDir.copy(sourceDirs = rootDownloadsDir.sourceDirs - source.id)
        }

        notifyChanges()
    }

    @Synchronized
    fun invalidateCache() {
        lastRenew = 0L
        renewalJob?.cancel()
        renewCache(force = true)
    }

    /**
     * Renews the downloads cache.
     */
    @Synchronized
    private fun renewCache(force: Boolean = false) {
        // Avoid renewing cache if in the process nor too often
        if ((!force && lastRenew + renewInterval >= System.currentTimeMillis()) || renewalJob?.isActive == true) {
            return
        }

        val previous = renewalJob
        val next = scope.launch(start = CoroutineStart.LAZY) {
            withContext(NonCancellable) { previous?.join() }
            ensureActive()
            initJob.join()
            if (!force && lastRenew + renewInterval >= System.currentTimeMillis()) {
                notifyChanges()
                return@launch
            }
            try {
                if (lastRenew == 0L) {
                    _isInitializing.emit(true)
                }

                // Try to wait until extensions and sources have loaded
                val sources = withTimeoutOrNull(30.seconds) {
                    extensionManager.isInitialized.first { it }
                    sourceManager.isInitialized.first { it }

                    getSources()
                } ?: throw IOException("Sources are not initialized")

                val sourceMap = sources.associate { provider.getSourceDirName(it).lowercase() to it.id }

                rootDownloadsDirMutex.withLock {
                    val updatedRootDir = RootDirectory(storageManager.getDownloadsDirectory())

                    val previousRoot = rootDownloadsDir.takeIf { it.dir?.uri == updatedRootDir.dir?.uri }
                    updatedRootDir.sourceDirs = readDownloadDirectory(updatedRootDir.dir) {
                        previousRoot?.sourceDirs?.values.orEmpty().any { it.dir?.exists() == true }
                    }
                        .filter { it.isDirectory && !it.name.isNullOrBlank() }
                        .mapNotNull { dir ->
                            val sourceId = sourceMap[dir.name!!.lowercase()]
                            sourceId?.let { it to SourceDirectory(dir) }
                        }
                        .toMap()

                    coroutineScope {
                        updatedRootDir.sourceDirs.map { (sourceId, sourceDir) ->
                            async {
                                val previousSource = previousRoot?.sourceDirs?.get(sourceId)
                                sourceDir.mangaDirs = readDownloadDirectory(sourceDir.dir) {
                                    previousSource?.mangaDirs?.values.orEmpty().any { it.dir?.exists() == true }
                                }
                                    .filter { it.isDirectory && !it.name.isNullOrBlank() }
                                    .associate { it.name!! to MangaDirectory(it) }

                                sourceDir.mangaDirs.forEach { (mangaName, mangaDir) ->
                                    val chapterDirs = readDownloadDirectory(mangaDir.dir) {
                                        previousSource?.mangaDirs?.get(mangaName)?.chapterDirs.orEmpty().any { name ->
                                            mangaDir.dir?.findFile(name)?.exists() == true ||
                                                mangaDir.dir?.findFile("$name.cbz")?.exists() == true
                                        }
                                    }
                                        .mapNotNull {
                                            when {
                                                // Ignore incomplete downloads
                                                it.name?.endsWith(Downloader.TMP_DIR_SUFFIX) == true -> null
                                                // Folder of images
                                                it.isDirectory -> it.name
                                                // CBZ files
                                                it.isFile && it.extension == "cbz" -> it.nameWithoutExtension
                                                // Anything else is irrelevant
                                                else -> null
                                            }
                                        }
                                        .toSet()

                                    mangaDir.chapterDirs = chapterDirs
                                }
                            }
                        }
                            .awaitAll()
                    }

                    ensureActive()
                    rootDownloadsDir = updatedRootDir
                    synchronized(this@DownloadCache) {
                        if (renewalJob === coroutineContext[Job]) lastRenew = System.currentTimeMillis()
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                synchronized(this@DownloadCache) {
                    if (renewalJob === coroutineContext[Job]) lastRenew = System.currentTimeMillis()
                }
                logcat(LogPriority.ERROR, error) { "DownloadCache: failed to create cache" }
            } finally {
                synchronized(this@DownloadCache) {
                    if (renewalJob === coroutineContext[Job]) {
                        _isInitializing.value = false
                        notifyChanges()
                    }
                }
            }
        }
        renewalJob = next
        next.start()

        // Mainly to notify the indexing notifier UI
        notifyChanges()
    }

    private fun getSources(): List<Source> {
        return sourceManager.getOnlineSources() + sourceManager.getStubSources()
    }

    private fun notifyChanges() {
        _changes.trySend(Unit)
        updateDiskCache()
    }

    private var updateDiskCacheJob: Job? = null

    @Synchronized
    private fun updateDiskCache() {
        updateDiskCacheJob?.cancel()
        updateDiskCacheJob = scope.launch {
            delay(1.seconds)
            try {
                initJob.join()
                rootDownloadsDirMutex.withLock {
                    val bytes = ProtoBuf.encodeToByteArray(rootDownloadsDir)
                    ensureActive()
                    writeDownloadIndex(diskCacheFile, bytes, beforeCommit = { ensureActive() })
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(
                    priority = LogPriority.ERROR,
                    throwable = e,
                    message = { "Failed to write disk cache file" },
                )
            }
        }
    }
}

/**
 * Class to store the files under the root downloads directory.
 */
@Serializable
private data class RootDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var sourceDirs: Map<Long, SourceDirectory> = mapOf(),
)

/**
 * Class to store the files under a source directory.
 */
@Serializable
private data class SourceDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var mangaDirs: Map<String, MangaDirectory> = mapOf(),
)

/**
 * Class to store the files under a manga directory.
 */
@Serializable
private data class MangaDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var chapterDirs: Set<String> = emptySet(),
)

private object UniFileAsStringSerializer : KSerializer<UniFile?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UniFile", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UniFile?) {
        return if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.uri.toString())
        }
    }

    override fun deserialize(decoder: Decoder): UniFile? {
        return if (decoder.decodeNotNullMark()) {
            UniFile.fromUri(Injekt.get<Application>(), decoder.decodeString().toUri())
        } else {
            decoder.decodeNull()
        }
    }
}
