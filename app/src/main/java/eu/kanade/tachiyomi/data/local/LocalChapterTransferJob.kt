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
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        val targetMangaId = inputData.getLong(KEY_TARGET_MANGA_ID, -1L)
        val isMove = inputData.getBoolean(KEY_IS_MOVE, false)
        val groupedManifestName = inputData.getString(KEY_GROUPED_MANIFEST)
        val uris = inputData.getStringArray(KEY_URIS)?.map(Uri::parse).orEmpty()
        val chapterIds = inputData.getLongArray(KEY_CHAPTER_IDS)?.toList().orEmpty()
        val isGroupedImport = !groupedManifestName.isNullOrBlank()
        if ((!isGroupedImport && targetMangaId < 0L) ||
            (!isMove && !isGroupedImport && uris.isEmpty()) ||
            (isMove && chapterIds.isEmpty())
        ) {
            return Result.failure()
        }
        val output = runCatching {
            LocalChapterTransferService.FolderOutput.valueOf(
                inputData.getString(KEY_FOLDER_OUTPUT)
                    ?: LocalChapterTransferService.FolderOutput.DIRECTORY.name,
            )
        }.getOrDefault(LocalChapterTransferService.FolderOutput.DIRECTORY)
        val deleteSource = inputData.getBoolean(KEY_DELETE_SOURCE, false)

        val groupedManifest = groupedManifestName?.let { File(context.filesDir, File(it).name) }
        return try {
            val onProgress: (LocalChapterTransferService.Progress) -> Unit = { progress ->
                notifier.showProgress(progress)
                setProgressAsync(
                    workDataOf(
                        KEY_COMPLETED to progress.completed,
                        KEY_TOTAL to progress.total,
                        KEY_CURRENT_NAME to progress.currentName,
                        KEY_COPIED_BYTES to progress.copiedBytes,
                        KEY_TOTAL_BYTES to progress.totalBytes,
                    ),
                )
            }
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
            } else if (groupedManifest != null) {
                val groups = readGroupedManifest(groupedManifest)
                if (groups.isEmpty()) return Result.failure()
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
            notifier.showResult(result.first, result.second, result.third, isMove)
            Result.success()
        } catch (_: CancellationException) {
            Result.success()
        } catch (_: Throwable) {
            Result.failure()
        } finally {
            groupedManifest?.delete()
            notifier.cancel()
        }
    }

    private fun readGroupedManifest(file: File): List<LocalChapterTransferService.GroupImport> {
        if (!file.isFile) return emptyList()
        return LocalGroupedImportManifest.decode(file.readText()).map { group ->
            LocalChapterTransferService.GroupImport(
                targetMangaId = group.targetMangaId,
                uris = group.uris.map(Uri::parse),
            )
        }
    }

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
        private const val KEY_URIS = "uris"
        private const val KEY_TARGET_MANGA_ID = "target_manga_id"
        private const val KEY_FOLDER_OUTPUT = "folder_output"
        private const val KEY_DELETE_SOURCE = "delete_source"
        private const val KEY_IS_MOVE = "is_move"
        private const val KEY_CHAPTER_IDS = "chapter_ids"
        private const val KEY_GROUPED_MANIFEST = "grouped_manifest"
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
            val request = OneTimeWorkRequestBuilder<LocalChapterTransferJob>()
                .addTag(TAG)
                .setInputData(
                    workDataOf(
                        KEY_URIS to uris.map(Uri::toString).toTypedArray(),
                        KEY_TARGET_MANGA_ID to targetMangaId,
                        KEY_FOLDER_OUTPUT to options.folderOutput.name,
                        KEY_DELETE_SOURCE to options.deleteSourceAfterSuccess,
                        KEY_IS_MOVE to false,
                    ),
                )
                .build()
            context.workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            return true
        }

        fun startMove(
            context: Context,
            chapterIds: List<Long>,
            targetMangaId: Long,
        ): Boolean {
            if (chapterIds.isEmpty() || targetMangaId < 0L || isRunning(context)) return false
            val request = OneTimeWorkRequestBuilder<LocalChapterTransferJob>()
                .addTag(TAG)
                .setInputData(
                    workDataOf(
                        KEY_TARGET_MANGA_ID to targetMangaId,
                        KEY_IS_MOVE to true,
                        KEY_CHAPTER_IDS to chapterIds.toLongArray(),
                    ),
                )
                .build()
            context.workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            return true
        }

        fun startGrouped(
            context: Context,
            groups: List<LocalChapterTransferService.GroupImport>,
            options: LocalChapterTransferService.Options,
        ): Boolean {
            if (groups.isEmpty() || groups.any { it.targetMangaId < 0L || it.uris.isEmpty() } || isRunning(context)) {
                return false
            }
            var manifest: File? = null
            return runCatching {
                val manifestFile = File(context.filesDir, "local-import-${UUID.randomUUID()}.json")
                manifest = manifestFile
                manifestFile.writeText(
                    LocalGroupedImportManifest.encode(
                        groups.map { group ->
                            PersistedGroupedImport(group.targetMangaId, group.uris.map(Uri::toString))
                        },
                    ),
                )
                val request = OneTimeWorkRequestBuilder<LocalChapterTransferJob>()
                    .addTag(TAG)
                    .setInputData(
                        workDataOf(
                            KEY_GROUPED_MANIFEST to manifestFile.name,
                            KEY_FOLDER_OUTPUT to options.folderOutput.name,
                            KEY_DELETE_SOURCE to options.deleteSourceAfterSuccess,
                            KEY_IS_MOVE to false,
                        ),
                    )
                    .build()
                context.workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
                true
            }.getOrElse {
                manifest?.delete()
                false
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
