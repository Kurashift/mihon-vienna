package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import tachiyomi.core.common.storage.extension

object Archive {

    private val SUPPORTED_ARCHIVE_TYPES = listOf("zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt")

    fun isSupported(file: UniFile): Boolean {
        return file.extension?.lowercase() in SUPPORTED_ARCHIVE_TYPES
    }

    /**
     * Whether a direct child of a manga directory counts as a chapter. This is the single rule
     * every local-source caller (scanning, page counts, covers, deletion) must agree on.
     *
     * A leading dot does not by itself make a file junk: gallery downloads keep the ellipsis in
     * titles such as "...Dakara Ore wa Imouto ni Saimin o Kaketa.cbz". Dot-prefixed files are
     * chapters whenever their format is supported, while dot-prefixed directories (thumbnail
     * caches, editor temp folders) stay hidden.
     */
    fun isChapterEntry(file: UniFile): Boolean {
        val name = file.name.orEmpty()
        if (name.isEmpty()) return false
        if (file.isDirectory) return !name.startsWith('.')
        return isSupported(file) || file.extension.equals("epub", true)
    }
}
