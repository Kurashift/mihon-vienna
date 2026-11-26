package eu.kanade.tachiyomi.data.local

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.ensureActive
import tachiyomi.core.common.util.system.PageImageName
import kotlin.coroutines.coroutineContext

private const val CHAPTER_NAME_RESERVED_BYTES = 11

/**
 * Shared low-level UniFile transfer helpers extracted out of [LocalChapterTransferService] so the
 * transfer orchestration service stays compact and away from ART per-method size limits.
 */
internal suspend fun copyEntry(source: UniFile, destination: UniFile, onBytes: (Long) -> Unit) {
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

internal fun sizeOf(file: UniFile): Long = if (file.isDirectory) {
    file.listFiles().orEmpty().sumOf(::sizeOf)
} else {
    file.length().coerceAtLeast(0L)
}

internal fun deleteRecursively(file: UniFile): Boolean {
    if (file.isDirectory) {
        val children = file.listFiles().orEmpty()
        if (children.any { !deleteRecursively(it) }) return false
    }
    return file.delete()
}

internal fun isImportableFile(file: UniFile): Boolean {
    val name = file.name.orEmpty().lowercase()
    // The single source of truth is ImageType (via PageImageName), the same table the reader
    // and page counting use. Keeping a second handwritten list here is how a format can be
    // readable yet silently skipped during import. ComicInfo.xml is the one non-image file
    // a chapter folder keeps.
    return PageImageName.hasImageExtension(name) || name == "comicinfo.xml"
}

/**
 * The name a chapter is committed under, cleaned the same way the download path cleans its
 * directory names.
 *
 * The picked file keeps whatever its source allowed, and an internal-storage name may legally
 * carry characters that a FAT/exFAT card rejects; committing it there would fail the import with
 * nothing the reader could act on. Bytes are reserved for the extension appended at commit time,
 * so the truncation cannot eat it.
 */
internal fun chapterDestinationName(rawName: String): String {
    return DiskUtil.buildValidFilename(
        rawName.trim().ifBlank { "Chapter" },
        DiskUtil.MAX_FILE_NAME_BYTES - CHAPTER_NAME_RESERVED_BYTES,
    )
}

internal fun chapterImportKey(name: String, isFile: Boolean): String =
    (if (isFile) name.substringBeforeLast('.') else name).trim().lowercase()

internal suspend fun <T> withOrderedLocalChapterMutationLocks(
    mangaUrls: List<String>,
    block: suspend () -> T,
): T {
    suspend fun acquire(index: Int): T {
        if (index >= mangaUrls.size) return block()
        return withLocalChapterMutationLock(mangaUrls[index]) { acquire(index + 1) }
    }
    return acquire(0)
}
