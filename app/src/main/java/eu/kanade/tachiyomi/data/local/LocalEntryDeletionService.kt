package eu.kanade.tachiyomi.data.local

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.manga.MangaMark
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.LocalSourceDirectoryEntryState
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalChapterCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.io.isSingleChapterChapterUrl
import tachiyomi.source.local.io.isSingleChapterEntryName
import tachiyomi.source.local.io.loosePages
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Deletes local manga files from disk and drops every trace of them from the database.
 *
 * A single service backs every delete entry point so the disk/database ordering stays
 * identical no matter where the action was triggered from.
 *
 * Runs on its own scope rather than the caller's: rotating the screen or leaving the page must not
 * interrupt a delete that has already started, and only [requestStop] ends one early. Callers start
 * a run with [startDeleteChapters] / [startDeleteMangas] and read [progress] and [lastReport];
 * the suspend entry points stay for tests and for code that really wants to wait.
 */
class LocalEntryDeletionService(
    private val fileSystem: LocalSourceFileSystem = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
    private val coverManager: LocalChapterCoverManager = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val mangaMarkStore: MangaMarkStore = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) {

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskLock = Any()
    private var taskJob: Job? = null
    private val stopRequested = AtomicBoolean(false)
    private val reportCounter = AtomicLong(0)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _lastReport = MutableStateFlow<LocalDeletionReport?>(null)
    val lastReport: StateFlow<LocalDeletionReport?> = _lastReport.asStateFlow()

    /**
     * How far a running delete has got.
     *
     * [Phase.COUNTING] has no total yet: the files are being walked to learn how many there are,
     * and the bar is indeterminate until that finishes.
     */
    data class Progress(
        val phase: Phase,
        val done: Int,
        val total: Int,
    ) {
        enum class Phase { COUNTING, DELETING }
    }

    data class Result(
        val deleted: Int,
        val failed: List<String>,
        val deletedMangaIds: Set<Long> = emptySet(),
    )

    /** Starts a chapter deletion and returns immediately. Ignored while another run is active. */
    fun startDeleteChapters(targets: List<ChapterTarget>, folders: List<EmptyFolderCleanup> = emptyList()) {
        if (targets.isEmpty()) return
        launchTask { deleteChapters(targets, folders) }
    }

    /** Authorization for a particular directory and the metadata shown in the confirmation. */
    data class EmptyFolderCleanup(val mangaId: Long, val url: String, val metadata: Set<String>)

    suspend fun previewEmptyFolders(targets: List<ChapterTarget>): List<EmptyFolderCleanup> =
        withContext(Dispatchers.IO) {
            targets.groupBy { it.mangaId }.mapNotNull { (mangaId, selected) ->
                val chapters = chapterRepository.getChapterByMangaId(mangaId)
                if (chapters.isEmpty() || chapters.map { it.id }.toSet() != selected.map { it.id }.toSet()) {
                    return@mapNotNull null
                }
                val url = chapters.first().url.substringBefore('/')
                val directory = fileSystem.getMangaDirectory(url) ?: return@mapNotNull null
                val snapshot = fileSystem.getDirectorySnapshot(directory, fresh = true)
                if (!snapshot.isAccessible) return@mapNotNull null
                val namesToDelete = if (chapters.singleOrNull()?.url?.isSingleChapterChapterUrl() == true) {
                    loosePages(snapshot.files).mapNotNull { it.name }.toSet()
                } else {
                    chapters.map { it.url.substringAfter('/') }.toSet()
                }
                val remaining = snapshot.files.filterNot { it.name in namesToDelete }
                if (remaining.any { !it.isFile || it.name !in GENERATED_FILE_NAMES }) return@mapNotNull null
                EmptyFolderCleanup(mangaId, url, remaining.mapNotNull { it.name }.toSet())
            }
        }

    /** Starts a whole-directory deletion and returns immediately. Ignored while a run is active. */
    fun startDeleteMangas(entries: List<MangaEntry>) {
        if (entries.isEmpty()) return
        launchTask { deleteMangas(entries) }
    }

    /**
     * Asks the running delete to stop at the next file boundary.
     *
     * Files already removed stay removed; the entries that were not reached are reported as
     * skipped so the receipt says what is still on disk.
     */
    fun requestStop() {
        stopRequested.set(true)
    }

    /** Drops the receipt. Called when the reader closes the result dialog or exports the list. */
    fun clearReport() {
        _lastReport.value = null
    }

    private fun launchTask(block: suspend () -> Unit) {
        synchronized(taskLock) {
            if (taskJob?.isActive == true) return
            stopRequested.set(false)
            // A new run replaces the previous receipt instead of stacking a second dialog behind
            // the one still on screen.
            _lastReport.value = null
            taskJob = scope.launch {
                try {
                    block()
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Local deletion failed" }
                } finally {
                    _progress.value = null
                }
            }
        }
    }

    /**
     * Deletes the local files of the given chapters.
     *
     * A manga keeps its directory after losing its last chapter unless cleanup was approved.
     * Cleanup only removes empty directories and previously approved metadata.
     *
     * Chapter urls are looked up here rather than passed in, so a caller can never hand over a
     * stale or empty path.
     */
    suspend fun deleteChapters(
        targets: List<ChapterTarget>,
        folders: List<EmptyFolderCleanup> = emptyList(),
    ): Result = withContext(Dispatchers.IO) {
        mutex.withLock { runChapters(targets, folders) }
    }

    /**
     * Deletes a whole manga directory. The database row is only dropped once every file in the
     * directory is gone, otherwise the leftover files would be re-indexed under a new identity
     * on the next scan and silently lose their history.
     *
     * A directory that is already gone counts as deleted rather than as a failure: the files are
     * in the state this call asks for, so only the records are left to drop. Without that, a card
     * whose directory disappeared behind the app's back - deleted through a file manager, or
     * removed by an earlier pass that did not finish - can never be deleted from the library at
     * all, because the removal it reports as failed has already happened.
     *
     * "Gone" is only accepted when two independent reads agree - see [directoryIsConfirmedGone].
     * The records dropped here are not rebuildable (history, bookmarks, chapter titles and the
     * custom cover all go with the row), so a provider that merely failed to answer has to come
     * back as a failure and be retried by the reader, never as a deletion.
     */
    suspend fun deleteManga(manga: MangaEntry): Result = withContext(Dispatchers.IO) {
        mutex.withLock { runMangas(listOf(manga)) }
    }

    /** Same as [deleteManga] for a selection, with one counting pass over every directory. */
    suspend fun deleteMangas(entries: List<MangaEntry>): Result = withContext(Dispatchers.IO) {
        mutex.withLock { runMangas(entries) }
    }

    private suspend fun runChapters(targets: List<ChapterTarget>, folders: List<EmptyFolderCleanup>): Result {
        try {
            val base = fileSystem.getBaseDirectory()
            if (base == null) {
                logcat(LogPriority.ERROR) { "Local directory unavailable; nothing was deleted" }
                publishReport(
                    unit = LocalDeletionUnit.CHAPTER,
                    entries = emptyList(),
                    skippedCount = 0,
                )
                return Result(deleted = 0, failed = targets.map { it.name })
            }

            val resolved = runCatching {
                targets.groupBy { it.mangaId }.flatMap { (mangaId, group) ->
                    val wanted = group.associateBy { it.id }
                    chapterRepository.getChapterByMangaId(mangaId)
                        .filter { it.id in wanted }
                        .map { chapter -> wanted.getValue(chapter.id) to chapter }
                }
            }.onFailure {
                logcat(LogPriority.ERROR, it) { "Failed to resolve chapters before deletion" }
            }.getOrDefault(emptyList())

            val missing = targets.mapTo(mutableSetOf()) { it.id } -
                resolved.mapTo(mutableSetOf()) { it.first.id }

            val plans = resolved.map { (target, chapter) ->
                ChapterPlan(
                    target = target,
                    chapterUrl = chapter.url,
                    file = resolveChapterFile(base, chapter.url),
                    singleChapter = chapter.url.isSingleChapterChapterUrl(),
                )
            }

            // Counting first: the bar needs a total that does not shrink as the loop runs.
            _progress.value = Progress(Progress.Phase.COUNTING, done = 0, total = 0)
            var total = 0
            plans.forEach { plan ->
                val file = plan.file ?: return@forEach
                if (plan.singleChapter) {
                    val snapshot = fileSystem.getDirectorySnapshot(file, fresh = true)
                    if (!snapshot.isAccessible) return@forEach
                    plan.pages = loosePages(snapshot.files)
                }
                val (count, size) = if (plan.singleChapter) {
                    plan.pages.size to plan.pages.sumOf { it.length().coerceAtLeast(0) }
                } else {
                    measure(file)
                }
                plan.fileCount = count
                plan.sizeBytes = size
                total += count
            }

            _progress.value = Progress(Progress.Phase.DELETING, done = 0, total = total)
            var done = 0
            val deleted = mutableListOf<Pair<ChapterTarget, String>>()
            val failed = mutableListOf<String>()
            val entries = mutableListOf<LocalDeletionEntry>()

            plans.forEach { plan ->
                if (stopRequested.get()) {
                    plan.skipped = true
                    return@forEach
                }
                val file = plan.file
                if (file == null) {
                    failed += plan.target.name
                    entries += plan.toEntry(LocalDeletionOutcome.FAILED)
                    return@forEach
                }
                val onFileDeleted = {
                    done++
                    _progress.value = Progress(Progress.Phase.DELETING, done = done, total = total)
                }
                val outcome = if (plan.singleChapter) {
                    deleteLoosePages(plan.pages, onFileDeleted)
                } else {
                    deleteTree(file, onFileDeleted)
                }
                entries += plan.toEntry(outcome)
                if (outcome == LocalDeletionOutcome.DELETED) {
                    deleted += plan.target to plan.chapterUrl
                } else {
                    failed += plan.target.name
                }
            }

            targets.filter { it.id in missing }.forEach { target ->
                failed += target.name
                entries += LocalDeletionEntry(
                    mangaTitle = target.mangaTitle,
                    name = target.name,
                    relativePath = "",
                    fileCount = 0,
                    sizeBytes = 0,
                    outcome = LocalDeletionOutcome.FAILED,
                )
            }

            if (deleted.isNotEmpty()) {
                cleanupChapters(deleted)
            }

            val changedWorks = deleted.mapTo(mutableSetOf()) { (_, url) -> url.substringBefore('/') }
            val removedFolderMangaIds = mutableSetOf<Long>()
            val keptFolders = folders.mapNotNull { folder ->
                val removed = folder.url in changedWorks && !stopRequested.get() &&
                    withLocalChapterMutationLock(folder.url) {
                        runCatching {
                            chapterRepository.getChapterByMangaId(folder.mangaId).isEmpty() &&
                                removeEmptyDirectory(base.findFile(folder.url), folder.metadata)
                        }.getOrDefault(false)
                    }
                if (removed) {
                    localSource()?.removeListingEntry(folder.url)
                    changedWorks.remove(folder.url)
                    removedFolderMangaIds += folder.mangaId
                    null
                } else {
                    folder.url
                }
            }
            localSource()?.refreshMutatedWorks(changedWorks)

            publishReport(
                unit = LocalDeletionUnit.CHAPTER,
                entries = entries,
                skippedCount = plans.count { it.skipped },
                keptFolders = keptFolders,
                removedFolderMangaIds = removedFolderMangaIds,
            )
            return Result(
                deleted = deleted.size,
                failed = failed,
            )
        } finally {
            _progress.value = null
        }
    }

    private suspend fun runMangas(entries: List<MangaEntry>): Result {
        try {
            val base = fileSystem.getBaseDirectory()
            if (base == null) {
                logcat(LogPriority.ERROR) { "Local directory unavailable; nothing was deleted" }
                publishReport(
                    unit = LocalDeletionUnit.COLLECTION,
                    entries = emptyList(),
                    skippedCount = 0,
                )
                return Result(deleted = 0, failed = entries.map { it.title })
            }

            val plans = entries.map { entry ->
                CollectionPlan(entry, runCatching { base.findFile(entry.url) }.getOrNull())
            }

            _progress.value = Progress(Progress.Phase.COUNTING, done = 0, total = 0)
            var total = 0
            plans.forEach { plan ->
                val dir = plan.dir ?: return@forEach
                val (count, size) = measure(dir)
                plan.fileCount = count
                plan.sizeBytes = size
                total += count
            }

            _progress.value = Progress(Progress.Phase.DELETING, done = 0, total = total)
            var done = 0
            val failed = mutableListOf<String>()
            val reportEntries = mutableListOf<LocalDeletionEntry>()
            val removed = mutableSetOf<Long>()

            plans.forEach { plan ->
                if (stopRequested.get()) {
                    plan.skipped = true
                    return@forEach
                }
                val outcome = when {
                    plan.dir != null -> deleteTree(plan.dir) {
                        done++
                        _progress.value = Progress(Progress.Phase.DELETING, done = done, total = total)
                    }
                    directoryIsConfirmedGone(plan.entry.url) -> LocalDeletionOutcome.DELETED
                    else -> LocalDeletionOutcome.FAILED
                }
                reportEntries += plan.toEntry(outcome)
                if (outcome == LocalDeletionOutcome.DELETED) {
                    cleanupManga(plan.entry)
                    removed += plan.entry.id
                } else {
                    failed += plan.entry.title
                }
            }

            publishReport(
                unit = LocalDeletionUnit.COLLECTION,
                entries = reportEntries,
                skippedCount = plans.count { it.skipped },
                deletedMangaIds = removed,
            )
            return Result(deleted = removed.size, failed = failed, deletedMangaIds = removed)
        } finally {
            _progress.value = null
        }
    }

    private fun publishReport(
        unit: LocalDeletionUnit,
        entries: List<LocalDeletionEntry>,
        skippedCount: Int,
        deletedMangaIds: Set<Long> = emptySet(),
        keptFolders: List<String> = emptyList(),
        removedFolderMangaIds: Set<Long> = emptySet(),
    ) {
        // Clear the progress first: the result dialog and the progress dialog must never be on
        // screen in the same frame.
        _progress.value = null
        _lastReport.value = LocalDeletionReport(
            id = reportCounter.incrementAndGet(),
            unit = unit,
            entries = entries,
            cancelled = stopRequested.get(),
            skippedCount = skippedCount,
            deletedMangaIds = deletedMangaIds,
            finishedAt = System.currentTimeMillis(),
            keptFolders = keptFolders,
            removedFolderMangaIds = removedFolderMangaIds,
        )
    }

    private suspend fun cleanupChapters(chapters: List<Pair<ChapterTarget, String>>) {
        runCatching {
            // Covers must go first: the generated name is derived from the file on disk.
            chapters.forEach { (target, url) -> coverManager.deleteChapterCovers(target.id, url) }
            // Marks are keyed by chapter id, so the timestamp is only carried to rebuild the
            // entry; the store drops whatever matches the id.
            val known = mangaMarkStore.marks.value.associateBy { it.chapterId }
            mangaMarkStore.setAll(
                chapters.map { (target, _) ->
                    MangaMark(
                        mangaId = target.mangaId,
                        mangaTitle = target.mangaTitle,
                        chapterId = target.id,
                        chapterName = target.name,
                        markedAt = known[target.id]?.markedAt ?: System.currentTimeMillis(),
                    )
                },
                false,
            )
            chapterRepository.removeChaptersWithIds(chapters.map { (target, _) -> target.id })
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Failed to clean up deleted chapters" }
        }
    }

    private suspend fun cleanupManga(manga: MangaEntry) {
        runCatching {
            coverCache.deleteFromCache(manga.manga, deleteCustomCover = true)
            mangaMarkStore.clearManga(manga.id)
            mangaRepository.deleteMangaById(manga.id)
            localSource()?.removeListingEntry(manga.url)
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Failed to clean up deleted manga" }
        }
    }

    private fun removeEmptyDirectory(dir: UniFile?, approvedMetadata: Set<String>): Boolean {
        // A SAF directory delete can recurse even if the provider's preceding listing was empty.
        val path = dir?.filePath?.let(::File)?.toPath() ?: return false
        val snapshot = fileSystem.getDirectorySnapshot(dir, fresh = true)
        if (!snapshot.isAccessible || snapshot.files.any { !it.isFile || it.name !in approvedMetadata }) return false
        return runCatching {
            snapshot.files.forEach { Files.delete(path.resolve(requireNotNull(it.name))) }
            Files.delete(path)
        }.isSuccess
    }

    private fun deleteLoosePages(pages: List<UniFile>, onFileDeleted: () -> Unit): LocalDeletionOutcome {
        if (pages.isEmpty()) return LocalDeletionOutcome.FAILED
        var failed = false
        for (page in pages) {
            if (stopRequested.get()) return LocalDeletionOutcome.PARTIAL
            if (deleteFile(page)) onFileDeleted() else failed = true
        }
        return if (failed) LocalDeletionOutcome.PARTIAL else LocalDeletionOutcome.DELETED
    }

    private fun resolveChapterFile(base: UniFile, chapterUrl: String): UniFile? {
        val parts = chapterUrl.split('/', limit = 2)
        if (parts.size < 2) return null
        // Flat chapters share the work directory but only their loose pages are selected.
        if (isSingleChapterEntryName(parts[1])) return base.findFile(parts[0])
        return base.findFile(parts[0])?.findFile(parts[1])
    }

    /**
     * Whether [mangaUrl]'s directory is really absent rather than merely unreadable.
     *
     * Two reads that fail for different reasons have to agree: the base directory's own child
     * lookup, and the per-entry document query, which does not go through the provider's child
     * list at all. A provider that is briefly unable to answer either one leaves the removal
     * unconfirmed, and the caller reports a failure the reader can retry - never a deletion.
     *
     * The records that follow a confirmed removal cannot be rebuilt: history, bookmarks, chapter
     * titles and the custom cover all go with the row, so the cheap answer here is the wrong one.
     */
    private suspend fun directoryIsConfirmedGone(mangaUrl: String): Boolean {
        // The provider may still be serving the directory out of a cached listing; drop that
        // first, exactly as the bulk removal check does before it decides.
        fileSystem.refreshBaseDirectoryMetadata()
        val base = fileSystem.getBaseDirectory() ?: return false

        repeat(DIRECTORY_REMOVAL_CONFIRMATION_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(DIRECTORY_REMOVAL_CONFIRMATION_DELAY_MILLIS)
            if (runCatching { base.findFile(mangaUrl) }.getOrNull() != null) return false
            val state = runCatching { fileSystem.createMangaDirectoryEntryStateLookup()(mangaUrl) }
                .getOrDefault(LocalSourceDirectoryEntryState.UNKNOWN)
            if (state == LocalSourceDirectoryEntryState.MISSING) {
                logcat(LogPriority.INFO) { "Directory $mangaUrl is confirmed gone" }
                return true
            }
        }
        logcat(LogPriority.INFO) { "Directory $mangaUrl could not be confirmed gone" }
        return false
    }

    private fun localSource(): LocalSource? =
        sourceManager.get(LocalSource.ID) as? LocalSource

    /**
     * Counts what a top-level entry holds before anything is removed.
     *
     * Only listing calls, no per-file stat beyond the leaves: the walk is far cheaper than the
     * delete loop it feeds, and it is what makes the bar move in files instead of in chapters.
     */
    private fun measure(file: UniFile): Pair<Int, Long> {
        if (!file.isDirectory) {
            return 1 to runCatching { file.length() }.getOrDefault(0L)
        }
        var count = 0
        var size = 0L
        file.listFiles().orEmpty().forEach { child ->
            val (childCount, childSize) = measure(child)
            count += childCount
            size += childSize
        }
        return count to size
    }

    /**
     * Removes [file] and everything under it, reporting each removed file through [onFileDeleted].
     *
     * Checks [stopRequested] before every file so a stop lands within one IPC rather than after a
     * 3000-file directory. An aborted walk comes back as [LocalDeletionOutcome.PARTIAL]: part of
     * the entry is already gone, which is exactly what the receipt has to say.
     */
    private fun deleteTree(file: UniFile, onFileDeleted: () -> Unit): LocalDeletionOutcome {
        var failed = false
        var aborted = false

        fun rec(current: UniFile) {
            if (aborted) return
            if (stopRequested.get()) {
                aborted = true
                return
            }
            if (current.isDirectory) {
                current.listFiles().orEmpty().forEach { child -> if (!aborted) rec(child) }
                if (!aborted && !current.delete()) failed = true
            } else if (deleteFile(current)) {
                onFileDeleted()
            } else {
                failed = true
            }
        }

        rec(file)
        return if (aborted || failed) LocalDeletionOutcome.PARTIAL else LocalDeletionOutcome.DELETED
    }

    /**
     * A file that is already gone counts as deleted: the call asked for that state, and reporting
     * it as a failure would put "本来就不在" into the same bucket as "删不掉".
     */
    private fun deleteFile(file: UniFile): Boolean {
        if (file.delete()) return true
        return runCatching { !file.exists() }.getOrDefault(false)
    }

    /** A chapter picked for deletion. The file path is resolved by the service, not by the caller. */
    data class ChapterTarget(
        val id: Long,
        val mangaId: Long,
        val mangaTitle: String,
        val name: String,
    )

    data class MangaEntry(
        val id: Long,
        val url: String,
        val title: String,
        val manga: tachiyomi.domain.manga.model.Manga,
    )

    private class ChapterPlan(
        val target: ChapterTarget,
        val chapterUrl: String,
        val file: UniFile?,
        /** A folder-level single chapter: its file is the work directory itself. */
        val singleChapter: Boolean = false,
        var fileCount: Int = 0,
        var sizeBytes: Long = 0,
        var skipped: Boolean = false,
        var pages: List<UniFile> = emptyList(),
    ) {
        fun toEntry(outcome: LocalDeletionOutcome) = LocalDeletionEntry(
            mangaTitle = target.mangaTitle,
            name = target.name,
            relativePath = chapterUrl,
            fileCount = fileCount,
            sizeBytes = sizeBytes,
            outcome = outcome,
        )
    }

    private class CollectionPlan(
        val entry: MangaEntry,
        val dir: UniFile?,
        var fileCount: Int = 0,
        var sizeBytes: Long = 0,
        var skipped: Boolean = false,
    ) {
        fun toEntry(outcome: LocalDeletionOutcome) = LocalDeletionEntry(
            mangaTitle = entry.title,
            name = entry.title,
            relativePath = entry.url,
            fileCount = fileCount,
            sizeBytes = sizeBytes,
            outcome = outcome,
        )
    }

    companion object {
        /** Only these metadata files can be listed for optional empty-folder cleanup. */
        private val GENERATED_FILE_NAMES = setOf("cover.jpg", "ComicInfo.xml", ".nomedia", ".noxml")

        /**
         * How often an absent directory is re-checked before its removal is treated as confirmed.
         * A document provider that is mid-refresh can answer "no such child" for a directory that
         * is still there, so one answer is not enough to drop records that cannot be rebuilt.
         */
        private const val DIRECTORY_REMOVAL_CONFIRMATION_ATTEMPTS = 2
        private const val DIRECTORY_REMOVAL_CONFIRMATION_DELAY_MILLIS = 150L
    }
}
