package tachiyomi.source.local.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.source.local.io.Archive
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

data class LocalChapterCover(
    val chapterUrl: String,
    val version: Long,
)

data class LocalChapterCoverStats(
    val count: Int,
    val size: Long,
)

data class LocalChapterCoverGenerationResult(
    val total: Int,
    val generated: Int,
    val skipped: Int,
    val failed: Int,
    val removed: Int,
)

sealed interface LocalChapterCoverGenerationState {
    data object Idle : LocalChapterCoverGenerationState
    data class Running(val completed: Int, val total: Int) : LocalChapterCoverGenerationState
    data class Finished(val result: LocalChapterCoverGenerationResult) : LocalChapterCoverGenerationState
    data object Failed : LocalChapterCoverGenerationState
}

class LocalChapterCoverManager(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
) {

    private val coverDirectory = File(context.filesDir, COVER_DIRECTORY_NAME)
    private val coverVersionFile = File(context.filesDir, "$COVER_DIRECTORY_NAME.version")
    private val coverVersionLock = Any()
    private var coverVersionChecked = false
    private val generationSemaphore = Semaphore(MAX_CONCURRENT_GENERATIONS)
    private val keyLocks = ConcurrentHashMap<String, Mutex>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _generationState = MutableStateFlow<LocalChapterCoverGenerationState>(
        LocalChapterCoverGenerationState.Idle,
    )
    val generationState: StateFlow<LocalChapterCoverGenerationState> = _generationState.asStateFlow()

    private var generationJob: Job? = null

    fun startGenerateAll() {
        if (generationJob?.isActive == true) return
        generationJob = scope.launch {
            _generationState.value = LocalChapterCoverGenerationState.Running(0, 0)
            try {
                val result = generateAll { completed, total ->
                    _generationState.value = LocalChapterCoverGenerationState.Running(completed, total)
                }
                _generationState.value = LocalChapterCoverGenerationState.Finished(result)
            } catch (_: CancellationException) {
                _generationState.value = LocalChapterCoverGenerationState.Idle
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Unable to generate local chapter covers" }
                _generationState.value = LocalChapterCoverGenerationState.Failed
            }
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
    }

    suspend fun getOrCreate(data: LocalChapterCover): File? = withContext(Dispatchers.IO) {
        val chapterFile = findChapterFile(data.chapterUrl) ?: return@withContext null
        getOrCreate(data.chapterUrl, chapterFile)
    }

    /**
     * Replaces the generated chapter cover with a user-selected page. The generated cache file
     * keeps the same stable name, so the normal fetcher can serve it without changing how chapter
     * covers are requested. The caller is expected to touch the chapter's database row so readers
     * observe a new cache version.
     */
    suspend fun setCustom(chapterUrl: String, openStream: () -> InputStream): Boolean = withContext(Dispatchers.IO) {
        val chapterFile = findChapterFile(chapterUrl) ?: return@withContext false
        ensureCoverDirectoryVersion()
        val target = targetFile(chapterUrl, chapterFile)
        val lock = keyLocks.getOrPut(target.name) { Mutex() }
        try {
            lock.withLock {
                // Copy the reader page's stream to a local temp file first, then decode from it.
                // Decoding directly re-opens the archive-backed stream (and even opens it twice:
                // bounds + full decode), which races with the reader's own page decoding on the
                // same native archive handle and crashes with a SIGSEGV. The batch generation
                // path already copies first; do the same here.
                val bitmap = decodeCopiedThumbnail(openStream()) ?: return@withLock false
                missingFile(target).delete()
                writeCover(bitmap, target) != null
            }
        } finally {
            keyLocks.remove(target.name, lock)
        }
    }

    suspend fun generateAll(
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): LocalChapterCoverGenerationResult = withContext(Dispatchers.IO) {
        ensureCoverDirectoryVersion()
        val snapshot = fileSystem.getBaseDirectorySnapshot()
        if (!snapshot.isAccessible) {
            return@withContext LocalChapterCoverGenerationResult(0, 0, 0, 0, 0)
        }

        val chapters = snapshot.files
            .asSequence()
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            .flatMap { mangaDirectory ->
                mangaDirectory.listFiles()
                    .orEmpty()
                    .asSequence()
                    .filter(::isSupportedChapter)
                    .map { chapterFile ->
                        "${mangaDirectory.name.orEmpty()}/${chapterFile.name.orEmpty()}" to chapterFile
                    }
            }
            .toList()

        var generated = 0
        var skipped = 0
        var failed = 0
        val liveFiles = hashSetOf<String>()

        chapters.forEachIndexed { index, (chapterUrl, chapterFile) ->
            coroutineContext.ensureActive()
            val target = targetFile(chapterUrl, chapterFile)
            liveFiles += target.name
            liveFiles += missingFile(target).name
            when {
                target.isValidCover() -> skipped++
                getOrCreate(chapterUrl, chapterFile, retryMissing = true) != null -> generated++
                else -> failed++
            }
            onProgress(index + 1, chapters.size)
        }

        LocalChapterCoverGenerationResult(
            total = chapters.size,
            generated = generated,
            skipped = skipped,
            failed = failed,
            removed = removeOrphans(liveFiles),
        )
    }

    fun stats(): LocalChapterCoverStats {
        val files = coverDirectory.listFiles().orEmpty().filter { it.isValidCover() }
        return LocalChapterCoverStats(
            count = files.size,
            size = files.sumOf(File::length),
        )
    }

    fun clear(): Int {
        val files = coverDirectory.listFiles().orEmpty().filter(File::isFile)
        return files.count(File::delete)
    }

    private suspend fun getOrCreate(
        chapterUrl: String,
        chapterFile: UniFile,
        retryMissing: Boolean = false,
    ): File? {
        ensureCoverDirectoryVersion()
        val target = targetFile(chapterUrl, chapterFile)
        if (target.isValidCover()) return target
        val missing = missingFile(target)
        if (!retryMissing && missing.isFile) return null

        val lock = keyLocks.getOrPut(target.name) { Mutex() }
        return try {
            lock.withLock {
                if (target.isValidCover()) return@withLock target
                if (!retryMissing && missing.isFile) return@withLock null
                missing.delete()
                generationSemaphore.withPermit {
                    createThumbnail(chapterFile, target).also { result ->
                        if (result == null) {
                            coverDirectory.mkdirs()
                            missing.createNewFile()
                        }
                    }
                }
            }
        } finally {
            keyLocks.remove(target.name, lock)
        }
    }

    private fun createThumbnail(chapterFile: UniFile, target: File): File? {
        return try {
            val bitmap = when (val format = Format.valueOf(chapterFile)) {
                is Format.Directory -> decodeDirectoryCover(format.file)
                is Format.Archive -> decodeArchiveCover(format.file)
                is Format.Epub -> decodeEpubCover(format.file)
            } ?: return null

            writeCover(bitmap, target)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            target.delete()
            logcat(LogPriority.WARN, e) { "Unable to generate local chapter cover for ${chapterFile.name}" }
            null
        }
    }

    private fun writeCover(bitmap: Bitmap, target: File): File? {
        coverDirectory.mkdirs()
        val temp = File(coverDirectory, "${target.name}.tmp")
        return try {
            temp.outputStream().buffered().use { output ->
                @Suppress("DEPRECATION")
                check(bitmap.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, output))
            }
            if (target.exists()) {
                target.delete()
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
            }
            target.takeIf { it.isValidCover() }
        } finally {
            bitmap.recycle()
            temp.delete()
        }
    }

    private fun decodeDirectoryCover(directory: UniFile): Bitmap? {
        val image = directory.listFiles()
            .orEmpty()
            .filter { !it.isDirectory }
            .sortedWith { first, second ->
                first.name.orEmpty().compareToCaseInsensitiveNaturalOrder(second.name.orEmpty())
            }
            .firstOrNull { ImageUtil.isImage(it.name) { it.openInputStream() } }
            ?: return null
        return decodeCopiedThumbnail(image.openInputStream())
    }

    private fun decodeArchiveCover(archive: UniFile): Bitmap? {
        return archive.archiveReader(context).use { reader ->
            val entry = reader.useEntries { entries ->
                entries
                    .filter { it.isFile }
                    .sortedWith { first, second ->
                        first.name.compareToCaseInsensitiveNaturalOrder(second.name)
                    }
                    .firstOrNull { ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
            } ?: return@use null
            val input = reader.getInputStream(entry.name) ?: return@use null
            decodeCopiedThumbnail(input)
        }
    }

    private fun decodeEpubCover(epubFile: UniFile): Bitmap? {
        return epubFile.epubReader(context).use { epub ->
            val entry = epub.getImagesFromPages().firstOrNull() ?: return@use null
            val input = epub.getInputStream(entry) ?: return@use null
            decodeCopiedThumbnail(input)
        }
    }

    private fun decodeCopiedThumbnail(input: InputStream): Bitmap? {
        val temp = File.createTempFile("local-chapter-cover-", ".image", context.cacheDir)
        return try {
            input.use { source ->
                temp.outputStream().buffered().use { output ->
                    source.copyTo(output)
                }
            }
            decodeThumbnail { temp.inputStream().buffered() }
        } finally {
            temp.delete()
        }
    }

    private fun decodeThumbnail(openStream: () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (
            bounds.outWidth / (sampleSize * 2) >= TARGET_WIDTH &&
            bounds.outHeight / (sampleSize * 2) >= TARGET_HEIGHT
        ) {
            sampleSize *= 2
        }

        val decoded = openStream().use { input ->
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } ?: return null

        val scale = minOf(
            TARGET_WIDTH.toFloat() / decoded.width,
            TARGET_HEIGHT.toFloat() / decoded.height,
            1f,
        )
        if (scale >= 1f) return decoded

        val scaled = Bitmap.createScaledBitmap(
            decoded,
            max(1, (decoded.width * scale).roundToInt()),
            max(1, (decoded.height * scale).roundToInt()),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun findChapterFile(chapterUrl: String): UniFile? {
        val parts = chapterUrl.split('/', limit = 2)
        if (parts.size != 2) return null
        return fileSystem.getMangaDirectory(parts[0])
            ?.findFile(parts[1])
            ?.takeIf(::isSupportedChapter)
    }

    private fun targetFile(chapterUrl: String, chapterFile: UniFile): File {
        return File(
            coverDirectory,
            localChapterCoverCacheFileName(
                chapterUrl = chapterUrl,
                size = chapterFile.length(),
                lastModified = chapterFile.lastModified(),
                targetWidth = TARGET_WIDTH,
                targetHeight = TARGET_HEIGHT,
                quality = WEBP_QUALITY,
            ),
        )
    }

    private fun missingFile(target: File): File {
        return File(target.parentFile, "${target.nameWithoutExtension}.$MISSING_EXTENSION")
    }

    private fun removeOrphans(liveFiles: Set<String>): Int {
        if (!coverDirectory.exists()) return 0
        var removed = 0
        coverDirectory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.name !in liveFiles && file.delete()) removed++
        }
        return removed
    }

    private fun isSupportedChapter(file: UniFile): Boolean {
        return !file.name.orEmpty().startsWith('.') &&
            (file.isDirectory || Archive.isSupported(file) || file.name.orEmpty().endsWith(".epub", true))
    }

    private fun File.isValidCover(): Boolean {
        return isFile && extension == COVER_EXTENSION && length() > 0L
    }

    private fun ensureCoverDirectoryVersion() {
        synchronized(coverVersionLock) {
            if (coverVersionChecked) return
            val expectedVersion = "$TARGET_WIDTH-$TARGET_HEIGHT-$WEBP_QUALITY"
            val currentVersion = if (coverVersionFile.isFile) {
                runCatching { coverVersionFile.readText() }.getOrNull()
            } else {
                null
            }
            if (currentVersion != expectedVersion) {
                coverDirectory.listFiles().orEmpty().forEach { file ->
                    if (file.isFile) file.delete()
                }
                coverDirectory.mkdirs()
                coverVersionFile.writeText(expectedVersion)
            }
            coverVersionChecked = true
        }
    }

    companion object {
        private const val COVER_DIRECTORY_NAME = "local_chapter_covers"
        private const val COVER_EXTENSION = "webp"
        private const val MISSING_EXTENSION = "missing"
        private const val TARGET_WIDTH = 480
        private const val TARGET_HEIGHT = 672
        private const val WEBP_QUALITY = 80
        private const val MAX_CONCURRENT_GENERATIONS = 2
    }
}

internal fun localChapterCoverCacheFileName(
    chapterUrl: String,
    size: Long,
    lastModified: Long,
    targetWidth: Int,
    targetHeight: Int,
    quality: Int,
): String {
    val fingerprint = "$chapterUrl|$size|$lastModified|$targetWidth|$targetHeight|$quality"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(fingerprint.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "$digest.webp"
}
