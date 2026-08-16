package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import tachiyomi.domain.storage.service.StorageManager

class LocalSourceFileSystem(
    private val storageManager: StorageManager,
) {

    data class DirectorySnapshot(
        val directory: UniFile?,
        val files: List<UniFile>,
        val isAccessible: Boolean,
    ) {
        val lastModified: Long
            get() = if (isAccessible) directory?.lastModified() ?: -1L else -1L
    }

    fun getBaseDirectory(): UniFile? {
        return storageManager.getLocalSourceDirectory()
    }

    fun getFilesInBaseDirectory(): List<UniFile> {
        return getBaseDirectory()?.listFiles().orEmpty().toList()
    }

    /** Keeps a failed directory read distinct from a valid, empty local library. */
    fun getBaseDirectorySnapshot(): DirectorySnapshot {
        val directory = getBaseDirectory()
            ?: return DirectorySnapshot(null, emptyList(), isAccessible = false)
        return runCatching {
            if (!directory.exists() || !directory.isDirectory) {
                DirectorySnapshot(directory, emptyList(), isAccessible = false)
            } else {
                val files = directory.listFiles()
                    ?: return@runCatching DirectorySnapshot(directory, emptyList(), isAccessible = false)
                DirectorySnapshot(directory, files.toList(), isAccessible = true)
            }
        }.getOrElse {
            DirectorySnapshot(directory, emptyList(), isAccessible = false)
        }
    }

    fun getMangaDirectory(name: String): UniFile? {
        return getBaseDirectory()
            ?.findFile(name)
            ?.takeIf { it.isDirectory }
    }

    fun getFilesInMangaDirectory(name: String): List<UniFile> {
        return getMangaDirectory(name)?.listFiles().orEmpty().toList()
    }
}
