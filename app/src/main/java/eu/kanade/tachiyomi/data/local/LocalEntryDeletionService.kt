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
import tachiyomi.source.local.io.Archive
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
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
        /**
         * Manga rows dropped as a side effect of this deletion: a work that lost its last chapter
         * loses its row with the directory. Callers holding those ids have nothing left to show.
         */
        val deletedMangaIds: Set<Long> = emptySet(),
    )

    /** Starts a chapter deletion and returns immediately. Ignored while another run is active. */
    fun startDeleteChapters(targets: List<ChapterTarget>) {
        if (targets.isEmpty()) return
        launchTask { deleteChapters(targets) }
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
     * Once a manga loses its last chapter its directory is removed as well, so no empty shell is
     * left behind on disk. The directory is only taken when everything inside is either gone or
     * generated by the app itself (`cover.jpg` / `ComicInfo.xml` / `.nomedia`); anything the user
     * put there keeps the directory alive and is reported instead.
     *
     * Chapter urls are looked up here rather than passed in, so a caller can never hand over a
     * stale or empty path.
     */
    suspend fun deleteChapters(targets: List<ChapterTarget>): Result = withContext(Dispatchers.IO) {
        mutex.withLock { runChapters(targets) }
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

    private suspend fun runChapters(targets: List<ChapterTarget>): Result {
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
                )
            }

            // Counting first: the bar needs a total that does not shrink as the loop runs.
            _progress.value = Progress(Progress.Phase.COUNTING, done = 0, total = 0)
            var total = 0
            plans.forEach { plan ->
                val file = plan.file ?: return@forEach
                val (count, size) = measure(file)
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
                val outcome = deleteTree(file) {
                    done++
                    _progress.value = Progress(Progress.Phase.DELETING, done = done, total = total)
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

            // Works that just lost their last chapter: drop the directory too so it disappears
            // from the library immediately instead of lingering until the next full refresh.
            val removedMangaIds = mutableSetOf<Long>()
            deleted.map { (target, _) -> target.mangaId }.distinct().forEach { mangaId ->
                val dirName = resolved.firstOrNull { (target, _) -> target.mangaId == mangaId }
                    ?.second?.url?.substringBefore('/')
                when (dirName?.let { removeDirectoryIfEmptied(base, it) }) {
                    true -> {
                        cleanupEmptiedManga(mangaId)
                        removedMangaIds += mangaId
                    }
                    false -> logcat(LogPriority.INFO) {
                        "Kept non-empty directory $dirName; it still holds unrelated files"
                    }
                    null -> Unit
                }
            }

            publishReport(
                unit = LocalDeletionUnit.CHAPTER,
                entries = entries,
                skippedCount = plans.count { it.skipped },
                deletedMangaIds = removedMangaIds,
            )
            return Result(
                deleted = deleted.size,
                failed = failed,
                deletedMangaIds = removedMangaIds,
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

    /**
     * Deletes a manga directory that no longer holds a single chapter.
     *
     * Returns true when the directory is gone, false when it was kept because it still contains
     * something the app did not create, and null when there was nothing to decide.
     */
    private fun removeDirectoryIfEmptied(base: UniFile, dirName: String): Boolean? {
        val dir = base.findFile(dirName) ?: return null
        if (!dir.isDirectory) return null

        val leftover = dir.listFiles().orEmpty()
        if (leftover.any { Archive.isChapterEntry(it) }) return false
        // Anything not generated by the app is the user's business; keep the directory.
        if (leftover.any { it.name !in GENERATED_FILE_NAMES }) {
            logcat(LogPriority.INFO) { "Directory $dirName holds unrelated files; keeping it" }
            return false
        }

        val removed = leftover.all { it.delete() } && dir.delete()
        if (!removed) {
            logcat(LogPriority.ERROR) { "Failed to remove emptied directory $dirName" }
        }
        return removed
    }

    /** Drops the database row of a manga whose directory was just removed. */
    private suspend fun cleanupEmptiedManga(mangaId: Long) {
        val manga = runCatching { mangaRepository.getMangaById(mangaId) }
            .onFailure { logcat(LogPriority.ERROR, it) { "Failed to load manga $mangaId" } }
            .getOrNull() ?: return
        cleanupManga(
            MangaEntry(
                id = manga.id,
                url = manga.url,
                title = manga.title,
                manga = manga,
            ),
        )
    }

    private fun resolveChapterFile(base: UniFile, chapterUrl: String): UniFile? {
        val parts = chapterUrl.split('/', limit = 2)
        if (parts.size < 2) return null
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
        var fileCount: Int = 0,
        var sizeBytes: Long = 0,
        var skipped: Boolean = false,
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
        /**
         * Files the app itself writes next to the chapters. A directory holding only these counts
         * as empty once its chapters are gone and may be removed.
         */
        private val GENERATED_FILE_NAMES = setOf("cover.jpg", "ComicInfo.xml", ".nomedia")

        /**
         * How often an absent directory is re-checked before its removal is treated as confirmed.
         * A document provider that is mid-refresh can answer "no such child" for a directory that
         * is still there, so one answer is not enough to drop records that cannot be rebuilt.
         */
        private const val DIRECTORY_REMOVAL_CONFIRMATION_ATTEMPTS = 2
        private const val DIRECTORY_REMOVAL_CONFIRMATION_DELAY_MILLIS = 150L
    }
}
