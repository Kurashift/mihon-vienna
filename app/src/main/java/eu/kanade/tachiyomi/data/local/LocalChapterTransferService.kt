package eu.kanade.tachiyomi.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
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
        /**
         * Whether the pick was a folder rather than a file.
         *
         * A folder is a collection: its name is the collection's name, and several picked folders
         * become several collections. A file is one chapter of a collection the reader names, so it
         * contributes no name of its own. The two cannot be told apart from [groups] alone, because
         * a folder whose contents are directly the chapters has no groups - and that is exactly the
         * case that used to fall through to a manual name.
         */
        val isDirectory: Boolean = false,
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

    /** Why a picked source contributed nothing to the import. */
    enum class SourceRejection {
        /** The provider refused to list the folder: permissions, or a restricted location. */
        Unreadable,

        /** Listed fine, but nothing inside is a book or a supported archive. */
        NoContent,

        /** The folder is the local library, so importing it would copy the library onto itself. */
        InsideLibrary,
    }

    /**
     * Outcome of inspecting one picked source.
     *
     * A rejected source carries [rejection] rather than simply being absent, because "nothing was
     * imported" and "nothing could be read" need different answers from the user, and a bare null
     * cannot tell them apart.
     */
    data class SourceInspection(
        val displayName: String,
        val preview: SourcePreview? = null,
        val rejection: SourceRejection? = null,
    )

    suspend fun inspectSource(uri: Uri): SourceInspection = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val file = UniFile.fromUri(context, uri)
            ?: return@withContext SourceInspection(
                displayName = uri.lastPathSegment.orEmpty(),
                rejection = SourceRejection.Unreadable,
            )
        val displayName = file.name.orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }

        // The local source already reads that folder in place, so importing it would copy the
        // library onto itself. Reported rather than silently skipped: the user picked this path
        // deliberately and needs to know why it did nothing.
        if (overlapsLocalLibrary(file)) {
            return@withContext SourceInspection(displayName, rejection = SourceRejection.InsideLibrary)
        }

        // A provider can decline to list a folder it will not grant access to. Treating that as
        // "empty" sends the user looking for content that is already there, so it stays distinct.
        if (file.isDirectory && file.listFiles() == null) {
            return@withContext SourceInspection(displayName, rejection = SourceRejection.Unreadable)
        }

        val grouped = expandGrouped(file)
        if (grouped != null) {
            return@withContext SourceInspection(
                displayName = displayName,
                preview = SourcePreview(
                    uri = uri,
                    displayName = displayName,
                    candidateNames = grouped.flatMap { it.candidateNames },
                    groups = grouped,
                    ignoredGroupCount = (file.listFiles().orEmpty().size - grouped.size).coerceAtLeast(0),
                    isDirectory = true,
                ),
            )
        }
        val candidates = expand(file)
        if (candidates.isEmpty()) {
            return@withContext SourceInspection(displayName, rejection = SourceRejection.NoContent)
        }
        SourceInspection(
            displayName = displayName,
            preview = SourcePreview(
                uri = uri,
                displayName = displayName,
                candidateNames = candidates.map { it.name },
                isDirectory = file.isDirectory,
            ),
        )
    }

    /**
     * Whether [file] is the local library folder or one of its ancestors.
     *
     * Importing either copies the library onto itself, which the picker never means: the local
     * source already indexes that folder in place. A folder *inside* the library is refused too,
     * because its contents are already local manga — importing one would only add a duplicate.
     *
     * Comparison is by document-id segments and only within one provider. Document ids are
     * provider-defined, and cloud providers often use ids without separators; those collapse to a
     * single segment, so the check degrades to exact equality instead of guessing at a prefix.
     */
    private fun overlapsLocalLibrary(file: UniFile): Boolean {
        val picked = documentSegments(file.uri)
        val library = listOfNotNull(
            documentSegments(fileSystem.getBaseDirectory()?.uri),
            documentSegments(fileSystem.getBaseDirectoryIdentityUri()?.toUri()),
        )
        return library.any { overlapsPath(it, picked) }
    }

    /**
     * Document id split into path segments, or null when [uri] is not a document URI.
     *
     * The external-storage provider encodes a volume plus a slash-separated path
     * (`primary:Mihon/local`), and splitting on that slash is what makes an ancestor comparable
     * to its descendants.
     */
    private fun documentSegments(uri: Uri?): List<String>? {
        uri ?: return null
        val id = runCatching {
            when {
                DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
                else -> null
            }
        }.getOrNull() ?: return null
        return id.split('/')
    }

    /**
     * True when the two document-id segment paths contain one another.
     *
     * Both directions count as a clash: picking the library folder would copy the library onto
     * itself, and picking something inside it would re-import works the local source already
     * reads in place. Either way the pick is refused rather than silently duplicated.
     */
    internal fun overlapsPath(a: List<String>?, b: List<String>?): Boolean {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
        val shorter = if (a.size <= b.size) a else b
        val longer = if (a.size <= b.size) b else a
        return longer.subList(0, shorter.size) == shorter
    }

    suspend fun moveChapters(
        chapters: List<Chapter>,
        targetMangaId: Long,
        onProgress: (Progress) -> Unit = {},
    ): MoveResult = withContext(kotlinx.coroutines.Dispatchers.IO) {
        // The target is picked moments earlier, but a local directory that disappears takes its
        // row with it. There is nothing to move into in that case, so fail the transfer instead
        // of reading a null row.
        val target = mangaRepository.getMangaByIdOrNull(targetMangaId)
            ?: error("Target manga $targetMangaId no longer exists")
        require(target.source == LocalSource.ID)
        var moved = 0
        var skipped = 0
        var failed = 0
        chapters.forEachIndexed { index, chapter ->
            val source = mangaRepository.getMangaByIdOrNull(chapter.mangaId)
            if (source == null) {
                // The source directory of this chapter is gone; the rest of the batch still moves.
                failed++
                onProgress(Progress(index + 1, chapters.size, chapter.name, 0L, 0L))
                return@forEachIndexed
            }
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

    internal data class Candidate(val file: UniFile, val name: String)

    suspend fun previewImport(
        uris: List<Uri>,
        targetMangaId: Long,
    ): ImportPreview = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val target = mangaRepository.getMangaByIdOrNull(targetMangaId)
            ?: error("Target manga $targetMangaId no longer exists")
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
        val target = mangaRepository.getMangaByIdOrNull(targetMangaId)
            ?: error("Target manga $targetMangaId no longer exists")
        require(target.source == LocalSource.ID) { "Target manga is not local" }
        val targetDir = fileSystem.getBaseDirectory()?.findFile(target.url)
            ?: fileSystem.getBaseDirectory()?.createDirectory(target.url)
            ?: error("Local source directory is unavailable")
        // Taken before anything is copied, so it is the moment the import began. Every chapter this
        // import registers is stamped strictly later (see the date_fetch below): the updates view
        // lists a chapter only while date_fetch > date_added, and both are read from the same
        // millisecond clock, so an import fast enough to land on the same millisecond would
        // otherwise hide the very chapters it just added.
        val importStartedAt = System.currentTimeMillis()
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
                                    // Strictly later than the import stamp, whatever the clock
                                    // resolution: the updates view lists a chapter only while
                                    // date_fetch > date_added, so a chapter sharing its work's
                                    // stamp would be imported and immediately invisible there.
                                    dateFetch = maxOf(System.currentTimeMillis(), importStartedAt + 1),
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
        if (imported > 0) {
            // New chapters are the work growing, so it re-enters the library at this moment and the
            // import date says so: a work that gained chapters is as new as one that just arrived,
            // and under the import-date order it belongs at the top with the rest of today's
            // arrivals. Only a real addition moves it - an import that was entirely duplicates
            // changed nothing, and shelving still leaves the date alone.
            try {
                withLocalChapterMutationLock(target.url) {
                    mangaRepository.update(
                        MangaUpdate(id = target.id, dateAdded = importStartedAt),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The chapters are on disk and in the database; the date is presentation. Failing
                // the import over it would report a transfer that succeeded as a failure.
            }
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

    internal fun expand(file: UniFile): List<Candidate> {
        if (!file.isDirectory) {
            return if (Archive.isChapterEntry(file)) {
                listOf(Candidate(file, file.name.orEmpty().substringBeforeLast('.')))
            } else {
                emptyList()
            }
        }
        val children = file.listFiles().orEmpty()
        val directImages = children.filter { !it.isDirectory && isImportableFile(it) }
        val directArchives = children.filter { !it.isDirectory && Archive.isChapterEntry(it) }
        // Dot-prefixed directories are download-client bookkeeping (`.thumb`), not chapters:
        // counting one as a child folder would also make a folder of loose images look like a
        // container and stop it from importing as a single chapter. Same rule as [expandGrouped].
        val childFolders = children.filter {
            it.isDirectory && !it.name.orEmpty().startsWith('.') &&
                it.listFiles().orEmpty().any(::isImportableFile)
        }
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
