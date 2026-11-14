package mihon.core.archive

/**
 * Archive formats whose contents the app can write back.
 *
 * Reading is wider than writing: libarchive reads rar/cbr as well, but it has no RAR writer, so a
 * chapter that can be opened is not necessarily one whose pages can be deleted. This enum is the
 * single place that answers "can this file be rewritten in place", and it is used both to decide
 * whether the reader offers page deletion and to pick the writer for the rewrite.
 */
enum class ArchiveFormat {
    ZIP,
    SEVEN_ZIP,
    TAR,
    ;

    companion object {
        /**
         * Resolves the writable format for [fileName], or null when the file cannot be rewritten
         * in its own format. Extensions follow the comic-book conventions (cbz/cb7/cbt) and are
         * matched case-insensitively.
         */
        fun fromFileName(fileName: String?): ArchiveFormat? {
            return when (fileName.orEmpty().substringAfterLast('.', "").lowercase()) {
                "zip", "cbz" -> ZIP
                "7z", "cb7" -> SEVEN_ZIP
                "tar", "cbt" -> TAR
                else -> null
            }
        }
    }
}
