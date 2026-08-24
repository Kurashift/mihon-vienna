package eu.kanade.tachiyomi.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalPageOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mihon.core.archive.ZipWriter
import tachiyomi.core.common.storage.extension
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalChapterCoverManager
import tachiyomi.source.local.io.Archive
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Shared, serialized transfer logic for local chapter import and future chapter moves. */
class LocalChapterTransferService(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
    private val coverManager: LocalChapterCoverManager = Injekt.get(),
    private val mangaMarkStore: MangaMarkStore = Injekt.get(),
) {

    enum class FolderOutput { DIRECTORY, CBZ }

    data class Options(
        val folderOutput: FolderOutput = FolderOutput.DIRECTORY,
        val deleteSourceAfterSuccess: Boolean = false,
    )

    data class Progress(
        val completed: Int,
        val total: Int,
        val currentName: String,
        val copiedBytes: Long,
        val totalBytes: Long,
    )

    data class Result(
        val imported: Int,
        val skipped: Int,
        val failed: Int,
    )

    data class ImportPreview(
        val candidateNames: List<String>,
        val conflicts: List<String>,
    )

    data class SourcePreview(
        val uri: Uri,
        val displayName: String,
        val candidateNames: List<String>,
        val groups: List<SourceGroupPreview> = emptyList(),
        val ignoredGroupCount: Int = 0,
    )

    data class SourceGroupPreview(
        val uri: Uri,
        val name: String,
        val candidateNames: List<String>,
        val candidateUris: List<Uri>,
    )

    data class GroupImport(
        val targetMangaId: Long,
        val uris: List<Uri>,
    )

    data class GroupPreviewRequest(
        val targetUrl: String,
        val uris: List<Uri>,
    )

    data class MoveResult(val moved: Int, val skipped: Int, val failed: Int)

    suspend fun inspectSource(uri: Uri): SourcePreview? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val file = UniFile.fromUri(context, uri) ?: return@withContext null
        val grouped = expandGrouped(file)
        if (grouped != null) {
            return@withContext SourcePreview(
                uri = uri,
                displayName = file.name.orEmpty().ifBlank { uri.lastPathSegment.orEmpty() },
                candidateNames = grouped.flatMap { it.candidateNames },
                groups = grouped,
                ignoredGroupCount = (file.listFiles().orEmpty().size - grouped.size).coerceAtLeast(0),
            )
        }
        val candidates = expand(file)
        if (candidates.isEmpty()) return@withContext null
        SourcePreview(
            uri = uri,
            displayName = file.name.orEmpty().ifBlank { uri.lastPathSegment.orEmpty() },
            candidateNames = candidates.map { it.name },
        )
    }

    suspend fun moveChapters(
        chapters: List<Chapter>,
        targetMangaId: Long,
        onProgress: (Progress) -> Unit = {},
    ): MoveResult = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val target = mangaRepository.getMangaById(targetMangaId)
        require(target.source == LocalSource.ID)
        var moved = 0
        var skipped = 0
        var failed = 0
        chapters.forEachIndexed { index, chapter ->
            val source = mangaRepository.getMangaById(chapter.mangaId)
            if (source.id == target.id) {
                skipped++
                onProgress(Progress(index + 1, chapters.size, chapter.name, 0L, 0L))
                return@forEachIndexed
            }
            val fileName = chapter.url.substringAfterLast('/')
            val sourceDir = fileSystem.getBaseDirectory()?.findFile(source.url)
            val targetDir = fileSystem.getBaseDirectory()?.findFile(target.url)
                ?: fileSystem.getBaseDirectory()?.createDirectory(target.url)
            val sourceFile = sourceDir?.findFile(fileName)
            if (sourceFile == null || targetDir == null) {
                failed++
                onProgress(Progress(index + 1, chapters.size, fileName, 0L, 0L))
                return@forEachIndexed
            }
            val normalized = normalizeName(fileName.substringBeforeLast('.'))
            if (targetDir.listFiles().orEmpty().any {
                    normalizeName(it.name.orEmpty().substringBeforeLast('.')) == normalized
                }
            ) {
                skipped++
                onProgress(Progress(index + 1, chapters.size, fileName, 0L, 0L))
                return@forEachIndexed
            }
            var temp: UniFile? = null
            var movedDirectly = false
            var databaseRelocated = false
            try {
                movedDirectly = moveEntryDirect(sourceFile, sourceDir, targetDir, fileName)
                if (!movedDirectly) {
                    temp = if (sourceFile.isDirectory) {
                        targetDir.createDirectory(".mihon-move-${UUID.randomUUID()}")
                    } else {
                        targetDir.createFile(".mihon-move-${UUID.randomUUID()}.tmp")
                    } ?: error("Cannot create temporary move target")
                    copyEntry(sourceFile, temp) { copied ->
                        onProgress(Progress(index, chapters.size, fileName, copied, sourceFile.length()))
                    }
                    if (!temp.renameTo(fileName)) error("Cannot commit move target")
                }
                val newUrl = "${target.url}/$fileName"
                coverManager.migrateLegacyCover(chapter.id, chapter.url, newUrl)
                chapterRepository.relocateAll(
                    listOf(ChapterUpdate(id = chapter.id, mangaId = target.id, url = newUrl)),
                )
                databaseRelocated = true
                mangaMarkStore.relocate(chapter.id, target.id, target.title)
                if (!movedDirectly) {
                    // Local chapters are commonly directories. Delete recursively so a successful
                    // copy-based move does not leave a second source copy to be rediscovered.
                    deleteRecursively(sourceFile)
                }
                moved++
            } catch (e: CancellationException) {
                temp?.delete()
                rollbackMove(
                    chapter = chapter,
                    source = source,
                    target = target,
                    sourceDir = sourceDir,
                    targetDir = targetDir,
                    fileName = fileName,
                    movedDirectly = movedDirectly,
                    databaseRelocated = databaseRelocated,
                )
                throw e
            } catch (_: Throwable) {
                temp?.delete()
                rollbackMove(
                    chapter = chapter,
                    source = source,
                    target = target,
                    sourceDir = sourceDir,
                    targetDir = targetDir,
                    fileName = fileName,
                    movedDirectly = movedDirectly,
                    databaseRelocated = databaseRelocated,
                )
                failed++
            }
            onProgress(Progress(index + 1, chapters.size, fileName, 0L, 0L))
        }
        chapters.asSequence()
            .map { it.mangaId }
            .distinct()
            .forEach { sourceMangaId ->
                cleanupEmptySourceDirectory(sourceMangaId)
            }
        Injekt.get<SourceManager>().get(LocalSource.ID)?.let { (it as? LocalSource)?.invalidateListing() }
        MoveResult(moved, skipped, failed)
    }

    private suspend fun rollbackMove(
        chapter: Chapter,
        source: Manga,
        target: Manga,
        sourceDir: UniFile?,
        targetDir: UniFile,
        fileName: String,
        movedDirectly: Boolean,
        databaseRelocated: Boolean,
    ) {
        if (databaseRelocated) {
            runCatching {
                chapterRepository.relocateAll(
                    listOf(ChapterUpdate(id = chapter.id, mangaId = source.id, url = chapter.url)),
                )
            }
            runCatching { mangaMarkStore.relocate(chapter.id, source.id, source.title) }
        }
        if (movedDirectly && sourceDir != null) {
            runCatching {
                val movedFile = targetDir.findFile(fileName)
                if (movedFile != null) moveEntryDirect(movedFile, targetDir, sourceDir, fileName)
            }
        }
    }

    /**
     * Uses the storage provider's native move when source and target belong to the same storage.
     * Returns false for unsupported providers so the caller can safely fall back to copy/delete.
     */
    private fun moveEntryDirect(
        source: UniFile,
        sourceParent: UniFile,
        targetParent: UniFile,
        targetName: String,
    ): Boolean {
        val sourceUri = source.uri
        val sourceParentUri = sourceParent.uri
        val targetParentUri = targetParent.uri
        if (DocumentsContract.isDocumentUri(context, sourceUri) &&
            DocumentsContract.isDocumentUri(context, sourceParentUri) &&
            DocumentsContract.isDocumentUri(context, targetParentUri) &&
            sourceUri.authority == targetParentUri.authority
        ) {
            return runCatching {
                DocumentsContract.moveDocument(
                    context.contentResolver,
                    sourceUri,
                    sourceParentUri,
                    targetParentUri,
                ) != null
            }.getOrDefault(false)
        }

        val sourcePath = source.filePath?.let(::File)?.toPath() ?: return false
        val targetParentPath = targetParent.filePath?.let(::File)?.toPath() ?: return false
        val targetPath = targetParentPath.resolve(targetName)
        return runCatching {
            if (Files.getFileStore(sourcePath) != Files.getFileStore(targetParentPath)) return false
            runCatching {
                Files.move(sourcePath, targetPath, StandardCopyOption.ATOMIC_MOVE)
            }.recoverCatching {
                Files.move(sourcePath, targetPath)
            }.isSuccess
        }.getOrDefault(false)
    }

    /** Removes only a truly empty local manga directory after all of its chapters moved. */
    private suspend fun cleanupEmptySourceDirectory(mangaId: Long) {
        val manga = runCatching { mangaRepository.getMangaById(mangaId) }.getOrNull() ?: return
        val chapters = runCatching { chapterRepository.getChapterByMangaId(mangaId) }.getOrNull() ?: return
        if (chapters.isNotEmpty()) return
        val directory = fileSystem.getBaseDirectory()?.findFile(manga.url) ?: return
        val remaining = directory.listFiles().orEmpty()
            .filterNot { it.name.orEmpty().equals(".nomedia", ignoreCase = true) }
        if (remaining.isEmpty()) {
            directory.delete()
        }
    }

    private data class Candidate(val file: UniFile, val name: String)

    suspend fun previewImport(
        uris: List<Uri>,
        targetMangaId: Long,
    ): ImportPreview = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val target = mangaRepository.getMangaById(targetMangaId)
        require(target.source == LocalSource.ID) { "Target manga is not local" }
        val targetDir = fileSystem.getBaseDirectory()?.findFile(target.url)
        val existingNames = targetDir?.listFiles().orEmpty()
            .map { normalizeName(it.name.orEmpty().substringBeforeLast('.')) }
            .toHashSet()
        val candidates = uris
            .flatMap { expand(UniFile.fromUri(context, it) ?: return@flatMap emptyList()) }
            .distinctBy { it.file.uri.toString() }
        val seen = hashSetOf<String>()
        val conflicts = candidates.mapNotNull { candidate ->
            val normalized = normalizeName(candidate.name.substringBeforeLast('.'))
            if (normalized in existingNames || !seen.add(normalized)) candidate.name else null
        }
        ImportPreview(
            candidateNames = candidates.map { it.name },
            conflicts = conflicts.distinct(),
        )
    }

    suspend fun importUris(
        uris: List<Uri>,
        targetMangaId: Long,
        options: Options = Options(),
        onProgress: (Progress) -> Unit = {},
    ): Result = importUrisInternal(
        uris = uris,
        targetMangaId = targetMangaId,
        options = options,
        onProgress = onProgress,
        invalidateListing = true,
    )

    private suspend fun importUrisInternal(
        uris: List<Uri>,
        targetMangaId: Long,
        options: Options,
        onProgress: (Progress) -> Unit,
        invalidateListing: Boolean,
    ): Result = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val target = mangaRepository.getMangaById(targetMangaId)
        require(target.source == LocalSource.ID) { "Target manga is not local" }
        val targetDir = fileSystem.getBaseDirectory()?.findFile(target.url)
            ?: fileSystem.getBaseDirectory()?.createDirectory(target.url)
            ?: error("Local source directory is unavailable")
        val candidates = uris.flatMap { expand(UniFile.fromUri(context, it) ?: return@flatMap emptyList()) }
            .distinctBy { it.file.uri.toString() }
        val totalBytes = candidates.sumOf { sizeOfForTransfer(it.file, options) }
        var copiedBytes = 0L
        var imported = 0
        var skipped = 0
        var failed = 0
        var firstImportedChapterFileName: String? = null
        candidates.forEachIndexed { index, candidate ->
            coroutineContext.ensureActive()
            val destinationName = candidate.name.trim().ifBlank { "Chapter" }
            if (targetDir.findFile(destinationName) != null ||
                targetDir.listFiles()?.any {
                    normalizeName(it.name.orEmpty().substringBeforeLast('.')) ==
                        normalizeName(destinationName.substringBeforeLast('.'))
                } ==
                true
            ) {
                skipped++
                onProgress(Progress(index + 1, candidates.size, candidate.name, copiedBytes, totalBytes))
                return@forEachIndexed
            }
            var temp: UniFile? = null
            try {
                val tempName = ".mihon-import-${UUID.randomUUID()}"
                val staged = if (candidate.file.isDirectory && options.folderOutput == FolderOutput.DIRECTORY) {
                    targetDir.createDirectory(tempName) ?: error("Cannot create temporary chapter directory")
                } else {
                    targetDir.createFile("$tempName.tmp") ?: error("Cannot create temporary chapter file")
                }
                temp = staged
                if (candidate.file.isDirectory && options.folderOutput == FolderOutput.CBZ) {
                    val files = candidate.file.listFiles().orEmpty()
                        .filter { !it.isDirectory && isImportableFile(it) }
                        .sortedWith(
                            Comparator { a, b ->
                                a.name.orEmpty().compareToCaseInsensitiveNaturalPageOrder(b.name.orEmpty())
                            },
                        )
                    ZipWriter(context, staged).use { writer ->
                        files.forEach { file ->
                            coroutineContext.ensureActive()
                            writer.write(file) { copied ->
                                copiedBytes += copied
                                onProgress(Progress(index, candidates.size, candidate.name, copiedBytes, totalBytes))
                            }
                        }
                    }
                } else {
                    copyEntry(candidate.file, staged) { copied ->
                        copiedBytes += copied
                        onProgress(Progress(index, candidates.size, candidate.name, copiedBytes, totalBytes))
                    }
                }
                val committedName = if (staged.isDirectory) {
                    destinationName
                } else {
                    val extension = if (candidate.file.isDirectory) {
                        "cbz"
                    } else {
                        candidate.file.extension?.takeIf { it.isNotBlank() } ?: "cbz"
                    }
                    "$destinationName.$extension"
                }
                withLocalChapterMutationLock(target.url) {
                    if (!staged.renameTo(committedName)) error("Cannot commit imported chapter")
                    firstImportedChapterFileName = firstImportedChapterFileName ?: committedName
                    val chapterUrl = "${target.url}/$committedName"
                    if (chapterRepository.getChapterByUrlAndMangaId(chapterUrl, target.id) == null) {
                        val added = chapterRepository.addAll(
                            listOf(
                                Chapter.create().copy(
                                    mangaId = target.id,
                                    url = chapterUrl,
                                    name = destinationName,
                                    dateFetch = System.currentTimeMillis(),
                                    dateUpload = System.currentTimeMillis(),
                                ),
                            ),
                        )
                        if (added.isEmpty()) error("Chapter database commit failed")
                    }
                }
                if (options.deleteSourceAfterSuccess && canDeleteSource(candidate.file, options)) {
                    deleteRecursively(candidate.file)
                }
                imported++
            } catch (e: CancellationException) {
                temp?.delete()
                throw e
            } catch (_: Throwable) {
                temp?.delete()
                failed++
            }
            onProgress(Progress(index + 1, candidates.size, candidate.name, copiedBytes, totalBytes))
        }
        firstImportedChapterFileName?.let { chapterFileName ->
            try {
                val localSource = Injekt.get<SourceManager>().get(LocalSource.ID) as? LocalSource
                val coverUri = localSource?.ensureMangaCover(target.url, chapterFileName)
                if (coverUri != null && target.thumbnailUrl.isNullOrBlank()) {
                    mangaRepository.update(
                        MangaUpdate(
                            id = target.id,
                            thumbnailUrl = coverUri,
                            coverLastModified = System.currentTimeMillis(),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A cover can be regenerated later; it must not turn a completed import into a failure.
            }
        }
        if (invalidateListing) {
            Injekt.get<SourceManager>().get(LocalSource.ID)?.let { (it as? LocalSource)?.invalidateListing() }
        }
        Result(imported, skipped, failed)
    }

    suspend fun previewGroupedImport(
        groups: List<GroupPreviewRequest>,
    ): ImportPreview = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val previews = groups.map { group ->
            previewImportForTargetUrl(group.uris, group.targetUrl)
        }
        ImportPreview(
            candidateNames = previews.flatMap { it.candidateNames },
            conflicts = previews.flatMap { it.conflicts }.distinct(),
        )
    }

    private fun previewImportForTargetUrl(uris: List<Uri>, targetUrl: String): ImportPreview {
        val targetDir = fileSystem.getBaseDirectory()?.findFile(targetUrl)
        val existingNames = targetDir?.listFiles().orEmpty()
            .map { normalizeName(it.name.orEmpty().substringBeforeLast('.')) }
            .toHashSet()
        val candidates = uris
            .flatMap { expand(UniFile.fromUri(context, it) ?: return@flatMap emptyList()) }
            .distinctBy { it.file.uri.toString() }
        val seen = hashSetOf<String>()
        val conflicts = candidates.mapNotNull { candidate ->
            val normalized = normalizeName(candidate.name.substringBeforeLast('.'))
            if (normalized in existingNames || !seen.add(normalized)) candidate.name else null
        }
        return ImportPreview(
            candidateNames = candidates.map { it.name },
            conflicts = conflicts.distinct(),
        )
    }

    suspend fun importGroupedUris(
        groups: List<GroupImport>,
        options: Options = Options(),
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (groups.isEmpty()) return@withContext Result(0, 0, 0)
        val expanded = groups.flatMap { group ->
            group.uris.flatMap { uri ->
                expand(UniFile.fromUri(context, uri) ?: return@flatMap emptyList())
            }
        }.distinctBy { it.file.uri.toString() }
        val total = expanded.size
        val totalBytes = expanded.sumOf { sizeOfForTransfer(it.file, options) }
        var completed = 0
        var copiedBytes = 0L
        var imported = 0
        var skipped = 0
        var failed = 0
        try {
            for (group in groups) {
                coroutineContext.ensureActive()
                var groupCopiedBytes = 0L
                val result = importUrisInternal(
                    uris = group.uris,
                    targetMangaId = group.targetMangaId,
                    options = options,
                    onProgress = { progress ->
                        groupCopiedBytes = progress.copiedBytes
                        onProgress(
                            Progress(
                                completed = completed + progress.completed,
                                total = total,
                                currentName = progress.currentName,
                                copiedBytes = copiedBytes + progress.copiedBytes,
                                totalBytes = totalBytes,
                            ),
                        )
                    },
                    invalidateListing = false,
                )
                imported += result.imported
                skipped += result.skipped
                failed += result.failed
                val groupCandidates = group.uris.flatMap { uri ->
                    expand(UniFile.fromUri(context, uri) ?: return@flatMap emptyList())
                }
                completed += groupCandidates.size
                copiedBytes += groupCopiedBytes
            }
            Result(imported, skipped, failed)
        } finally {
            Injekt.get<SourceManager>().get(LocalSource.ID)?.let { (it as? LocalSource)?.invalidateListing() }
        }
    }

    private fun expand(file: UniFile): List<Candidate> {
        if (!file.isDirectory) {
            return if (Archive.isSupported(file) || file.extension.equals("epub", true)) {
                listOf(Candidate(file, file.name.orEmpty().substringBeforeLast('.')))
            } else {
                emptyList()
            }
        }
        val children = file.listFiles().orEmpty()
        val directImages = children.filter { !it.isDirectory && isImportableFile(it) }
        val directArchives = children.filter {
            !it.isDirectory &&
                (Archive.isSupported(it) || it.extension.equals("epub", true))
        }
        val childFolders = children.filter { it.isDirectory && it.listFiles().orEmpty().any(::isImportableFile) }
        return if (directImages.isNotEmpty() && childFolders.isEmpty() && directArchives.isEmpty()) {
            listOf(Candidate(file, file.name.orEmpty()))
        } else {
            (childFolders + directArchives)
                .sortedBy { it.name.orEmpty() }
                .map { Candidate(it, it.name.orEmpty().substringBeforeLast('.')) }
        }
    }

    /** Recognizes root/author/book layouts without making ordinary book containers recursive. */
    internal fun expandGrouped(file: UniFile): List<SourceGroupPreview>? {
        if (!file.isDirectory) return null
        val children = file.listFiles().orEmpty().filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
        if (children.isEmpty()) return null
        val groups = children.mapNotNull { groupDir ->
            val candidates = expand(groupDir)
            if (candidates.isEmpty()) return@mapNotNull null
            if (candidates.all { it.file.uri == groupDir.uri }) return@mapNotNull null
            SourceGroupPreview(
                uri = groupDir.uri,
                name = groupDir.name.orEmpty(),
                candidateNames = candidates.map { it.name },
                candidateUris = candidates.map { it.file.uri },
            )
        }
        return groups.takeIf { it.isNotEmpty() }
    }

    private suspend fun copyEntry(source: UniFile, destination: UniFile, onBytes: (Long) -> Unit) {
        if (source.isDirectory) {
            source.listFiles().orEmpty().forEach { child ->
                coroutineContext.ensureActive()
                val target = if (child.isDirectory) {
                    destination.createDirectory(child.name.orEmpty())
                } else {
                    destination.createFile(child.name.orEmpty())
                } ?: error("Cannot create transfer target for ${child.name.orEmpty()}")
                copyEntry(child, target, onBytes)
            }
        } else {
            source.openInputStream().use { input ->
                destination.openOutputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        onBytes(read.toLong())
                    }
                }
            }
        }
    }

    private fun sizeOf(file: UniFile): Long = if (file.isDirectory) {
        file.listFiles().orEmpty().sumOf(::sizeOf)
    } else {
        file.length().coerceAtLeast(0L)
    }

    private fun sizeOfForTransfer(file: UniFile, options: Options): Long {
        if (!file.isDirectory || options.folderOutput == FolderOutput.DIRECTORY) return sizeOf(file)
        return file.listFiles().orEmpty()
            .filter { !it.isDirectory && isImportableFile(it) }
            .sumOf(::sizeOf)
    }

    private fun canDeleteSource(file: UniFile, options: Options): Boolean {
        if (!file.isDirectory || options.folderOutput == FolderOutput.DIRECTORY) return true
        // CBZ mode intentionally ignores unrelated attachments. Keep the source directory when
        // anything outside the migrated image/metadata set would otherwise be deleted.
        return file.listFiles().orEmpty().all { child ->
            child.isDirectory || isImportableFile(child) ||
                child.name.orEmpty().equals(".nomedia", ignoreCase = true)
        } && file.listFiles().orEmpty().none { it.isDirectory }
    }

    private fun deleteRecursively(file: UniFile): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles().orEmpty()
            if (children.any { !deleteRecursively(it) }) return false
        }
        return file.delete()
    }

    private fun normalizeName(value: String): String = value.trim().lowercase()

    private fun isImportableFile(file: UniFile): Boolean {
        val name = file.name.orEmpty().lowercase()
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
            name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".avif") ||
            name.endsWith(".heif") || name.endsWith(".jxl") || name == "cover.jpg" ||
            name == "comicinfo.xml"
    }
}
