package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.archive.ArchiveWriter
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.components.material.SNACKBAR_ACTION_TIMEOUT_MILLIS
import tachiyomi.presentation.core.components.material.SnackbarTimeout
import tachiyomi.source.local.LocalSource
import java.util.UUID

class ReaderPageDeletionService(
    private val app: Application,
    private val sourceManager: SourceManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val mutablePendingSnackbar = MutableStateFlow<PendingSnackbar?>(null)
    val pendingSnackbar = mutablePendingSnackbar.asStateFlow()
    private val deferredTimer = SnackbarTimeout(scope, SNACKBAR_ACTION_TIMEOUT_MILLIS) {
        commitDeferredDeletion()
    }
    private var deferredTargets: List<ReaderPage.DeletionTarget> = emptyList()
    private var deferredCallbacks: List<(Result) -> Unit> = emptyList()
    private var nextSnackbarRevision = 0

    data class PendingSnackbar(
        val count: Int,
        val revision: Int,
        val deadlineMillis: Long,
    )

    fun deferAfterReaderExit(
        targets: List<ReaderPage.DeletionTarget>,
        count: Int,
        revision: Int,
        deadlineMillis: Long,
        onComplete: (Result) -> Unit,
    ) {
        if (targets.isEmpty()) return
        deferredTargets = (deferredTargets + targets).distinctBy(ReaderPage.DeletionTarget::key)
        deferredCallbacks = deferredCallbacks + onComplete
        val deadline = maxOf(deferredTimer.deadlineMillis, deadlineMillis)
        nextSnackbarRevision = maxOf(nextSnackbarRevision + 1, revision)
        mutablePendingSnackbar.value =
            PendingSnackbar(deferredTargets.size.coerceAtLeast(count), nextSnackbarRevision, deadline)
        deferredTimer.start(deadline)
    }

    fun setDeferredDeletionPressed(pressed: Boolean) {
        deferredTimer.setPressed(pressed)
    }

    fun undoDeferredDeletion() {
        deferredTimer.cancel()
        deferredTargets = emptyList()
        deferredCallbacks = emptyList()
        mutablePendingSnackbar.value = null
    }

    fun commitDeferredDeletion() {
        val targets = deferredTargets
        val callbacks = deferredCallbacks
        deferredTimer.cancel()
        deferredTargets = emptyList()
        deferredCallbacks = emptyList()
        mutablePendingSnackbar.value = null
        submit(targets) { result -> callbacks.forEach { it(result) } }
    }

    fun submit(
        targets: List<ReaderPage.DeletionTarget>,
        onComplete: (Result) -> Unit = {},
    ) {
        if (targets.isEmpty()) return
        scope.launch {
            // Acquire in submission order and publish the result before the next batch starts.
            mutex.withLock {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val failures = mutableListOf<ReaderPage.DeletionTarget>()
                        val archives = targets.filterIsInstance<ReaderPage.DeletionTarget.ArchiveEntry>()
                            .groupBy { it.archiveUri }
                        targets.filterIsInstance<ReaderPage.DeletionTarget.File>().forEach { target ->
                            runCatching { target.file.delete() }.onFailure {
                                logcat(LogPriority.ERROR, it) { "Failed to delete reader page file" }
                            }.getOrDefault(false).also { deleted -> if (!deleted) failures += target }
                        }
                        archives.values.forEach { entries ->
                            runCatching {
                                rewriteArchive(entries.first(), entries.mapTo(hashSetOf()) { it.entryName })
                            }.onFailure {
                                logcat(LogPriority.ERROR, it) { "Failed to rewrite reader archive" }
                            }.getOrDefault(false).also { rewritten ->
                                if (!rewritten) failures += entries
                            }
                        }
                        if (targets.any { it.invalidatesLocalSource() && it !in failures }) {
                            (sourceManager.get(LocalSource.ID) as? LocalSource)?.invalidateListing()
                        }
                        Result(targets.size - failures.size, failures.toList())
                    }.getOrElse {
                        logcat(LogPriority.ERROR, it) { "Reader page deletion failed" }
                        Result(deleted = 0, failedTargets = targets)
                    }
                }
                onComplete(result)
            }
        }
    }

    private fun rewriteArchive(target: ReaderPage.DeletionTarget.ArchiveEntry, removedNames: Set<String>): Boolean {
        val parent = target.archiveParent ?: return false
        val originalName = target.archiveName ?: return false
        val archive = parent.findFile(originalName) ?: return false
        val stagedName = ".mihon-page-delete-${UUID.randomUUID()}.tmp"
        val backupName = ".mihon-page-delete-${UUID.randomUUID()}.bak"
        val originalUri = target.archiveUri
        val staged = parent.createFile(stagedName) ?: return false

        return try {
            // Rewrite in the archive's own format: a .cb7 stays a 7z file and a .cbt stays a tar,
            // so the replacement never changes what other readers see at that path.
            val writer = ArchiveWriter(app, staged, target.format)
            try {
                archive.archiveReader(app).use { reader ->
                    reader.forEachEntry { entry, input ->
                        if (entry.name !in removedNames) {
                            writer.write(entry.name, entry.size, input, isDirectory = !entry.isFile)
                        }
                    }
                }
            } finally {
                writer.close()
            }

            if (!replaceReaderArchive(archive, staged, parent, originalName, stagedName, backupName)) {
                return false
            }
            eu.kanade.tachiyomi.ui.reader.loader.LocalPageListCache.invalidateArchive(originalUri)
            true
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to rewrite reader archive" }
            false
        } finally {
            runCatching { parent.findFile(stagedName)?.delete() }
        }
    }

    data class Result(val deleted: Int, val failedTargets: List<ReaderPage.DeletionTarget>)
}

internal fun replaceReaderArchive(
    archive: UniFile,
    staged: UniFile,
    parent: UniFile,
    originalName: String,
    stagedName: String,
    backupName: String,
): Boolean {
    var originalMoved = false
    var replacementPlaced = false
    try {
        if (!archive.renameTo(backupName)) return false
        originalMoved = true
        if (!staged.renameTo(originalName)) return false
        replacementPlaced = true
        // UniFile implementations may change their URI on rename. Resolve by name instead of
        // deleting either pre-rename handle, which could now point at the replacement.
        runCatching { parent.findFile(backupName)?.delete() }
        return true
    } finally {
        if (originalMoved && !replacementPlaced) {
            // Retain the backup if the provider also refuses rollback.
            runCatching { parent.findFile(backupName)?.renameTo(originalName) }
        }
        if (!replacementPlaced) runCatching { parent.findFile(stagedName)?.delete() }
    }
}

private fun ReaderPage.DeletionTarget.key(): String = when (this) {
    is ReaderPage.DeletionTarget.File -> "file:${file.uri}"
    is ReaderPage.DeletionTarget.ArchiveEntry -> "archive:$archiveUri:$entryName"
}

private fun ReaderPage.DeletionTarget.invalidatesLocalSource(): Boolean = when (this) {
    is ReaderPage.DeletionTarget.File -> invalidateLocalSource
    is ReaderPage.DeletionTarget.ArchiveEntry -> invalidateLocalSource
}
