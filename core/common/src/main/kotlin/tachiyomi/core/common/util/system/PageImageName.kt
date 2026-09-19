package tachiyomi.core.common.util.system

/**
 * Name-based page rule for local chapters, kept free of Android types so it can be unit tested.
 *
 * A download client leaves its thumbnail next to the pages as a file called `.thumb`, and its
 * bytes are a JPEG. Deciding on the extension alone therefore rejects it, while a name that
 * carries no extension at all still has to be decided by sniffing the header - there is simply
 * nothing in the name to trust.
 */
object PageImageName {

    /**
     * Whether [name] can be one page of a chapter.
     *
     * [sniffHeader] is consulted only for a name with no dot in it, and reports whether the bytes
     * behind that name are an image.
     */
    fun isPage(name: String?, sniffHeader: (() -> Boolean)? = null): Boolean {
        if (name == null) return false
        if (name.contains('.')) return hasImageExtension(name)
        return sniffHeader?.invoke() == true
    }

    /** Whether [name] carries an extension that can only be an image. */
    fun hasImageExtension(name: String): Boolean {
        // Match extensions case-insensitively, and treat the long ".jpeg" form as ".jpg" so
        // page counting and cover picking don't miss common uppercase / long-form file names.
        val extension = name.substringAfterLast('.').lowercase()
        val normalizedExtension = if (extension == "jpeg") "jpg" else extension
        return ImageType.entries.any { it.extension == normalizedExtension }
    }
}
