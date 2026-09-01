package tachiyomi.source.local

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalPageOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import nl.adaptivity.xmlutil.core.AndroidXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.SearchTextNormalizer.containsSearch
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.copyFromComicInfo
import tachiyomi.core.metadata.comicinfo.getComicInfo
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.LocalSourceDirectoryEntryState
import tachiyomi.i18n.MR
import tachiyomi.source.local.filter.OrderBy
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.Archive
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.metadata.fillMetadata
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import tachiyomi.domain.source.model.Source as DomainSource

class LocalChapterSyncScan internal constructor(
    val changedMangaUrls: Set<String>,
    val removedMangaUrls: Set<String>,
    val previousChapterFileNamesByMangaUrl: Map<String, Set<String>>,
    val chapterFileNamesByMangaUrl: Map<String, Set<String>>,
    internal val folderStates: Map<String, LocalChapterFolderState>,
    internal val baseUri: String?,
    internal val baseDirectoryLastModified: Long,
    val isReliable: Boolean,
)

internal data class LocalChapterFolderState(
    val directoryLastModified: Long,
    val fingerprint: String,
    val chapterFileNames: Set<String>?,
)

data class LocalListingSnapshot(
    val allUrls: List<String> = emptyList(),
    val latestUrls: List<String> = emptyList(),
)

data class LocalMangaDirectorySnapshot(
    val urls: Set<String>,
    val signature: String,
    val fromListingFallback: Boolean,
)

internal fun chapterFileSetChanged(
    existingChapterUrls: Collection<String>,
    currentChapterUrls: Set<String>,
    isAccessible: Boolean,
    isConfirmedEmpty: Boolean,
): Boolean {
    if (!isAccessible) return false
    if (currentChapterUrls.isEmpty() && existingChapterUrls.isNotEmpty() && !isConfirmedEmpty) return false
    return currentChapterUrls != existingChapterUrls.toSet()
}

private const val MAX_CONCURRENT_DIRECTORY_REMOVAL_CHECKS = 16

internal suspend fun confirmMissingLocalMangaDirectoriesGone(
    missingNames: Set<String>,
    directoryState: suspend (String) -> LocalSourceDirectoryEntryState,
): Boolean = coroutineScope {
    val semaphore = Semaphore(MAX_CONCURRENT_DIRECTORY_REMOVAL_CHECKS)
    missingNames.map { name ->
        async {
            semaphore.withPermit {
                runCatching { directoryState(name) == LocalSourceDirectoryEntryState.MISSING }
                    .getOrDefault(false)
            }
        }
    }.awaitAll().all { it }
}

class LocalSource(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
    private val coverManager: LocalCoverManager,
) : Source, UnmeteredSource {

    private val json: Json by injectLazy()
    private val xml: XML by injectLazy()

    /**
     * Only consulted to enrich search with manually assigned chapter translated names. Lazy so the
     * source stays constructible before the database module is registered.
     */
    private val chapterRepository: ChapterRepository by injectLazy()

    @Volatile
    private var cachedListing: List<LocalMangaEntry>? = null

    private val listingSnapshotInternal = MutableStateFlow(LocalListingSnapshot())
    val listingSnapshot: StateFlow<LocalListingSnapshot> = listingSnapshotInternal

    @Volatile
    private var cachedListingTime: Long = 0

    @Volatile
    private var activeBaseUri: String? = fileSystem.getBaseDirectoryIdentityUri()

    @Volatile
    private var cachedListingBaseUri: String? = activeBaseUri

    private val baseIdentityLock = Any()

    @Volatile
    private var cachedBaseDirLastModified: Long = -1

    @Volatile
    private var listingInvalidated = false

    @Volatile
    private var lastListingRefreshAttempt = 0L

    private val listingMutex = Mutex()

    @Volatile
    private var cachedBaseDirectorySnapshot: LocalSourceFileSystem.DirectorySnapshot? = null

    @Volatile
    private var cachedBaseDirectorySnapshotTime: Long = 0

    private val baseDirectorySnapshotMutex = Mutex()

    /**
     * Memoizes the fully derived page (filtered + sorted listing converted to [SManga]) for the
     * current listing snapshot and sort/query parameters. Building this for the whole library is
     * expensive and was repeated on every pager refresh and on every toolbar-count recomputation
     * even though the underlying listing hadn't changed. The memo is keyed by the listing
     * instance, so it follows the exact same lifetime as the listing cache itself and is
     * discarded automatically whenever the listing gets rebuilt or the sort/query changes.
     */
    @Volatile
    private var cachedDerivedListing: CachedDerivedListing? = null

    @Volatile
    private var cachedChapterNames: Map<String, List<String>>? = null

    @Volatile
    private var cachedChapterNamesTime: Long = 0

    @Volatile
    private var cachedChapterNamesBaseDirLastModified: Long = -1

    @Volatile
    private var cachedChapterNamesBaseUri: String? = null

    private val chapterNamesMutex = Mutex()

    private val listingIndexFile: File by lazy {
        File(context.filesDir, "local_source_listing_index.json")
    }

    private val chapterIndexFile: File by lazy {
        File(context.filesDir, "local_source_chapter_index.json")
    }

    private val chapterNamesIndexFile: File by lazy {
        File(context.filesDir, "local_source_chapter_names_index.json")
    }

    /**
     * Persisted record of each manga folder's mtime at the last chapter sync, so a change in one
     * folder can be detected and synced incrementally without rescanning the whole library.
     */
    private val syncIndexFile: File by lazy {
        File(context.filesDir, "local_source_sync_index.json")
    }

    @Volatile
    private var cachedChapterIndex: Map<String, ChapterIndex>? = null

    @Volatile
    private var cachedChapterIndexBaseUri: String? = null

    private val chapterIndexMutex = Mutex()
    private val chapterIndexBuildMutexes = Array(MAX_CONCURRENT_CHAPTER_INDEX_BUILDS) { Mutex() }
    private val chapterIndexScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var chapterIndexSaveJob: Job? = null
    private var chapterIndexDirty = false

    private val preferenceStore: PreferenceStore by injectLazy()

    private val orderByIndexPreference: Preference<Int> = preferenceStore.getInt(
        "local_source_order_by_index",
        0,
    )

    private val orderByAscendingPreference: Preference<Boolean> = preferenceStore.getBoolean(
        "local_source_order_by_ascending",
        true,
    )

    private fun ensureBaseDirectoryIdentity() {
        val currentBaseUri = fileSystem.getBaseDirectoryIdentityUri()
        if (activeBaseUri == currentBaseUri) return
        synchronized(baseIdentityLock) {
            if (activeBaseUri == currentBaseUri) return
            activeBaseUri = currentBaseUri
            cachedListing = null
            cachedListingBaseUri = currentBaseUri
            cachedListingTime = 0L
            cachedBaseDirLastModified = -1L
            cachedDerivedListing = null
            listingInvalidated = false
            lastListingRefreshAttempt = 0L
            cachedBaseDirectorySnapshot = null
            cachedBaseDirectorySnapshotTime = 0L
            listingSnapshotInternal.value = LocalListingSnapshot()
            cachedChapterNames = null
            cachedChapterNamesTime = 0L
            cachedChapterNamesBaseDirLastModified = -1L
            cachedChapterNamesBaseUri = currentBaseUri
            chapterIndexSaveJob?.cancel()
            chapterIndexSaveJob = null
            cachedChapterIndex = null
            cachedChapterIndexBaseUri = currentBaseUri
            chapterIndexDirty = false
        }
    }

    private suspend fun getListing(): List<LocalMangaEntry> = withIOContext {
        ensureBaseDirectoryIdentity()
        val now = System.currentTimeMillis()

        // The persisted listing is the local library's startup snapshot. Render it immediately;
        // the browse screen checks the directory signature in the background and invalidates this
        // cache only when manga folders were actually added, removed, or renamed.
        cachedListing?.takeIf {
            cachedListingBaseUri == activeBaseUri &&
                !listingInvalidated && now - cachedListingTime < LISTING_MAX_AGE.inWholeMilliseconds
        }
            ?.let { return@withIOContext it }
        if (cachedListing == null && !listingInvalidated) {
            val baseUri = fileSystem.getBaseDirectoryIdentityUri()
            val persisted = loadListingIndex(baseUri)
                ?.takeIf { it.entries.isNotEmpty() && now - it.builtAt in 0 until LISTING_MAX_AGE.inWholeMilliseconds }
                ?: recoverListingIndexFromChapterNames(baseUri, now)
            persisted?.let {
                val entries = it.toListingEntries()
                cachedListing = entries
                cachedListingBaseUri = baseUri
                publishListingSnapshot(entries)
                cachedListingTime = now
                cachedBaseDirLastModified = it.baseDirLastModified
                return@withIOContext entries
            }
        }

        cachedListing?.takeIf {
            listingInvalidated && now - lastListingRefreshAttempt < LISTING_RETRY_COOLDOWN.inWholeMilliseconds
        }?.let { return@withIOContext it }
        if (listingInvalidated) lastListingRefreshAttempt = now

        val snapshot = getBaseDirectorySnapshot()
        val baseUri = fileSystem.getBaseDirectoryIdentityUri()
        val baseDirLastModified = snapshot.lastModified

        val cached = cachedListing
        if (cached != null && isListingFresh(now, cachedListingTime, baseDirLastModified)) {
            return@withIOContext cached
        }

        listingMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            val lockedCached = cachedListing
            if (lockedCached != null && isListingFresh(lockedNow, cachedListingTime, baseDirLastModified)) {
                return@withLock lockedCached
            }

            // Cold start: reuse the persisted listing when the base directory hasn't changed,
            // so re-entering the local source is instant instead of rescanning every directory.
            val persisted = if (lockedCached == null) {
                loadListingIndex(baseUri)?.takeIf {
                    !snapshot.isAccessible || it.isFresh(baseDirLastModified, lockedNow)
                }
            } else {
                null
            }
            if (persisted != null) {
                val entries = persisted.toListingEntries()
                cachedListing = entries
                cachedListingBaseUri = baseUri
                publishListingSnapshot(entries)
                cachedListingTime = lockedNow
                cachedBaseDirLastModified = baseDirLastModified
                return@withLock entries
            }

            if (!snapshot.isAccessible) {
                return@withLock lockedCached
                    ?: loadListingIndex(baseUri)?.toListingEntries()
                    ?: emptyList()
            }

            buildListing(snapshot.files, baseUri, lockedNow, baseDirLastModified)
        }
    }

    /** Forces the next pager load to rebuild the listing after a confirmed directory change. */
    fun invalidateListing() {
        listingInvalidated = true
        cachedDerivedListing = null
        lastListingRefreshAttempt = 0L
        cachedBaseDirectorySnapshot = null
        cachedBaseDirectorySnapshotTime = 0L
    }

    private suspend fun getBaseDirectorySnapshot(
        forceRefresh: Boolean = false,
    ): LocalSourceFileSystem.DirectorySnapshot {
        ensureBaseDirectoryIdentity()
        val now = System.currentTimeMillis()
        val cached = cachedBaseDirectorySnapshot
        if (!forceRefresh && cached != null && now - cachedBaseDirectorySnapshotTime < BASE_SNAPSHOT_CACHE_MILLIS) {
            return cached
        }

        return baseDirectorySnapshotMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            val lockedCached = cachedBaseDirectorySnapshot
            if (
                !forceRefresh &&
                lockedCached != null &&
                lockedNow - cachedBaseDirectorySnapshotTime < BASE_SNAPSHOT_CACHE_MILLIS
            ) {
                return@withLock lockedCached
            }

            readBaseDirectorySnapshot().also {
                cachedBaseDirectorySnapshot = it
                cachedBaseDirectorySnapshotTime = lockedNow
            }
        }
    }

    private suspend fun readBaseDirectorySnapshot(): LocalSourceFileSystem.DirectorySnapshot {
        var snapshot = fileSystem.getBaseDirectorySnapshot()
        for (retryDelay in EMPTY_DIRECTORY_RETRY_DELAYS) {
            if (!snapshot.isAccessible || snapshot.files.isNotEmpty()) return snapshot
            delay(retryDelay)
            snapshot = fileSystem.getBaseDirectorySnapshot()
        }

        return snapshot
    }

    /**
     * Updates the cover URI for a single manga in the in-memory and persisted listing caches.
     * This is intentionally narrow: cover edits rewrite a file inside the manga folder without
     * changing the base directory mtime, so the normal listing cache would otherwise keep the
     * old cover URI until the next full rescan.
     */
    suspend fun refreshMangaCover(mangaUrl: String, coverUri: String?) = withIOContext {
        listingMutex.withLock {
            cachedListing = cachedListing?.map { entry ->
                if (entry.url == mangaUrl) entry.copy(coverUri = coverUri) else entry
            }
            updatePersistedListingCover(mangaUrl, coverUri)
        }
    }

    /** Generates one collection cover during an explicit import, never during an ordinary listing read. */
    suspend fun ensureMangaCover(mangaUrl: String, chapterFileName: String): String? {
        val coverUri = withIOContext {
            coverManager.find(mangaUrl)?.uri?.toString()?.let { return@withIOContext it }
            val chapterFile = fileSystem.getMangaDirectory(mangaUrl)
                ?.findFile(chapterFileName)
                ?.takeIf(::isChapterFile)
                ?: return@withIOContext null
            val manga = SManga.create().apply {
                title = mangaUrl
                url = mangaUrl
            }
            val chapter = SChapter.create().apply {
                name = chapterFileName.substringBeforeLast('.', chapterFileName)
                url = "$mangaUrl/$chapterFileName"
            }
            updateCover(chapter, manga)?.uri?.toString()
        }
        if (coverUri != null) refreshMangaCover(mangaUrl, coverUri)
        return coverUri
    }

    /**
     * Rebuilds the shelf from the same reliable chapter scan used by manual refresh. This keeps
     * additions, deletions, empty folders, counts, and paging totals on one authoritative result
     * without doing a second full-library cover pass.
     */
    suspend fun refreshListing(scan: LocalChapterSyncScan): Boolean = withIOContext {
        if (!scan.isReliable) return@withIOContext false
        listingMutex.withLock {
            val snapshot = getBaseDirectorySnapshot(forceRefresh = true)
            if (!snapshot.isAccessible) return@withLock false
            val snapshotNames = snapshot.files
                .asSequence()
                .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
                .mapNotNull { it.name }
                .toSet()
            if (!snapshotNames.containsAll(scan.chapterFileNamesByMangaUrl.keys)) {
                return@withLock false
            }
            val baseUri = fileSystem.getBaseDirectoryIdentityUri()
            buildListing(
                baseFiles = snapshot.files,
                baseUri = baseUri,
                now = System.currentTimeMillis(),
                baseDirLastModified = snapshot.lastModified,
                scannedChapterFileNames = scan.chapterFileNamesByMangaUrl,
                forceChapterRefreshUrls = scan.changedMangaUrls,
                allowEmptyListing = true,
            )
            true
        }
    }

    private fun ListingIndex.toListingEntries(): List<LocalMangaEntry> {
        return entries.map { (name, entry) ->
            LocalMangaEntry(
                title = name,
                url = name,
                lastModified = entry.dirLastModified,
                latestChapterModified = entry.latestChapterModified,
                coverUri = entry.coverUri,
                chapterCount = entry.chapterCount,
            )
        }
    }

    private fun isListingFresh(now: Long, cachedTime: Long, baseDirLastModified: Long): Boolean {
        // If the base directory mtime can't be determined, fall back to a short TTL so we
        // never rescan on every read.
        if (baseDirLastModified < 0) {
            return now - cachedTime < LISTING_CACHE_TTL.inWholeMilliseconds
        }
        // Otherwise trust the cache while the base directory itself is unchanged. Stating the
        // directory on every read is cheap, so manga added/removed/renamed while the app is
        // running show up without a manual refresh. Use the long max age (not the short TTL):
        // a full rescan over a large library (thousands of folders) is expensive, and chapter
        // files added/removed inside an existing manga folder are picked up via the per-manga
        // chapter cache / the "refresh all chapters" action instead of a full listing rescan.
        return cachedBaseDirLastModified >= 0 &&
            cachedBaseDirLastModified == baseDirLastModified &&
            now - cachedTime < LISTING_MAX_AGE.inWholeMilliseconds
    }

    private fun ListingIndex.isFresh(baseDirLastModified: Long, now: Long): Boolean {
        if (baseDirLastModified < 0 || baseDirLastModified != this.baseDirLastModified) return false
        return now - builtAt in 0 until LISTING_MAX_AGE.inWholeMilliseconds
    }

    private suspend fun buildListing(
        baseFiles: List<UniFile>,
        baseUri: String?,
        now: Long,
        baseDirLastModified: Long,
        scannedChapterFileNames: Map<String, Set<String>>? = null,
        forceChapterRefreshUrls: Set<String> = emptySet(),
        allowEmptyListing: Boolean = false,
    ): List<LocalMangaEntry> {
        val index = if (baseUri != null) loadListingIndex(baseUri) else null
        val persistedChapterNames = loadPersistedChapterNamesIndex()

        val dirs = baseFiles
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            .filter { dir -> shouldIncludeLocalMangaDirectory(dir.name.orEmpty(), scannedChapterFileNames) }
            .distinctBy { it.name }

        if (
            !allowEmptyListing &&
            shouldReuseListingAfterUnexpectedEmptyScan(
                scannedDirectoryCount = dirs.size,
                persistedEntryCount = index?.entries?.size ?: 0,
            )
        ) {
            val entries = index!!.toListingEntries()
            cachedListing = entries
            cachedListingBaseUri = baseUri
            publishListingSnapshot(entries)
            cachedListingTime = now
            cachedBaseDirLastModified = baseDirLastModified
            listingInvalidated = false
            return entries
        }

        val coverLookups = Semaphore(MAX_CONCURRENT_COVER_LOOKUPS)
        val results = coroutineScope {
            dirs.map { dir ->
                async {
                    coverLookups.withPermit {
                        val name = dir.name.orEmpty()
                        val dirLastModified = dir.lastModified()
                        val indexed = index?.entries?.get(name)
                        val directoryMetadataUnchanged = indexed != null && indexed.dirLastModified == dirLastModified
                        val coverUri = if (directoryMetadataUnchanged) {
                            indexed.coverUri
                        } else {
                            coverManager.find(name)?.uri?.toString()
                        }
                        val measuredChapterStats = if (
                            directoryMetadataUnchanged &&
                            name !in forceChapterRefreshUrls &&
                            indexed.chapterCount >= 0 &&
                            indexed.latestChapterModified > 0
                        ) {
                            ChapterListingStats(indexed.chapterCount, indexed.latestChapterModified)
                        } else {
                            getChapterListingStats(
                                dir = dir,
                                knownChapterFileNames = persistedChapterNames[name].orEmpty(),
                            )
                        }
                        val chapterStats = measuredChapterStats.copy(
                            count = resolvedLocalChapterCount(
                                scannedChapterFiles = scannedChapterFileNames?.get(name),
                                measuredChapterCount = measuredChapterStats.count,
                                previousConfirmedChapterCount = indexed?.chapterCount,
                            ),
                            latestModified = if (
                                scannedChapterFileNames == null &&
                                measuredChapterStats.count == 0 &&
                                indexed?.chapterCount?.let { it > 0 } == true
                            ) {
                                indexed.latestChapterModified
                            } else {
                                measuredChapterStats.latestModified
                            },
                        )
                        name to ListingIndexEntry(
                            dirLastModified = dirLastModified,
                            coverUri = coverUri,
                            chapterCount = chapterStats.count,
                            latestChapterModified = chapterStats.latestModified,
                        )
                    }
                }
            }.awaitAll()
        }

        val nonEmptyResults = results.filter { (_, indexed) -> indexed.chapterCount > 0 }

        val entries = nonEmptyResults.map { (name, indexed) ->
            LocalMangaEntry(
                title = name,
                url = name,
                lastModified = indexed.dirLastModified,
                latestChapterModified = indexed.latestChapterModified,
                coverUri = indexed.coverUri,
                chapterCount = indexed.chapterCount,
            )
        }

        if (baseUri != null) {
            saveListingIndex(
                ListingIndex(
                    baseUri = baseUri,
                    entries = nonEmptyResults.toMap(),
                    baseDirLastModified = baseDirLastModified,
                    builtAt = now,
                ),
            )
        }

        cachedListing = entries
        cachedListingBaseUri = baseUri
        cachedDerivedListing = null
        publishListingSnapshot(entries)
        cachedListingTime = now
        cachedBaseDirLastModified = baseDirLastModified
        listingInvalidated = false
        return entries
    }

    private fun publishListingSnapshot(entries: List<LocalMangaEntry>) {
        val latestLimit = System.currentTimeMillis() - LATEST_THRESHOLD
        listingSnapshotInternal.value = LocalListingSnapshot(
            allUrls = entries.map(LocalMangaEntry::url),
            latestUrls = entries.filter { it.latestChapterModified >= latestLimit }.map(LocalMangaEntry::url),
        )
    }

    private fun updatePersistedListingCover(mangaUrl: String, coverUri: String?) {
        val index = loadListingIndex() ?: return
        val entry = index.entries[mangaUrl] ?: return
        val updated = index.copy(
            entries = index.entries + (mangaUrl to entry.copy(coverUri = coverUri)),
        )
        saveListingIndex(updated)
    }

    private data class ListingIndex(
        val baseUri: String,
        val entries: Map<String, ListingIndexEntry>,
        val baseDirLastModified: Long,
        val builtAt: Long,
    )

    private data class ListingIndexEntry(
        val dirLastModified: Long,
        val coverUri: String?,
        val chapterCount: Int,
        val latestChapterModified: Long,
    )

    private fun loadListingIndex(expectedBaseUri: String? = null): ListingIndex? {
        return try {
            if (!listingIndexFile.exists()) return null
            val root = JSONObject(listingIndexFile.readText())
            if (root.optInt("version", -1) != LISTING_INDEX_VERSION) return null
            val baseUri = root.optString("baseUri")
            if (expectedBaseUri != null && baseUri != expectedBaseUri) return null
            val entries = mutableMapOf<String, ListingIndexEntry>()
            val array = root.optJSONArray("entries") ?: return null
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (name.isEmpty()) continue
                entries[name] = ListingIndexEntry(
                    dirLastModified = item.optLong("dirLastModified", 0L),
                    coverUri = item.optString("coverUri").takeIf { it.isNotEmpty() },
                    chapterCount = item.optInt("chapterCount", -1),
                    latestChapterModified = item.optLong("latestChapterModified", 0L),
                )
            }
            ListingIndex(
                baseUri = baseUri,
                entries = entries,
                baseDirLastModified = root.optLong("baseDirLastModified", -1L),
                builtAt = root.optLong("builtAt", 0L),
            )
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to load local source listing index" }
            null
        }
    }

    /**
     * Repairs an empty listing index from the older chapter-name index. This path is intentionally
     * metadata-only: it avoids touching thousands of SAF directories and keeps existing database
     * covers intact until a later healthy directory scan refreshes the full listing.
     */
    private fun recoverListingIndexFromChapterNames(baseUri: String?, now: Long): ListingIndex? {
        if (baseUri == null || !chapterNamesIndexFile.exists()) return null
        return try {
            val root = JSONObject(chapterNamesIndexFile.readText())
            if (root.optInt("version", -1) != CHAPTER_NAMES_INDEX_VERSION) return null
            if (root.optString("baseUri") != baseUri) return null
            val mangaObject = root.optJSONObject("manga") ?: return null
            val entries = buildMap {
                mangaObject.keys().forEach { url ->
                    val chapterCount = mangaObject.optJSONArray(url)?.length() ?: 0
                    if (url.isNotEmpty() && chapterCount > 0) {
                        put(
                            url,
                            ListingIndexEntry(
                                dirLastModified = 0L,
                                coverUri = null,
                                chapterCount = chapterCount,
                                latestChapterModified = 0L,
                            ),
                        )
                    }
                }
            }
            if (entries.isEmpty()) return null
            ListingIndex(
                baseUri = baseUri,
                entries = entries,
                baseDirLastModified = root.optLong("baseDirLastModified", -1L),
                builtAt = now,
            ).also(::saveListingIndex)
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to recover local listing from chapter names" }
            null
        }
    }

    private fun saveListingIndex(index: ListingIndex) {
        try {
            val array = JSONArray()
            index.entries.forEach { (name, entry) ->
                array.put(
                    JSONObject()
                        .put("name", name)
                        .put("dirLastModified", entry.dirLastModified)
                        .put("coverUri", entry.coverUri.orEmpty())
                        .put("chapterCount", entry.chapterCount)
                        .put("latestChapterModified", entry.latestChapterModified),
                )
            }
            val root = JSONObject()
                .put("version", LISTING_INDEX_VERSION)
                .put("baseUri", index.baseUri)
                .put("baseDirLastModified", index.baseDirLastModified)
                .put("builtAt", index.builtAt)
                .put("entries", array)

            writeIndexAtomically(listingIndexFile, root.toString())
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to save local source listing index" }
        }
    }

    private data class LocalMangaEntry(
        val title: String,
        val url: String,
        val lastModified: Long,
        val latestChapterModified: Long,
        val coverUri: String?,
        val chapterCount: Int,
    )

    private data class CachedDerivedListing(
        val listing: List<LocalMangaEntry>,
        val query: String,
        val sortByTitle: Boolean,
        val ascending: Boolean,
        val latestWindow: Boolean,
        val mangas: List<SManga>,
    )

    override val name: String = context.stringResource(MR.strings.local_source)

    override val id: Long = ID

    override val lang: String = "other"

    override fun toString() = name

    override val supportsLatest: Boolean = true

    // Browse related
    override suspend fun getPopularManga(page: Int): MangasPage {
        return getSearchMangaInternal(page, "", FilterList(currentOrderBy()), latestWindow = false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return getSearchMangaInternal(page, "", FilterList(currentOrderBy()), latestWindow = true)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        return getSearchMangaInternal(page, query, filters, latestWindow = false)
    }

    suspend fun getPopularMangaUrls(): List<String> {
        return getSearchMangaList("", FilterList(currentOrderBy()), latestWindow = false).map(SManga::url)
    }

    suspend fun getLatestMangaUrls(): List<String> {
        return getSearchMangaList("", FilterList(currentOrderBy()), latestWindow = true).map(SManga::url)
    }

    suspend fun getSearchMangaUrls(query: String, filters: FilterList): List<String> {
        return getSearchMangaList(query, filters, latestWindow = false).map(SManga::url)
    }

    /**
     * Persists the given order-by filter selection so it applies globally to the local source,
     * even if the filter sheet is closed without applying.
     */
    fun persistOrderBySelection(filters: FilterList) {
        val orderBy = filters.filterIsInstance<OrderBy>().firstOrNull()
        val selection = orderBy?.state
        if (selection != null) {
            if (selection.index != orderByIndexPreference.get()) {
                orderByIndexPreference.set(selection.index)
            }
            if (selection.ascending != orderByAscendingPreference.get()) {
                orderByAscendingPreference.set(selection.ascending)
            }
        }
    }

    private fun currentOrderBy(): OrderBy {
        val selection = Filter.Sort.Selection(orderByIndexPreference.get(), orderByAscendingPreference.get())
        return if (orderByIndexPreference.get() == 0) {
            OrderBy.Popular(context, selection)
        } else {
            OrderBy.Latest(context, selection)
        }
    }

    /**
     * Restores the default order for a listing tab: name ascending for the
     * popular/browse tab and date descending for the latest tab. This keeps a
     * leftover sort preference from silently applying to every tab.
     */
    fun resetOrderBy(popular: Boolean) {
        if (popular) {
            if (orderByIndexPreference.get() != 0) orderByIndexPreference.set(0)
            if (!orderByAscendingPreference.get()) orderByAscendingPreference.set(true)
        } else {
            if (orderByIndexPreference.get() != 1) orderByIndexPreference.set(1)
            if (orderByAscendingPreference.get()) orderByAscendingPreference.set(false)
        }
    }

    /**
     * Returns a map of manga url to its chapter file names (without extensions).
     * Used to find a manga by searching for a chapter name. Cached like the
     * listing so repeated paging loads don't rescan directories.
     */
    private suspend fun getChapterNamesIndex(): Map<String, List<String>> = withIOContext {
        ensureBaseDirectoryIdentity()
        val now = System.currentTimeMillis()
        val cached = cachedChapterNames
        if (cached != null && chapterNamesIndexFresh(now)) {
            return@withIOContext cached
        }
        chapterNamesMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            val lockedCached = cachedChapterNames
            if (lockedCached != null && chapterNamesIndexFresh(lockedNow)) {
                return@withLock lockedCached
            }

            // Cold start: reuse the persisted index when the base directory hasn't changed, so
            // the first search after launching the app doesn't rescan every manga directory.
            val baseDirectory = fileSystem.getBaseDirectory()
            val baseUri = fileSystem.getBaseDirectoryIdentityUri()
            val baseDirLastModified = baseDirectory?.lastModified() ?: -1L
            // Even when the in-memory copy went stale, the persisted index is cheap to read
            // back, so only rescan directories when the base directory actually changed.
            val persisted = loadChapterNamesIndex(baseUri, baseDirLastModified, lockedNow)
            if (persisted != null) {
                cachedChapterNames = persisted
                cachedChapterNamesTime = lockedNow
                cachedChapterNamesBaseDirLastModified = baseDirLastModified
                cachedChapterNamesBaseUri = baseUri
                return@withLock persisted
            }

            val listing = getListing()
            val semaphore = Semaphore(MAX_CONCURRENT_CHAPTER_NAME_LOOKUPS)
            val scanned = coroutineScope {
                listing.map { entry ->
                    async {
                        semaphore.withPermit {
                            val url = entry.url
                            try {
                                val names = fileSystem.getFilesInMangaDirectory(url)
                                    .filterNot { it.name.orEmpty().startsWith('.') }
                                    .filter {
                                        it.isDirectory || Archive.isSupported(it) ||
                                            it.extension.equals("epub", true)
                                    }
                                    .mapNotNull { file ->
                                        val base = if (file.isDirectory) {
                                            file.name.orEmpty()
                                        } else {
                                            file.nameWithoutExtension.orEmpty()
                                        }
                                        base.takeIf { it.isNotEmpty() }
                                    }
                                url to names
                            } catch (e: Exception) {
                                url to emptyList()
                            }
                        }
                    }
                }.awaitAll()
            }
            val index = buildMap {
                scanned.forEach { (url, names) ->
                    if (names.isNotEmpty()) {
                        put(url, names)
                    }
                }
            }
            cachedChapterNames = index
            cachedChapterNamesTime = lockedNow
            cachedChapterNamesBaseDirLastModified = baseDirLastModified
            cachedChapterNamesBaseUri = baseUri
            saveChapterNamesIndex(index, baseUri, baseDirLastModified, lockedNow)
            index
        }
    }

    /**
     * Returns the manually assigned Chinese translated names (中文译名) of local chapters, keyed by
     * manga url - the same url used by the listing and by [getChapterNamesIndex], so all three
     * sources of a match line up.
     *
     * The database is an enhancement here, not the source of truth: the listing itself always comes
     * from the file system, so a failed lookup degrades to matching on file names alone rather than
     * failing the search.
     */
    private suspend fun getTranslatedNamesIndex(): Map<String, List<String>> {
        return try {
            chapterRepository.getTranslatedNamesBySourceId(ID)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to load local source translated names" }
            emptyMap()
        }
    }

    /**
     * Whether the in-memory chapter names index is still usable. Falls back to the short TTL
     * when the base directory mtime can't be determined; otherwise trusts the index only while
     * the base directory is unchanged, so manga added/removed/renamed on disk are picked up by
     * chapter-name search without a manual refresh.
     */
    private fun chapterNamesIndexFresh(now: Long): Boolean {
        if (cachedChapterNamesBaseUri != fileSystem.getBaseDirectoryIdentityUri()) return false
        val baseDirLastModified = fileSystem.getBaseDirectory()?.lastModified() ?: -1L
        if (baseDirLastModified < 0) {
            return now - cachedChapterNamesTime < LISTING_CACHE_TTL.inWholeMilliseconds
        }
        return cachedChapterNamesBaseDirLastModified >= 0 &&
            cachedChapterNamesBaseDirLastModified == baseDirLastModified &&
            now - cachedChapterNamesTime < LISTING_MAX_AGE.inWholeMilliseconds
    }

    private fun loadChapterNamesIndex(
        baseUri: String?,
        baseDirLastModified: Long,
        now: Long,
    ): Map<String, List<String>>? {
        if (baseDirLastModified < 0 || !chapterNamesIndexFile.exists()) return null
        return try {
            val root = JSONObject(chapterNamesIndexFile.readText())
            if (root.optInt("version", -1) != CHAPTER_NAMES_INDEX_VERSION) return null
            if (root.optString("baseUri").takeIf(String::isNotEmpty) != baseUri) return null
            if (root.optLong("baseDirLastModified", -1L) != baseDirLastModified) return null
            val builtAt = root.optLong("builtAt", 0L)
            if (now - builtAt !in 0 until LISTING_MAX_AGE.inWholeMilliseconds) return null
            val mangaObject = root.optJSONObject("manga") ?: return null
            val map = mutableMapOf<String, List<String>>()
            mangaObject.keys().forEach { url ->
                val array = mangaObject.optJSONArray(url) ?: return@forEach
                val names = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    val name = array.optString(i)
                    if (name.isNotEmpty()) names.add(name)
                }
                if (names.isNotEmpty()) map[url] = names
            }
            map
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to load local source chapter names index" }
            null
        }
    }

    private fun saveChapterNamesIndex(
        index: Map<String, List<String>>,
        baseUri: String?,
        baseDirLastModified: Long,
        now: Long,
    ) {
        try {
            val mangaObject = JSONObject()
            index.forEach { (url, names) ->
                mangaObject.put(url, JSONArray(names))
            }
            val root = JSONObject()
                .put("version", CHAPTER_NAMES_INDEX_VERSION)
                .put("baseUri", baseUri.orEmpty())
                .put("baseDirLastModified", baseDirLastModified)
                .put("builtAt", now)
                .put("manga", mangaObject)

            writeIndexAtomically(chapterNamesIndexFile, root.toString())
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to save local source chapter names index" }
        }
    }

    private suspend fun getSearchMangaInternal(
        page: Int,
        query: String,
        filters: FilterList,
        latestWindow: Boolean,
    ): MangasPage {
        val derived = getSearchMangaList(query, filters, latestWindow)
        val result = derived.localPage(page, PAGE_SIZE)
        return MangasPage(result.items, result.hasNextPage).apply {
            itemsBefore = result.itemsBefore
            itemsAfter = result.itemsAfter
        }
    }

    private suspend fun getSearchMangaList(
        query: String,
        filters: FilterList,
        latestWindow: Boolean,
    ): List<SManga> = withIOContext {
        // Persist the order-by selection chosen in the filter sheet globally
        persistOrderBySelection(filters)

        val sortByTitle = orderByIndexPreference.get() == 0
        val ascending = orderByAscendingPreference.get()

        val lastModifiedLimit = if (latestWindow) {
            System.currentTimeMillis() - LATEST_THRESHOLD
        } else {
            0L
        }

        val allEntries = getListing()

        // Reuse the previously derived page when nothing that feeds it changed. The listing
        // instance is compared by identity on purpose: `getListing()` keeps returning the same
        // cached instance until a rebuild, so this memo lives exactly as long as the listing
        // cache and is invalidated together with it.
        val cachedPage = cachedDerivedListing
        if (
            cachedPage != null &&
            cachedPage.listing === allEntries &&
            cachedPage.query == query &&
            cachedPage.sortByTitle == sortByTitle &&
            cachedPage.ascending == ascending &&
            cachedPage.latestWindow == latestWindow
        ) {
            return@withIOContext cachedPage.mangas
        }

        val matchedChapters = mutableMapOf<String, String>()
        var mangaEntries = allEntries

        // Latest window applies the time filter first (and now also keeps the query).
        if (lastModifiedLimit != 0L) {
            mangaEntries = mangaEntries.filter { it.latestChapterModified >= lastModifiedLimit }
        }

        if (query.isNotBlank()) {
            val chapterNames = getChapterNamesIndex()
            val translatedNames = getTranslatedNamesIndex()
            val matched = linkedMapOf<String, LocalMangaEntry>()
            mangaEntries.forEach { entry ->
                val match = localSearchMatch(
                    query = query,
                    title = entry.title,
                    chapterNames = chapterNames[entry.url].orEmpty(),
                    translatedNames = translatedNames[entry.url].orEmpty(),
                )
                if (match != null) {
                    matched[entry.url] = entry
                    match.matchedChapter?.let { matchedChapters[entry.url] = it }
                }
            }
            mangaEntries = matched.values.toList()
        }

        mangaEntries = if (sortByTitle) {
            // Same natural comparator used everywhere else by name: digit-leading titles
            // (e.g. "86", "3月") sort after English/CJK titles.
            if (ascending) {
                mangaEntries.sortedWith { a, b -> a.title.compareToCaseInsensitiveNaturalOrder(b.title) }
            } else {
                mangaEntries.sortedWith { a, b -> b.title.compareToCaseInsensitiveNaturalOrder(a.title) }
            }
        } else {
            if (ascending) {
                mangaEntries.sortedBy(LocalMangaEntry::latestChapterModified)
            } else {
                mangaEntries.sortedByDescending(LocalMangaEntry::latestChapterModified)
            }
        }

        val mangas = mangaEntries.map { entry ->
            SManga.create().apply {
                title = entry.title
                url = entry.url
                entry.coverUri?.let { thumbnail_url = it }
                if (entry.latestChapterModified > 0) {
                    memo = JsonObject(
                        memo.toMap() +
                            (LATEST_CHAPTER_TIME_KEY to JsonPrimitive(entry.latestChapterModified)),
                    )
                }
                matchedChapters[entry.url]?.let { chapter ->
                    memo = JsonObject(memo.toMap() + (MATCHED_CHAPTER_KEY to JsonPrimitive(chapter)))
                }
            }
        }

        mangas.also { derived ->
            cachedDerivedListing = CachedDerivedListing(
                listing = allEntries,
                query = query,
                sortByTitle = sortByTitle,
                ascending = ascending,
                latestWindow = latestWindow,
                mangas = derived,
            )
        }
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val directorySnapshot = if (fetchDetails || fetchChapters) {
            getMangaDirectorySnapshot(
                mangaUrl = manga.url,
                confirmEmptyChapters = fetchChapters,
                existingChapterUrls = chapters.map(SChapter::url),
            )
        } else {
            null
        }
        val asyncManga = if (fetchDetails) async { getMangaDetails(manga, directorySnapshot) } else null
        val asyncChapters = if (fetchChapters) {
            async { getChapterList(manga, chapters, directorySnapshot) }
        } else {
            null
        }
        SMangaUpdate(asyncManga?.await() ?: manga, asyncChapters?.await() ?: chapters)
    }

    // Manga details related
    private suspend fun getMangaDetails(
        manga: SManga,
        directorySnapshot: LocalSourceFileSystem.DirectorySnapshot? = null,
    ): SManga = withIOContext {
        coverManager.find(manga.url)?.let {
            manga.thumbnail_url = it.uri.toString()
        }

        // Augment manga details based on metadata files
        try {
            val snapshot = directorySnapshot ?: getMangaDirectorySnapshot(manga.url)
            if (!snapshot.isAccessible || snapshot.files.isEmpty()) return@withIOContext manga
            val mangaDir = snapshot.directory ?: return@withIOContext manga
            val mangaDirFiles = snapshot.files

            val comicInfoFile = mangaDirFiles
                .firstOrNull { it.name == COMIC_INFO_FILE }
            val noXmlFile = mangaDirFiles
                .firstOrNull { it.name == ".noxml" }
            val legacyJsonDetailsFile = mangaDirFiles
                .firstOrNull { it.extension == "json" }

            when {
                // Top level ComicInfo.xml
                comicInfoFile != null -> {
                    noXmlFile?.delete()
                    setMangaDetailsFromComicInfoFile(comicInfoFile.openInputStream(), manga)
                }

                // Old custom JSON format
                // TODO: remove support for this entirely after a while
                legacyJsonDetailsFile != null -> {
                    json.decodeFromStream<MangaDetails>(legacyJsonDetailsFile.openInputStream()).run {
                        title?.let { manga.title = it }
                        author?.let { manga.author = it }
                        artist?.let { manga.artist = it }
                        description?.let { manga.description = it }
                        genre?.let { manga.genre = it.joinToString() }
                        status?.let { manga.status = it }
                    }
                    // Replace with ComicInfo.xml file
                    val comicInfo = manga.getComicInfo()
                    mangaDir
                        .createFile(COMIC_INFO_FILE)
                        ?.openOutputStream()
                        ?.use {
                            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
                            it.write(comicInfoString.toByteArray())
                            legacyJsonDetailsFile.delete()
                        }
                }

                // Copy ComicInfo.xml from a chapter archive to top level if found.
                // Uses the chapter metadata cache, so only the archive that actually
                // contains a ComicInfo.xml is opened (or none, when there isn't one).
                noXmlFile == null -> {
                    val chapterFiles = mangaDirFiles.filter {
                        it.isDirectory || Archive.isSupported(it) || it.extension.equals("epub", true)
                    }
                    if (chapterFiles.isEmpty()) return@withIOContext manga
                    val entries = getChapterIndex(manga, chapterFiles)
                    val archiveWithComicInfo = entries.firstOrNull { entry ->
                        entry.hasComicInfo && chapterFiles.any {
                            it.name.orEmpty() == entry.name && Archive.isSupported(it)
                        }
                    }
                    val copiedFile = archiveWithComicInfo?.let { entry ->
                        val chapterFile = chapterFiles.first { it.name.orEmpty() == entry.name }
                        getComicInfoForChapter(chapterFile) f@{ stream ->
                            return@f copyComicInfoFile(stream, mangaDir)
                        }
                    }
                    if (copiedFile != null) {
                        setMangaDetailsFromComicInfoFile(copiedFile.openInputStream(), manga)
                    } else {
                        // Avoid re-scanning
                        mangaDir.createFile(".noxml")
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error setting manga details from local metadata for ${manga.title}" }
        }

        return@withIOContext manga
    }

    private fun <T> getComicInfoForChapter(chapter: UniFile, block: (InputStream) -> T): T? {
        return if (chapter.isDirectory) {
            chapter.findFile(COMIC_INFO_FILE)?.openInputStream()?.use(block)
        } else {
            chapter.archiveReader(context).use { reader ->
                reader.getInputStream(COMIC_INFO_FILE)?.use(block)
            }
        }
    }

    private fun copyComicInfoFile(comicInfoFileStream: InputStream, folder: UniFile): UniFile? {
        return folder.createFile(COMIC_INFO_FILE)?.apply {
            openOutputStream().use { outputStream ->
                comicInfoFileStream.use { it.copyTo(outputStream) }
            }
        }
    }

    private fun parseComicInfo(stream: InputStream): ComicInfo {
        return AndroidXmlReader(stream, StandardCharsets.UTF_8.name()).use {
            xml.decodeFromReader<ComicInfo>(it)
        }
    }

    private fun setMangaDetailsFromComicInfoFile(stream: InputStream, manga: SManga) {
        manga.copyFromComicInfo(parseComicInfo(stream))
    }

    private fun setChapterDetailsFromComicInfoFile(stream: InputStream, chapter: SChapter) {
        val comicInfo = parseComicInfo(stream)

        comicInfo.title?.let { chapter.name = it.value }
        comicInfo.number?.value?.toFloatOrNull()?.let { chapter.chapter_number = it }
        comicInfo.translator?.let { chapter.scanlator = it.value }
    }

    /**
     * Returns the last modified time of the base directory, or -1 if it can't be determined.
     * Used to detect manga being added/removed/renamed while the app is running.
     */
    suspend fun getBaseDirectoryLastModified(): Long = withIOContext {
        val directory = fileSystem.getBaseDirectory() ?: return@withIOContext -1L
        runCatching {
            directory.lastModified().takeIf { directory.exists() && directory.isDirectory } ?: -1L
        }.getOrDefault(-1L)
    }

    /**
     * Returns a stable signature of the manga directories directly under the local source root.
     * Directory-provider mtimes are intentionally excluded because some providers change them on
     * every query, which would make an unchanged library look perpetually dirty.
     */
    suspend fun getMangaDirectorySnapshot(): LocalMangaDirectorySnapshot? = withIOContext {
        val snapshot = getBaseDirectorySnapshot(forceRefresh = true)
        if (!snapshot.isAccessible) return@withIOContext null
        val names = snapshot.files
            .asSequence()
            .filter { it.isDirectory }
            .mapNotNull { it.name?.takeIf(String::isNotEmpty) }
            .filterNot { it.startsWith('.') }
            .sorted()
            .toList()
        if (names.isNotEmpty()) {
            LocalMangaDirectorySnapshot(
                urls = names.toSet(),
                signature = fingerprint(names),
                fromListingFallback = false,
            )
        } else {
            val baseUri = fileSystem.getBaseDirectoryIdentityUri()
            (
                loadListingIndex(baseUri)?.takeIf { it.entries.isNotEmpty() }
                    ?: recoverListingIndexFromChapterNames(baseUri, System.currentTimeMillis())
                )
                ?.entries
                ?.keys
                ?.takeIf { it.isNotEmpty() }
                ?.sorted()
                ?.let { fallbackNames ->
                    LocalMangaDirectorySnapshot(
                        urls = fallbackNames.toSet(),
                        signature = fingerprint(fallbackNames),
                        fromListingFallback = true,
                    )
                }
        }
    }

    suspend fun getMangaDirectorySignature(): String? = getMangaDirectorySnapshot()?.signature

    /**
     * Returns the current manga folders after applying the same transient-partial-read protection
     * as a chapter refresh, without scanning every manga's children.
     */
    suspend fun getConfirmedMangaDirectorySnapshot(): LocalMangaDirectorySnapshot? = withIOContext {
        val initialSnapshot = getBaseDirectorySnapshot(forceRefresh = true)
        val baseUri = fileSystem.getBaseDirectoryIdentityUri()
        val knownDirectoryNames = buildSet {
            addAll(
                loadSyncIndex()?.takeIf { it.baseUri == null || baseUri == null || it.baseUri == baseUri }
                    ?.folders.orEmpty().keys,
            )
            addAll(loadListingIndex(baseUri)?.entries.orEmpty().keys)
            addAll(loadPersistedChapterNamesIndex().keys)
            cachedListing?.mapTo(this, LocalMangaEntry::url)
        }
        val names = confirmBaseDirectorySnapshot(initialSnapshot, knownDirectoryNames)
            ?.directoryNames()
            ?.sorted()
            ?: return@withIOContext null
        LocalMangaDirectorySnapshot(
            urls = names.toSet(),
            signature = fingerprint(names),
            fromListingFallback = false,
        )
    }

    suspend fun getConfirmedMangaUrls(): Set<String>? = getConfirmedMangaDirectorySnapshot()?.urls

    suspend fun getChapterCounts(): Map<String, Long> = withIOContext {
        getListing().associate { it.url to it.chapterCount.toLong() }
    }

    /**
     * Scans chapter file metadata once and returns the manga folders that differ from the last
     * successful sync. If the sync index is missing, the chapter metadata cache is used as a
     * migration baseline so an app update does not unnecessarily reopen every existing archive.
     */
    suspend fun scanChapterChanges(
        lastSuccessfulBaseDirectoryLastModified: Long = -1L,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): LocalChapterSyncScan = withIOContext {
        val initialSnapshot = getBaseDirectorySnapshot(forceRefresh = true)
        val baseUri = fileSystem.getBaseDirectoryIdentityUri()
        val savedIndex = loadSyncIndex()
            ?.takeIf { it.baseUri == null || baseUri == null || it.baseUri == baseUri }
        val persistedChapterNames = loadPersistedChapterNamesIndex()
        val previousNames = buildMap {
            (savedIndex?.folders.orEmpty().keys + persistedChapterNames.keys).forEach { name ->
                val fileNames = savedIndex?.folders?.get(name)?.chapterFileNames
                    ?: persistedChapterNames[name]
                if (fileNames != null) put(name, fileNames)
            }
        }
        val knownDirectoryNames = buildSet {
            addAll(savedIndex?.folders.orEmpty().keys)
            addAll(loadListingIndex(baseUri)?.entries.orEmpty().keys)
            addAll(persistedChapterNames.keys)
            cachedListing?.mapTo(this, LocalMangaEntry::url)
        }
        val snapshot = confirmBaseDirectorySnapshot(initialSnapshot, knownDirectoryNames)
        if (snapshot == null) {
            return@withIOContext LocalChapterSyncScan(
                changedMangaUrls = emptySet(),
                removedMangaUrls = emptySet(),
                previousChapterFileNamesByMangaUrl = previousNames,
                chapterFileNamesByMangaUrl = emptyMap(),
                folderStates = savedIndex?.folders.orEmpty(),
                baseUri = baseUri,
                baseDirectoryLastModified = -1L,
                isReliable = false,
            )
        }
        val baseDirectoryLastModified = snapshot.lastModified

        val directories = snapshot.files
            .mapNotNull { directory ->
                val name = directory.name?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                if (!directory.isDirectory || name.startsWith('.')) return@mapNotNull null
                name to directory
            }
            .distinctBy { it.first }
        val cachedStates = chapterIndexMutex.withLock {
            ensureChapterIndexLoaded()
            val cached = cachedChapterIndex.orEmpty()
            val directoryNames = directories.mapTo(HashSet()) { it.first }
            val pruned = cached.filterKeys { it in directoryNames }
            if (pruned.size != cached.size) {
                cachedChapterIndex = pruned
                chapterIndexDirty = true
                scheduleChapterIndexSave()
            }
            cached.mapValues { (_, index) ->
                LocalChapterFolderState(
                    directoryLastModified = -1L,
                    fingerprint = fingerprint(index.chapters.map(ChapterIndexEntry::fingerprintPart)),
                    chapterFileNames = index.chapters.mapTo(linkedSetOf(), ChapterIndexEntry::name),
                )
            }
        }
        val previousStates = savedIndex?.folders.orEmpty()
        val previousChapterFileNames = buildMap {
            (previousStates.keys + cachedStates.keys + persistedChapterNames.keys).forEach { name ->
                val fileNames = previousStates[name]?.chapterFileNames
                    ?: cachedStates[name]?.chapterFileNames
                    ?: persistedChapterNames[name]
                if (fileNames != null) put(name, fileNames)
            }
        }
        val canPromoteLegacyIndex = savedIndex?.legacy == true &&
            baseDirectoryLastModified > 0 &&
            baseDirectoryLastModified == lastSuccessfulBaseDirectoryLastModified

        onProgress(0, directories.size)
        var completed = 0
        val progressLock = Any()
        val scans = coroutineScope {
            val semaphore = Semaphore(MAX_CONCURRENT_CHAPTER_CHANGE_SCANS)
            directories.map { (name, directory) ->
                async {
                    try {
                        semaphore.withPermit {
                            val directoryLastModified = directory.lastModified()
                            val previous = previousStates[name]
                            val cached = cachedStates[name]
                            val previousFingerprint = previous?.fingerprint ?: cached?.fingerprint
                            val reusablePrevious = previous
                                ?.takeIf { it.chapterFileNames != null }
                                ?: cached?.takeIf {
                                    canPromoteLegacyIndex && previous != null &&
                                        it.fingerprint == previous.fingerprint
                                }
                            val state = when {
                                canPromoteLegacyIndex && reusablePrevious != null -> reusablePrevious.copy(
                                    directoryLastModified = directoryLastModified,
                                )
                                else -> chapterFolderState(
                                    dir = directory,
                                    directoryLastModified = directoryLastModified,
                                    knownChapterFileNames = reusablePrevious?.chapterFileNames.orEmpty(),
                                )
                            }
                            Triple(
                                name,
                                state,
                                when {
                                    previousFingerprint != null -> previousFingerprint != state.fingerprint
                                    persistedChapterNames[name] != null -> {
                                        state.chapterFileNames.orEmpty()
                                            .mapTo(hashSetOf(), ::chapterBaseName) != persistedChapterNames[name]
                                    }
                                    else -> true
                                },
                            )
                        }
                    } finally {
                        synchronized(progressLock) {
                            completed++
                            onProgress(completed, directories.size)
                        }
                    }
                }
            }.awaitAll()
        }
        val changed = scans.filter { it.third }.mapTo(mutableSetOf()) { it.first }
        val currentStates = scans.associate { it.first to it.second }
        val removed = previousChapterFileNames.keys - currentStates.keys
        LocalChapterSyncScan(
            changedMangaUrls = changed,
            removedMangaUrls = removed,
            previousChapterFileNamesByMangaUrl = previousChapterFileNames,
            chapterFileNamesByMangaUrl = currentStates.mapValues { (_, state) ->
                state.chapterFileNames.orEmpty()
            },
            folderStates = currentStates,
            baseUri = baseUri,
            baseDirectoryLastModified = baseDirectoryLastModified,
            isReliable = true,
        )
    }

    /**
     * Commits the scan without touching the filesystem again. Failed manga are deliberately
     * omitted so they remain changed and are retried by the next refresh; deleted folders are
     * naturally pruned from the persisted index.
     */
    suspend fun markChaptersSynced(
        scan: LocalChapterSyncScan,
        successfulMangaUrls: Set<String>,
    ) = withIOContext {
        if (!scan.isReliable) return@withIOContext
        val failed = scan.changedMangaUrls - successfulMangaUrls
        chapterIndexMutex.withLock {
            chapterIndexSaveJob?.cancel()
            chapterIndexSaveJob = null
            if (chapterIndexDirty) {
                cachedChapterIndex?.let { saveChapterIndex(it, cachedChapterIndexBaseUri) }
                chapterIndexDirty = false
            }
        }
        saveSyncIndex(
            LocalChapterSyncIndex(
                baseUri = scan.baseUri,
                baseDirectoryLastModified = scan.baseDirectoryLastModified,
                folders = scan.folderStates.filterKeys { it !in failed },
            ),
        )
        val chapterNames = scan.chapterFileNamesByMangaUrl
            .mapValues { (_, names) -> names.mapTo(linkedSetOf(), ::chapterBaseName) }
            .filterValues(Set<String>::isNotEmpty)
        chapterNamesMutex.withLock {
            cachedChapterNames = chapterNames.mapValues { (_, names) -> names.toList() }
            cachedChapterNamesTime = System.currentTimeMillis()
            cachedChapterNamesBaseDirLastModified = scan.baseDirectoryLastModified
            saveChapterNamesIndex(
                index = cachedChapterNames.orEmpty(),
                baseUri = scan.baseUri,
                baseDirLastModified = scan.baseDirectoryLastModified,
                now = cachedChapterNamesTime,
            )
            cachedChapterNamesBaseUri = scan.baseUri
        }
    }

    private suspend fun confirmBaseDirectorySnapshot(
        initial: LocalSourceFileSystem.DirectorySnapshot,
        knownDirectoryNames: Set<String>,
    ): LocalSourceFileSystem.DirectorySnapshot? {
        if (!initial.isAccessible) return null
        if (knownDirectoryNames.isEmpty()) return initial

        val snapshots = mutableListOf(initial)
        for (retryDelay in EMPTY_DIRECTORY_RETRY_DELAYS) {
            val observedNames = snapshots.maxBy(::directoryCount).directoryNames()
            if (observedNames.containsAll(knownDirectoryNames)) {
                return snapshots.maxBy(::directoryCount)
            }
            delay(retryDelay)
            val retry = getBaseDirectorySnapshot(forceRefresh = true)
            if (!retry.isAccessible) return null
            snapshots += retry
        }

        fileSystem.refreshBaseDirectoryMetadata()
        val refreshed = getBaseDirectorySnapshot(forceRefresh = true)
        if (!refreshed.isAccessible) return null
        snapshots += refreshed

        val best = snapshots.maxBy(::directoryCount)
        val observedNames = best.directoryNames()
        val missingNames = knownDirectoryNames - observedNames
        if (missingNames.isEmpty()) return best
        val directoryState = fileSystem.createMangaDirectoryEntryStateLookup()
        val everyMissingDirectoryIsGone = confirmMissingLocalMangaDirectoriesGone(missingNames) { name ->
            directoryState(name)
        }
        return best.takeIf { everyMissingDirectoryIsGone }
    }

    private fun LocalSourceFileSystem.DirectorySnapshot.directoryNames(): Set<String> {
        return files
            .asSequence()
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            .mapNotNull { it.name }
            .toSet()
    }

    private fun directoryCount(snapshot: LocalSourceFileSystem.DirectorySnapshot): Int {
        return snapshot.files.count { it.isDirectory && !it.name.orEmpty().startsWith('.') }
    }

    /**
     * Returns a stable content fingerprint per manga folder derived from its chapter files'
     * name + mtime + size, sorted. Adding, removing or replacing a cbz changes the fingerprint.
     */
    private suspend fun chapterFolderState(
        dir: UniFile,
        directoryLastModified: Long,
        knownChapterFileNames: Set<String>,
    ): LocalChapterFolderState {
        val chapterFiles = readChapterFilesWithRecovery(dir, knownChapterFileNames)
        val parts = chapterFiles.map { file ->
            val size = if (file.isDirectory) 0L else file.length()
            "${file.name.orEmpty()}|${file.lastModified()}|$size"
        }
        return LocalChapterFolderState(
            directoryLastModified = directoryLastModified,
            fingerprint = fingerprint(parts),
            chapterFileNames = chapterFiles.mapTo(linkedSetOf()) { it.name.orEmpty() },
        )
    }

    private fun readChapterFiles(dir: UniFile): List<UniFile> {
        return fileSystem.getFilesInDirectory(dir)
            .filter(::isChapterFile)
    }

    private suspend fun readChapterFilesWithRecovery(
        dir: UniFile,
        knownChapterFileNames: Set<String>,
    ): List<UniFile> {
        var chapterFiles = readChapterFiles(dir)
        if (chapterFiles.isEmpty()) {
            chapterFiles = recoverChapterFiles(dir, knownChapterFileNames)
        }
        for (retryDelay in EMPTY_DIRECTORY_RETRY_DELAYS) {
            if (chapterFiles.isNotEmpty()) break
            delay(retryDelay)
            chapterFiles = readChapterFiles(dir)
            if (chapterFiles.isEmpty()) {
                chapterFiles = recoverChapterFiles(dir, knownChapterFileNames)
            }
        }
        return chapterFiles
    }

    private fun fingerprint(parts: List<String>): String {
        val digest = MessageDigest.getInstance("MD5")
        parts.sorted().forEach { digest.update(it.toByteArray(StandardCharsets.UTF_8)) }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private data class LocalChapterSyncIndex(
        val baseUri: String?,
        val baseDirectoryLastModified: Long,
        val folders: Map<String, LocalChapterFolderState>,
        val legacy: Boolean = false,
    )

    private fun loadSyncIndex(): LocalChapterSyncIndex? {
        return try {
            if (!syncIndexFile.exists()) return null
            val root = JSONObject(syncIndexFile.readText())
            val version = root.optInt("version", 1)
            if (version >= 2) {
                val foldersObject = root.optJSONObject("folders") ?: JSONObject()
                val folders = buildMap {
                    foldersObject.keys().forEach { name ->
                        val item = foldersObject.optJSONObject(name) ?: return@forEach
                        val fingerprint = item.optString("fingerprint")
                        if (fingerprint.isNotEmpty()) {
                            put(
                                name,
                                LocalChapterFolderState(
                                    directoryLastModified = item.optLong("directoryLastModified", -1L),
                                    fingerprint = fingerprint,
                                    chapterFileNames = if (version >= 3) {
                                        item.optJSONArray("chapterFileNames")?.let { array ->
                                            buildSet {
                                                for (i in 0 until array.length()) {
                                                    array.optString(i).takeIf(String::isNotEmpty)?.let(::add)
                                                }
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                ),
                            )
                        }
                    }
                }
                LocalChapterSyncIndex(
                    baseUri = root.optString("baseUri").takeIf(String::isNotEmpty),
                    baseDirectoryLastModified = root.optLong("baseDirectoryLastModified", -1L),
                    folders = folders,
                )
            } else {
                val folders = buildMap {
                    root.keys().forEach { name ->
                        val fingerprint = root.optString(name)
                        if (fingerprint.isNotEmpty()) {
                            put(name, LocalChapterFolderState(-1L, fingerprint, null))
                        }
                    }
                }
                LocalChapterSyncIndex(
                    baseUri = null,
                    baseDirectoryLastModified = -1L,
                    folders = folders,
                    legacy = true,
                )
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to load local source sync index" }
            null
        }
    }

    private fun saveSyncIndex(index: LocalChapterSyncIndex) {
        try {
            val folders = JSONObject()
            index.folders.forEach { (name, state) ->
                folders.put(
                    name,
                    JSONObject()
                        .put("directoryLastModified", state.directoryLastModified)
                        .put("fingerprint", state.fingerprint)
                        .put("chapterFileNames", JSONArray(state.chapterFileNames.orEmpty().toList())),
                )
            }
            val root = JSONObject()
                .put("version", 3)
                .put("baseUri", index.baseUri.orEmpty())
                .put("baseDirectoryLastModified", index.baseDirectoryLastModified)
                .put("folders", folders)
            writeIndexAtomically(syncIndexFile, root.toString())
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to save local source sync index" }
        }
    }

    private suspend fun getChapterListingStats(
        dir: UniFile,
        knownChapterFileNames: Set<String>,
    ): ChapterListingStats {
        val chapterFiles = readChapterFilesWithRecovery(dir, knownChapterFileNames)
        return ChapterListingStats(
            count = chapterFiles.size,
            latestModified = chapterFiles.maxOfOrNull(UniFile::lastModified) ?: 0L,
        )
    }

    private data class ChapterListingStats(
        val count: Int,
        val latestModified: Long,
    )

    // Chapters

    /**
     * Returns the cached chapter metadata for a manga, building and persisting it on first
     * access. As long as every chapter file is unchanged, re-opening a manga or refreshing
     * its chapters reuses the cache instead of opening every archive again to look for
     * metadata (ComicInfo.xml / epub metadata).
     */
    private suspend fun getChapterIndex(manga: SManga, chapterFiles: List<UniFile>): List<ChapterIndexEntry> {
        ensureBaseDirectoryIdentity()
        val cached = chapterIndexMutex.withLock {
            ensureChapterIndexLoaded()
            cachedChapterIndex?.get(manga.url)
        }
        cached?.let { index ->
            if (index.isValid(chapterFiles)) return index.chapters
        }
        val buildMutex = chapterIndexBuildMutexes[
            (manga.url.hashCode() and Int.MAX_VALUE) %
                chapterIndexBuildMutexes.size,
        ]
        return buildMutex.withLock build@{
            val previous = chapterIndexMutex.withLock {
                ensureChapterIndexLoaded()
                cachedChapterIndex?.get(manga.url)
            }
            previous?.let { index ->
                if (index.isValid(chapterFiles)) return@build index.chapters
            }
            val built = ChapterIndex(buildChapterIndex(manga, chapterFiles, previous))
            chapterIndexMutex.withLock {
                val map = cachedChapterIndex.orEmpty().toMutableMap()
                map[manga.url] = built
                cachedChapterIndex = map
                chapterIndexDirty = true
                scheduleChapterIndexSave()
            }
            built.chapters
        }
    }

    private fun scheduleChapterIndexSave() {
        if (!chapterIndexDirty) return
        chapterIndexSaveJob?.cancel()
        chapterIndexSaveJob = chapterIndexScope.launch {
            delay(CHAPTER_INDEX_SAVE_DEBOUNCE_MILLIS)
            chapterIndexMutex.withLock {
                if (chapterIndexDirty) {
                    cachedChapterIndex?.let { saveChapterIndex(it, cachedChapterIndexBaseUri) }
                    chapterIndexDirty = false
                }
                chapterIndexSaveJob = null
            }
        }
    }

    private suspend fun buildChapterIndex(
        manga: SManga,
        chapterFiles: List<UniFile>,
        previous: ChapterIndex?,
    ): List<ChapterIndexEntry> {
        val coverLookups = Semaphore(MAX_CONCURRENT_COVER_LOOKUPS)
        val previousByName = previous?.chapters.orEmpty().associateBy(ChapterIndexEntry::name)
        return coroutineScope {
            chapterFiles.map { chapterFile ->
                async {
                    val cached = previousByName[chapterFile.name.orEmpty()]
                    cached?.takeIf { it.matches(chapterFile) }
                        ?: coverLookups.withPermit { buildChapterEntry(manga, chapterFile) }
                }
            }.awaitAll()
        }
            .sortedWith { c1, c2 ->
                c2.displayName.compareToCaseInsensitiveNaturalOrder(c1.displayName)
            }
    }

    private fun buildChapterEntry(manga: SManga, chapterFile: UniFile): ChapterIndexEntry {
        val fileName = chapterFile.name.orEmpty()
        val isDirectory = chapterFile.isDirectory
        val lastModified = chapterFile.lastModified()
        val size = if (isDirectory) 0L else chapterFile.length()
        val baseName = if (isDirectory) fileName else chapterFile.nameWithoutExtension.orEmpty()
        var displayName = baseName
        var chapterNumber = ChapterRecognition.parseChapterNumber(manga.title, baseName, -1.0).toFloat()
        var scanlator: String? = null
        var dateUpload = lastModified
        var hasComicInfo = false
        var pageCount = 0

        val format = Format.valueOf(chapterFile)
        if (format is Format.Epub) {
            format.file.epubReader(context).use { epub ->
                val chapter = SChapter.create().apply {
                    name = displayName
                    chapter_number = chapterNumber
                    scanlator = scanlator
                    date_upload = dateUpload
                }
                epub.fillMetadata(manga, chapter)
                displayName = chapter.name
                chapterNumber = chapter.chapter_number
                scanlator = chapter.scanlator
                dateUpload = chapter.date_upload
                pageCount = runCatching { epub.getImagesFromPages().size }.getOrDefault(0)
            }
        } else {
            getComicInfoForChapter(chapterFile) { stream ->
                val chapter = SChapter.create().apply {
                    name = displayName
                    chapter_number = chapterNumber
                    scanlator = scanlator
                    date_upload = dateUpload
                }
                setChapterDetailsFromComicInfoFile(stream, chapter)
                hasComicInfo = true
                displayName = chapter.name
                chapterNumber = chapter.chapter_number
                scanlator = chapter.scanlator
            }
            pageCount = when (format) {
                is Format.Directory -> fileSystem.getFilesInDirectory(chapterFile)
                    .count { !it.isDirectory && ImageUtil.isImage(it.name) }
                is Format.Archive -> chapterFile.archiveReader(context).use { reader ->
                    reader.useEntries { entries ->
                        entries.count { it.isFile && ImageUtil.isImage(it.name) }
                    }
                }
            }
        }

        return ChapterIndexEntry(
            name = fileName,
            lastModified = lastModified,
            size = size,
            displayName = displayName,
            chapterNumber = chapterNumber,
            scanlator = scanlator,
            dateUpload = dateUpload,
            hasComicInfo = hasComicInfo,
            pageCount = pageCount,
        )
    }

    private data class ChapterIndex(
        val chapters: List<ChapterIndexEntry>,
    ) {
        fun isValid(chapterFiles: List<UniFile>): Boolean {
            if (chapterFiles.size != chapters.size) return false
            val byName = chapters.associateBy { it.name }
            return chapterFiles.all { file ->
                byName[file.name.orEmpty()]?.matches(file) == true
            }
        }
    }

    private data class ChapterIndexEntry(
        val name: String,
        val lastModified: Long,
        val size: Long,
        val displayName: String,
        val chapterNumber: Float,
        val scanlator: String?,
        val dateUpload: Long,
        val hasComicInfo: Boolean,
        val pageCount: Int,
    ) {
        val fingerprintPart: String
            get() = "$name|$lastModified|$size"

        fun matches(file: UniFile): Boolean {
            if (name != file.name.orEmpty() || lastModified != file.lastModified()) return false
            return file.isDirectory || size == file.length()
        }
    }

    private fun ensureChapterIndexLoaded() {
        val baseUri = fileSystem.getBaseDirectoryIdentityUri()
        if (cachedChapterIndex != null && cachedChapterIndexBaseUri == baseUri) return
        cachedChapterIndex = try {
            if (!chapterIndexFile.exists()) {
                emptyMap()
            } else {
                val root = JSONObject(chapterIndexFile.readText())
                if (
                    root.optInt("version", -1) != CHAPTER_INDEX_VERSION ||
                    root.optString("baseUri").takeIf(String::isNotEmpty) != baseUri
                ) {
                    emptyMap()
                } else {
                    val mangaObject = root.optJSONObject("manga")
                    if (mangaObject == null) {
                        emptyMap()
                    } else {
                        val map = mutableMapOf<String, ChapterIndex>()
                        mangaObject.keys().forEach { url ->
                            val indexObject = mangaObject.optJSONObject(url)
                            val array = indexObject?.optJSONArray("chapters")
                            if (array != null) {
                                val entries = mutableListOf<ChapterIndexEntry>()
                                for (i in 0 until array.length()) {
                                    val item = array.optJSONObject(i) ?: continue
                                    entries.add(
                                        ChapterIndexEntry(
                                            name = item.optString("name"),
                                            lastModified = item.optLong("lastModified", 0L),
                                            size = item.optLong("size", 0L),
                                            displayName = item.optString("displayName"),
                                            chapterNumber = item.optDouble("chapterNumber", -1.0).toFloat(),
                                            scanlator = item.optString("scanlator").takeIf { it.isNotEmpty() },
                                            dateUpload = item.optLong("dateUpload", 0L),
                                            hasComicInfo = item.optBoolean("hasComicInfo", false),
                                            pageCount = item.optInt("pageCount", 0),
                                        ),
                                    )
                                }
                                map[url] = ChapterIndex(entries)
                            }
                        }
                        map
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to load local source chapter index" }
            emptyMap()
        }
        cachedChapterIndexBaseUri = baseUri
    }

    private fun saveChapterIndex(index: Map<String, ChapterIndex>, baseUri: String?) {
        try {
            val mangaObject = JSONObject()
            index.forEach { (url, mangaIndex) ->
                val array = JSONArray()
                mangaIndex.chapters.forEach { entry ->
                    array.put(
                        JSONObject()
                            .put("name", entry.name)
                            .put("lastModified", entry.lastModified)
                            .put("size", entry.size)
                            .put("displayName", entry.displayName)
                            .put("chapterNumber", entry.chapterNumber.toDouble())
                            .put("scanlator", entry.scanlator.orEmpty())
                            .put("dateUpload", entry.dateUpload)
                            .put("hasComicInfo", entry.hasComicInfo)
                            .put("pageCount", entry.pageCount),
                    )
                }
                mangaObject.put(url, JSONObject().put("chapters", array))
            }
            val root = JSONObject()
                .put("version", CHAPTER_INDEX_VERSION)
                .put("baseUri", baseUri.orEmpty())
                .put("manga", mangaObject)

            writeIndexAtomically(chapterIndexFile, root.toString())
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to save local source chapter index" }
        }
    }

    private fun writeIndexAtomically(target: File, content: String) {
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        tempFile.writeText(content)
        try {
            try {
                Files.move(
                    tempFile.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(
                    tempFile.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * Returns page counts from the local chapter index, keyed by the chapter URL used in the
     * database. This lets the detail screen prefill progress bars without entering the reader
     * and without rewriting source order.
     */
    suspend fun getChapterPageCounts(manga: SManga): Map<String, Long> = withIOContext {
        val snapshot = getMangaDirectorySnapshot(manga.url, confirmEmptyChapters = true)
        if (!snapshot.isAccessible || snapshot.files.isEmpty()) return@withIOContext emptyMap()
        val chapterFiles = snapshot.files
            // Only keep supported formats
            .filterNot { it.name.orEmpty().startsWith('.') }
            .filter { it.isDirectory || Archive.isSupported(it) || it.extension.equals("epub", true) }

        getChapterIndex(manga, chapterFiles)
            .associate { entry -> "${manga.url}/${entry.name}" to entry.pageCount.toLong() }
    }

    /**
     * Cheap detail-screen change check. This only lists direct children and never opens archives.
     * Unavailable or unexpectedly empty reads are treated as unchanged so entering a manga cannot
     * trigger a slow scan, or remove chapters, because of a transient document-provider failure.
     */
    suspend fun hasChapterFileChanges(
        mangaUrl: String,
        existingChapterUrls: Collection<String>,
    ): Boolean = withIOContext {
        val snapshot = getMangaDirectorySnapshot(
            mangaUrl = mangaUrl,
            confirmEmptyChapters = true,
            existingChapterUrls = existingChapterUrls,
        )

        val currentChapterUrls = snapshot.files
            .asSequence()
            .filterNot { it.name.orEmpty().startsWith('.') }
            .filter(::isChapterFile)
            .mapTo(linkedSetOf()) { "$mangaUrl/${it.name.orEmpty()}" }

        chapterFileSetChanged(
            existingChapterUrls = existingChapterUrls,
            currentChapterUrls = currentChapterUrls,
            isAccessible = snapshot.isAccessible,
            isConfirmedEmpty = snapshot.isConfirmedEmpty,
        )
    }

    private suspend fun getChapterList(
        manga: SManga,
        existingChapters: List<SChapter>,
        directorySnapshot: LocalSourceFileSystem.DirectorySnapshot? = null,
    ): List<SChapter> = withIOContext {
        val snapshot = directorySnapshot ?: getMangaDirectorySnapshot(manga.url)
        if (!snapshot.isAccessible) {
            error("Unable to read local manga directory: ${manga.url}")
        }
        val chapterFiles = snapshot.files
            // Only keep supported formats
            .filterNot { it.name.orEmpty().startsWith('.') }
            .filter(::isChapterFile)

        if (chapterFiles.isEmpty() && existingChapters.isNotEmpty() && !snapshot.isConfirmedEmpty) {
            logcat(LogPriority.WARN) {
                "Ignoring an unexpected empty local chapter scan for ${manga.url}; keeping existing chapters"
            }
            return@withIOContext existingChapters
        }

        val entries = getChapterIndex(manga, chapterFiles)
        val chapters = entries.map { entry ->
            SChapter.create().apply {
                url = "${manga.url}/${entry.name}"
                name = entry.displayName
                date_upload = entry.dateUpload
                chapter_number = entry.chapterNumber
                scanlator = entry.scanlator
                if (entry.pageCount > 0) {
                    memo = JsonObject(mapOf(PAGE_COUNT_KEY to JsonPrimitive(entry.pageCount)))
                }
            }
        }

        // Copy the cover from the first chapter found if not available
        if (manga.thumbnail_url.isNullOrBlank()) {
            chapters.lastOrNull()?.let { chapter ->
                updateCover(chapter, manga)
            }
        }

        chapters
    }

    private suspend fun getMangaDirectorySnapshot(
        mangaUrl: String,
        confirmEmptyChapters: Boolean = false,
        existingChapterUrls: Collection<String> = emptyList(),
    ): LocalSourceFileSystem.DirectorySnapshot {
        ensureBaseDirectoryIdentity()
        var snapshot = fileSystem.getMangaDirectorySnapshot(mangaUrl)
        if (!snapshot.isAccessible || snapshot.hasExpectedFiles(confirmEmptyChapters)) return snapshot

        val knownNames = knownChapterNames(mangaUrl, existingChapterUrls)
        recoverChapterFiles(snapshot.directory, knownNames).takeIf(List<UniFile>::isNotEmpty)?.let { recovered ->
            return snapshot.copy(files = recovered, isConfirmedEmpty = false)
        }

        var allReadsAccessibleAndEmpty = true
        for (retryDelay in EMPTY_DIRECTORY_RETRY_DELAYS) {
            delay(retryDelay)
            snapshot = fileSystem.getMangaDirectorySnapshot(mangaUrl)
            if (!snapshot.isAccessible) return snapshot
            if (snapshot.hasExpectedFiles(confirmEmptyChapters)) return snapshot

            recoverChapterFiles(snapshot.directory, knownNames).takeIf(List<UniFile>::isNotEmpty)?.let { recovered ->
                return snapshot.copy(files = recovered, isConfirmedEmpty = false)
            }
            allReadsAccessibleAndEmpty = allReadsAccessibleAndEmpty && snapshot.isAccessible &&
                (if (confirmEmptyChapters) snapshot.files.none(::isChapterFile) else snapshot.files.isEmpty())
        }
        return snapshot.copy(
            // Exact child lookups are repeated along with the empty directory query. Only then is
            // an all-chapters deletion considered real; ordinary false-empty reads recover above.
            isConfirmedEmpty = allReadsAccessibleAndEmpty,
        )
    }

    private fun LocalSourceFileSystem.DirectorySnapshot.hasExpectedFiles(confirmEmptyChapters: Boolean): Boolean {
        return if (confirmEmptyChapters) files.any(::isChapterFile) else files.isNotEmpty()
    }

    /**
     * Some external-storage document providers intermittently return an empty children query for
     * a directory URI while exact child document URIs remain readable. The persisted name index
     * gives us enough information to resolve those children without scanning or opening archives.
     */
    private fun recoverChapterFiles(
        directory: UniFile?,
        knownNames: Collection<String>,
    ): List<UniFile> {
        directory ?: return emptyList()
        return knownNames.mapNotNull { knownName ->
            chapterFileNameCandidates(knownName)
                .asSequence()
                .mapNotNull { candidate -> runCatching { directory.findFile(candidate) }.getOrNull() }
                .firstOrNull { candidate ->
                    runCatching { candidate.exists() && isChapterFile(candidate) }.getOrDefault(false)
                }
        }.distinctBy { it.name }
    }

    private fun knownChapterNames(
        mangaUrl: String,
        existingChapterUrls: Collection<String>,
    ): Set<String> {
        val exactFileNames = existingChapterUrls
            .mapNotNull { url -> url.removePrefix("$mangaUrl/").takeIf(String::isNotEmpty) }
            .toSet()
        val exactBaseNames = exactFileNames.mapTo(HashSet(), ::chapterBaseName)
        val indexedBaseNames = loadPersistedChapterNames(mangaUrl)
            .filterNot { it in exactBaseNames }
        return exactFileNames + indexedBaseNames
    }

    private fun loadPersistedChapterNames(mangaUrl: String): List<String> {
        return try {
            if (!chapterNamesIndexFile.exists()) return emptyList()
            val root = JSONObject(chapterNamesIndexFile.readText())
            if (root.optInt("version", -1) != CHAPTER_NAMES_INDEX_VERSION) return emptyList()
            val baseUri = fileSystem.getBaseDirectoryIdentityUri()
            if (root.optString("baseUri").takeIf(String::isNotEmpty) != baseUri) return emptyList()
            val array = root.optJSONObject("manga")?.optJSONArray(mangaUrl) ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf(String::isNotEmpty)?.let(::add)
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to recover local chapter names for $mangaUrl" }
            emptyList()
        }
    }

    private fun loadPersistedChapterNamesIndex(): Map<String, Set<String>> {
        return try {
            if (!chapterNamesIndexFile.exists()) return emptyMap()
            val root = JSONObject(chapterNamesIndexFile.readText())
            if (root.optInt("version", -1) != CHAPTER_NAMES_INDEX_VERSION) return emptyMap()
            val baseUri = fileSystem.getBaseDirectoryIdentityUri()
            if (root.optString("baseUri").takeIf(String::isNotEmpty) != baseUri) return emptyMap()
            val mangaObject = root.optJSONObject("manga") ?: return emptyMap()
            buildMap {
                mangaObject.keys().forEach { mangaUrl ->
                    val names = mangaObject.optJSONArray(mangaUrl)?.let { array ->
                        buildSet {
                            for (i in 0 until array.length()) {
                                array.optString(i).takeIf(String::isNotEmpty)?.let(::add)
                            }
                        }
                    }.orEmpty()
                    if (names.isNotEmpty()) put(mangaUrl, names)
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to load persisted local chapter names" }
            emptyMap()
        }
    }

    private fun isChapterFile(file: UniFile): Boolean {
        return !file.name.orEmpty().startsWith('.') &&
            (file.isDirectory || Archive.isSupported(file) || file.extension.equals("epub", true))
    }

    // Filters
    override fun getFilterList() = FilterList(currentOrderBy())

    // Unused stuff
    override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException("Unused")

    fun getFormat(chapter: SChapter): Format {
        try {
            val (mangaDirName, chapterName) = chapter.url.split('/', limit = 2)
            return fileSystem.getBaseDirectory()
                ?.findFile(mangaDirName)
                ?.findFile(chapterName)
                ?.let(Format.Companion::valueOf)
                ?: throw Exception(context.stringResource(MR.strings.chapter_not_found))
        } catch (e: Format.UnknownFormatException) {
            throw Exception(context.stringResource(MR.strings.local_invalid_format))
        } catch (e: Exception) {
            throw e
        }
    }

    private fun updateCover(chapter: SChapter, manga: SManga): UniFile? {
        return try {
            when (val format = getFormat(chapter)) {
                is Format.Directory -> {
                    val entry = fileSystem.getFilesInDirectory(format.file)
                        .sortedWith { f1, f2 ->
                            f1.name.orEmpty().compareToCaseInsensitiveNaturalPageOrder(
                                f2.name.orEmpty(),
                            )
                        }
                        .find {
                            !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() }
                        }

                    entry?.let { coverManager.update(manga, it.openInputStream()) }
                }
                is Format.Archive -> {
                    format.file.archiveReader(context).use { reader ->
                        val entry = reader.useEntries { entries ->
                            entries
                                .sortedWith { f1, f2 ->
                                    f1.name.compareToCaseInsensitiveNaturalPageOrder(f2.name)
                                }
                                .find { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        }

                        entry?.let { coverManager.update(manga, reader.getInputStream(it.name)!!) }
                    }
                }
                is Format.Epub -> {
                    format.file.epubReader(context).use { epub ->
                        val entry = epub.getImagesFromPages().firstOrNull()

                        entry?.let { coverManager.update(manga, epub.getInputStream(it)!!) }
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error updating cover for ${manga.title}" }
            null
        }
    }

    companion object {
        const val ID = 0L
        const val HELP_URL = "https://mihon.app/docs/guides/local-source/"
        const val PAGE_SIZE = 50

        private const val PAGE_COUNT_KEY = "mihon.pageCount"

        private val LATEST_THRESHOLD = 7.days.inWholeMilliseconds

        private val LISTING_CACHE_TTL = 10.minutes
        private val LISTING_MAX_AGE = 24.hours
        private val LISTING_RETRY_COOLDOWN = 2.seconds
        private val BASE_SNAPSHOT_CACHE_MILLIS = 2.seconds.inWholeMilliseconds
        private val EMPTY_DIRECTORY_RETRY_DELAYS = listOf(150L, 350L, 750L)
        private const val MAX_CONCURRENT_COVER_LOOKUPS = 16
        private const val MAX_CONCURRENT_CHAPTER_NAME_LOOKUPS = 16
        private const val MAX_CONCURRENT_CHAPTER_INDEX_BUILDS = 16
        private const val MAX_CONCURRENT_CHAPTER_CHANGE_SCANS = 16
        private const val CHAPTER_INDEX_SAVE_DEBOUNCE_MILLIS = 750L
        private const val LISTING_INDEX_VERSION = 4
        private const val CHAPTER_INDEX_VERSION = 4
        private const val CHAPTER_NAMES_INDEX_VERSION = 2

        /**
         * Memo key used to carry the chapter name that matched a search query
         * from the source to the browse UI (not user-visible).
         */
        const val MATCHED_CHAPTER_KEY = "mihon.matchedChapter"
        const val LATEST_CHAPTER_TIME_KEY = "mihon.latestChapterTime"
    }
}

internal fun shouldReuseListingAfterUnexpectedEmptyScan(
    scannedDirectoryCount: Int,
    persistedEntryCount: Int,
): Boolean {
    return scannedDirectoryCount == 0 &&
        persistedEntryCount > 0
}

internal fun resolvedLocalChapterCount(
    scannedChapterFiles: Set<String>?,
    measuredChapterCount: Int,
    previousConfirmedChapterCount: Int? = null,
): Int {
    return scannedChapterFiles?.size
        ?: measuredChapterCount.takeIf { it > 0 }
        ?: previousConfirmedChapterCount?.takeIf { it > 0 }
        ?: measuredChapterCount
}

/**
 * Outcome of matching a local manga against a search query.
 *
 * [matchedChapter] is the name the browse UI should credit for the hit, or null when only the manga
 * title matched and no chapter deserves the credit.
 */
internal data class LocalSearchMatch(val matchedChapter: String?)

/**
 * Decides whether a local manga matches [query], and which chapter name the browse UI should credit
 * for the hit.
 *
 * A chapter is enough to find its manga, so the manga title, its chapter file names and the manually
 * assigned Chinese translated names are all searched - the text only has to appear in one of them.
 * Normalization matches library search, otherwise a translated name would be reachable from the
 * library that stores it but not from the local source it belongs to.
 *
 * File names stay authoritative, so a translated name is only credited when no file name matched.
 *
 * Returns null when the manga must be filtered out.
 */
internal fun localSearchMatch(
    query: String,
    title: String,
    chapterNames: List<String>,
    translatedNames: List<String>,
): LocalSearchMatch? {
    chapterNames.firstOrNull { it.containsSearch(query) }?.let { return LocalSearchMatch(it) }
    translatedNames.firstOrNull { it.containsSearch(query) }?.let { return LocalSearchMatch(it) }
    return if (title.containsSearch(query)) LocalSearchMatch(null) else null
}

internal fun shouldIncludeLocalMangaDirectory(
    mangaUrl: String,
    scannedChapterFileNames: Map<String, Set<String>>?,
): Boolean = scannedChapterFileNames?.let { scanned ->
    scanned[mangaUrl]?.isNotEmpty() == true
} ?: true

internal fun chapterFileNameCandidates(knownName: String): List<String> {
    if (chapterBaseName(knownName) != knownName) return listOf(knownName)
    return buildList {
        add(knownName)
        LOCAL_CHAPTER_FILE_EXTENSIONS.forEach { extension -> add("$knownName.$extension") }
    }
}

internal fun chapterBaseName(fileName: String): String {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
    return if (extension.lowercase() in LOCAL_CHAPTER_FILE_EXTENSIONS) {
        fileName.removeSuffix(".$extension")
    } else {
        fileName
    }
}

private val LOCAL_CHAPTER_FILE_EXTENSIONS = listOf(
    "cbz",
    "zip",
    "cbr",
    "rar",
    "cb7",
    "7z",
    "cbt",
    "tar",
    "epub",
)

internal data class LocalPage<T>(
    val items: List<T>,
    val hasNextPage: Boolean,
    val itemsBefore: Int,
    val itemsAfter: Int,
)

internal fun <T> List<T>.localPage(page: Int, pageSize: Int): LocalPage<T> {
    require(pageSize > 0)
    val start = (page.coerceAtLeast(1) - 1) * pageSize
    if (start >= size) return LocalPage(emptyList(), false, size, 0)
    val end = (start + pageSize).coerceAtMost(size)
    return LocalPage(
        items = subList(start, end),
        hasNextPage = end < size,
        itemsBefore = start,
        itemsAfter = size - end,
    )
}

fun Manga.isLocal(): Boolean = source == LocalSource.ID

fun Source.isLocal(): Boolean = id == LocalSource.ID

fun DomainSource.isLocal(): Boolean = id == LocalSource.ID
