package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class LocalSourceFileSystem(
    private val storageManager: StorageManager,
) {

    @Volatile
    private var directTreeListing: DirectTreeListing? = null

    data class DirectorySnapshot(
        val directory: UniFile?,
        val files: List<UniFile>,
        val isAccessible: Boolean,
        val isConfirmedEmpty: Boolean = false,
    ) {
        val lastModified: Long
            get() = if (isAccessible) directory?.lastModified() ?: -1L else -1L
    }

    fun getBaseDirectory(): UniFile? {
        return storageManager.getDirectLocalSourceDirectory()
            ?: storageManager.getLocalSourceDirectory()
    }

    fun getBaseDirectoryIdentityUri(): String? {
        return storageManager.getLocalSourceDirectory()?.uri?.toString()
            ?: getBaseDirectory()?.uri?.toString()
    }

    fun getFilesInBaseDirectory(): List<UniFile> {
        return getBaseDirectorySnapshot().files
    }

    /** Keeps a failed directory read distinct from a valid, empty local library. */
    fun getBaseDirectorySnapshot(): DirectorySnapshot {
        val directory = getBaseDirectory()
            ?: return DirectorySnapshot(null, emptyList(), isAccessible = false)
        return readDirectorySnapshot(directory, prefetchDirectChildren = true)
    }

    fun getMangaDirectory(name: String): UniFile? {
        return getBaseDirectory()
            ?.findFile(name)
            ?.takeIf { it.isDirectory }
    }

    fun getMangaDirectorySnapshot(name: String): DirectorySnapshot {
        val directory = getMangaDirectory(name)
            ?: return DirectorySnapshot(null, emptyList(), isAccessible = false)
        return readDirectorySnapshot(directory)
    }

    fun getFilesInMangaDirectory(name: String): List<UniFile> {
        return getMangaDirectorySnapshot(name).files
    }

    fun getFilesInDirectory(directory: UniFile): List<UniFile> {
        return readDirectorySnapshot(directory, usePrefetchedDirectChildren = true).files
    }

    /** Reads one directory directly instead of reusing the full-library prefetch tree. */
    fun getFreshFilesInDirectory(directory: UniFile): List<UniFile> {
        return readDirectorySnapshot(directory).files
    }

    private fun readDirectorySnapshot(
        directory: UniFile,
        prefetchDirectChildren: Boolean = false,
        usePrefetchedDirectChildren: Boolean = false,
    ): DirectorySnapshot {
        return runCatching {
            if (!directory.exists() || !directory.isDirectory) {
                return@runCatching DirectorySnapshot(directory, emptyList(), isAccessible = false)
            }

            val directPath = runCatching { directory.filePath }.getOrNull()?.let(::File)
            val files = if (directPath != null) {
                when {
                    prefetchDirectChildren -> readDirectTree(directPath)?.also { directTreeListing = it }
                        ?.childrenByParentPath
                        ?.get(directPath.absolutePath)
                    usePrefetchedDirectChildren ->
                        directTreeListing
                            ?.takeIf { directPath.absolutePath.startsWith(it.basePath + File.separator) }
                            ?.childrenByParentPath
                            ?.get(directPath.absolutePath)
                            ?: readDirectDirectory(directPath)
                    else -> readDirectDirectory(directPath)
                }
            } else {
                directory.listFiles()?.toList()
            } ?: return@runCatching DirectorySnapshot(directory, emptyList(), isAccessible = false)

            DirectorySnapshot(directory, files, isAccessible = true)
        }.getOrElse {
            DirectorySnapshot(directory, emptyList(), isAccessible = false)
        }
    }

    /**
     * Java's File.listFiles() aborts the process when a filesystem entry has malformed UTF-8.
     * Read raw null-delimited paths through Android's bundled find tool and drop only malformed
     * entries before constructing File/UniFile objects.
     */
    private fun readDirectTree(baseDirectory: File): DirectTreeListing? {
        val paths = runFind(baseDirectory, maxDepth = 2) ?: return null
        val rootPath = baseDirectory.absolutePath
        val childrenByParent = paths
            .asSequence()
            .map(::File)
            .filter { file -> file.absolutePath.startsWith(rootPath + File.separator) }
            .mapNotNull { file ->
                val parentPath = file.parentFile?.absolutePath ?: return@mapNotNull null
                UniFile.fromFile(file)?.let { parentPath to it }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        return DirectTreeListing(rootPath, childrenByParent)
    }

    private fun readDirectDirectory(directory: File): List<UniFile>? {
        return runFind(directory, maxDepth = 1)
            ?.mapNotNull { path -> UniFile.fromFile(File(path)) }
    }

    private fun runFind(directory: File, maxDepth: Int): List<String>? {
        val process = ProcessBuilder(
            "/system/bin/find",
            directory.absolutePath,
            "-mindepth",
            "1",
            "-maxdepth",
            maxDepth.toString(),
            "-print0",
        ).start()
        val output = process.inputStream.use { it.readBytes() }
        process.errorStream.use { it.readBytes() }
        if (process.waitFor() != 0) return null
        return decodeNullSeparatedUtf8Paths(output).paths
    }

    private data class DirectTreeListing(
        val basePath: String,
        val childrenByParentPath: Map<String, List<UniFile>>,
    )
}

internal data class DecodedPaths(
    val paths: List<String>,
    val malformedCount: Int,
)

internal fun decodeNullSeparatedUtf8Paths(bytes: ByteArray): DecodedPaths {
    val paths = mutableListOf<String>()
    var malformedCount = 0
    var start = 0
    for (index in bytes.indices) {
        if (bytes[index] != 0.toByte()) continue
        if (index > start) {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            runCatching {
                decoder.decode(ByteBuffer.wrap(bytes, start, index - start)).toString()
            }.onSuccess(paths::add)
                .onFailure { malformedCount++ }
        }
        start = index + 1
    }
    return DecodedPaths(paths, malformedCount)
}
