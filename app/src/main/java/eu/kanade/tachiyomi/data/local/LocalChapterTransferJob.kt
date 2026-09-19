package eu.kanade.tachiyomi.data.local

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.lifecycle.asFlow
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.await
import tachiyomi.domain.chapter.repository.ChapterRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID

class LocalChapterTransferJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    data class Status(
        val state: WorkInfo.State,
        val completed: Int = 0,
        val total: Int = 0,
        val currentName: String = "",
        val copiedBytes: Long = 0L,
        val totalBytes: Long = 0L,
    )

    private val service = Injekt.get<LocalChapterTransferService>()
    private val chapterRepository = Injekt.get<ChapterRepository>()
    private val notifier = LocalChapterTransferNotifier(context)

    override suspend fun doWork(): Result {
        setForegroundSafely()
        val manifestName = inputData.getString(KEY_MANIFEST)
        val manifestFile = manifestName?.let { File(context.filesDir, File(it).name) }
        val request = manifestFile?.let(::readManifest) ?: PersistedTransferRequest()
        val isMove = request.isMove
        val targetMangaId = request.targetMangaId
        val uris = request.uris.map(Uri::parse)
        val chapterIds = request.chapterIds
        val isGroupedImport = request.groups.isNotEmpty()
        if ((!isGroupedImport && targetMangaId < 0L) ||
            (!isMove && !isGroupedImport && uris.isEmpty()) ||
            (isMove && chapterIds.isEmpty())
        ) {
            manifestFile?.delete()
            return Result.failure()
        }
        val output = runCatching {
            LocalChapterTransferService.FolderOutput.valueOf(request.folderOutput)
        }.getOrDefault(LocalChapterTransferService.FolderOutput.DIRECTORY)
        val deleteSource = request.deleteSource

        return try {
            var lastProgressUpdate: ListenableFuture<Void>? = null
            val progressThrottler = LocalChapterTransferProgressThrottler { progress ->
                notifier.showProgress(progress)
                lastProgressUpdate = setProgressAsync(progress.toWorkData())
            }
            val onProgress = progressThrottler::update
            val result = if (isMove) {
                val chapters = chapterIds.mapNotNull { chapterRepository.getChapterById(it) }
                if (chapters.isEmpty()) return Result.failure()
                service.moveChapters(
                    chapters = chapters,
                    targetMangaId = targetMangaId,
                    onProgress = onProgress,
                ).let {
                    Triple(it.moved, it.skipped, it.failed)
                }
            } else if (isGroupedImport) {
                val groups = request.groups.map { group ->
                    LocalChapterTransferService.GroupImport(
                        targetMangaId = group.targetMangaId,
                        uris = group.uris.map(Uri::parse),
                    )
                }
                service.importGroupedUris(
                    groups = groups,
                    options = LocalChapterTransferService.Options(output, deleteSource),
                    onProgress = onProgress,
                ).let {
                    Triple(it.imported, it.skipped, it.failed)
                }
            } else {
                service.importUris(
                    uris = uris,
                    targetMangaId = targetMangaId,
                    options = LocalChapterTransferService.Options(output, deleteSource),
                    onProgress = onProgress,
                ).let {
                    Triple(it.imported, it.skipped, it.failed)
                }
            }
            progressThrottler.flush()
            lastProgressUpdate?.await()
            notifier.showResult(result.first, result.second, result.third, isMove)
            Result.success()
        } catch (_: CancellationException) {
            Result.success()
        } catch (_: Throwable) {
            Result.failure()
        } finally {
            manifestFile?.delete()
            notifier.cancel()
        }
    }

    /**
     * Reads the work order written by [start]. A missing or unreadable file yields an empty
     * request, which the validation above turns into a clean failure instead of a retry loop.
     */
    private fun readManifest(file: File): PersistedTransferRequest {
        if (!file.isFile) return PersistedTransferRequest()
        return runCatching { LocalTransferManifest.decode(file.readText()) }
            .getOrDefault(PersistedTransferRequest())
    }

    private fun LocalChapterTransferService.Progress.toWorkData() = workDataOf(
        KEY_COMPLETED to completed,
        KEY_TOTAL to total,
        KEY_CURRENT_NAME to currentName,
        KEY_COPIED_BYTES to copiedBytes,
        KEY_TOTAL_BYTES to totalBytes,
    )

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        Notifications.ID_LOCAL_TRANSFER_PROGRESS,
        notifier.buildInitialNotification(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        },
    )

    companion object {
        private const val TAG = "LocalChapterTransfer"
        private const val WORK_NAME = "LocalChapterTransfer"
        private const val KEY_MANIFEST = "manifest"
        private const val MANIFEST_PREFIX = "local-transfer-"
        private const val MANIFEST_SUFFIX = ".json"

        /** Also matches the pre-manifest "local-import-" files left by earlier versions. */
        private val MANIFEST_PREFIXES = listOf(MANIFEST_PREFIX, "local-import-")

        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT_NAME = "current_name"
        const val KEY_COPIED_BYTES = "copied_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"

        fun start(
            context: Context,
            uris: List<Uri>,
            targetMangaId: Long,
            options: LocalChapterTransferService.Options,
        ): Boolean {
            if (uris.isEmpty() || targetMangaId < 0L || isRunning(context)) return false
            return enqueue(
                context = context,
                request = PersistedTransferRequest(
                    targetMangaId = targetMangaId,
                    uris = uris.map(Uri::toString),
                    folderOutput = options.folderOutput.name,
                    deleteSource = options.deleteSourceAfterSuccess,
                ),
            )
        }

        fun startMove(
            context: Context,
            chapterIds: List<Long>,
            targetMangaId: Long,
        ): Boolean {
            if (chapterIds.isEmpty() || targetMangaId < 0L || isRunning(context)) return false
            return enqueue(
                context = context,
                request = PersistedTransferRequest(
                    targetMangaId = targetMangaId,
                    isMove = true,
                    chapterIds = chapterIds,
                ),
            )
        }

        fun startGrouped(
            context: Context,
            groups: List<LocalChapterTransferService.GroupImport>,
            options: LocalChapterTransferService.Options,
        ): Boolean {
            if (groups.isEmpty() || groups.any { it.targetMangaId < 0L || it.uris.isEmpty() } || isRunning(context)) {
                return false
            }
            return enqueue(
                context = context,
                request = PersistedTransferRequest(
                    groups = groups.map { group ->
                        PersistedTransferGroup(group.targetMangaId, group.uris.map(Uri::toString))
                    },
                    folderOutput = options.folderOutput.name,
                    deleteSource = options.deleteSourceAfterSuccess,
                ),
            )
        }

        /**
         * Persists [request] next to the app's files and enqueues a job that references it by name.
         *
         * The payload never rides in the work request: WorkManager rejects input data above 10 KB,
         * which a large import or move exceeds on its own. The manifest is deleted by the job when
         * it finishes; a failed enqueue deletes it here so nothing is left behind.
         */
        private fun enqueue(
            context: Context,
            request: PersistedTransferRequest,
        ): Boolean {
            // Only one transfer runs at a time and the caller already confirmed none is active, so
            // any manifest still on disk belongs to a job that died or was cancelled before it
            // could clean up. Clearing them here keeps the directory from growing without bound.
            deleteOrphanManifests(context)
            var manifest: File? = null
            return runCatching {
                val manifestFile = File(context.filesDir, "$MANIFEST_PREFIX${UUID.randomUUID()}$MANIFEST_SUFFIX")
                manifest = manifestFile
                manifestFile.writeText(LocalTransferManifest.encode(request))
                val workRequest = OneTimeWorkRequestBuilder<LocalChapterTransferJob>()
                    .addTag(TAG)
                    .setInputData(workDataOf(KEY_MANIFEST to manifestFile.name))
                    .build()
                context.workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, workRequest)
                true
            }.getOrElse {
                manifest?.delete()
                false
            }
        }

        private fun deleteOrphanManifests(context: Context) {
            runCatching {
                context.filesDir.listFiles { file ->
                    file.isFile && MANIFEST_PREFIXES.any { file.name.startsWith(it) } &&
                        file.name.endsWith(MANIFEST_SUFFIX)
                }?.forEach { it.delete() }
            }
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(WORK_NAME)
        }

        fun isRunning(context: Context): Boolean {
            val query = WorkQuery.Builder.fromTags(listOf(TAG))
                .addStates(listOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED))
                .build()
            return context.workManager.getWorkInfos(query).get().isNotEmpty()
        }

        fun statusFlow(context: Context): Flow<Status?> =
            context.workManager.getWorkInfosForUniqueWorkLiveData(WORK_NAME)
                .asFlow()
                .map { infos ->
                    val info = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
                    info?.let {
                        Status(
                            state = it.state,
                            completed = it.progress.getInt(KEY_COMPLETED, 0),
                            total = it.progress.getInt(KEY_TOTAL, 0),
                            currentName = it.progress.getString(KEY_CURRENT_NAME).orEmpty(),
                            copiedBytes = it.progress.getLong(KEY_COPIED_BYTES, 0L),
                            totalBytes = it.progress.getLong(KEY_TOTAL_BYTES, 0L),
                        )
                    }
                }
    }
}
