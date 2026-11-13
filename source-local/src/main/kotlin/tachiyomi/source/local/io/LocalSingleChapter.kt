package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalPageOrder
import tachiyomi.core.common.util.system.PageImageName
import tachiyomi.source.local.image.isChapterCoverName

/**
 * Virtual chapter name for a manga folder whose pages sit directly in it.
 *
 * The local source has always required the layout `Mihon/local/<title>/<chapter>/<pages>`, which
 * leaves a single-chapter work - the common shape for a downloaded doujin - with nowhere to put
 * its pages: `Mihon/local/<title>/001.webp` is not a chapter entry, so the folder was dropped from
 * the shelf entirely. A folder with no chapter entry of its own but with pages in it is therefore
 * treated as one chapter, addressed as `<title>/.__single__`.
 *
 * The leading dot is what keeps this name out of every real entry: [Archive.isChapterEntryName]
 * accepts a file only with a supported chapter extension and a directory only when it is not
 * dot-prefixed, and [PageImageName.hasImageExtension] rejects it as a page. It can therefore
 * never collide with something the user actually put on disk.
 *
 * The value is persisted in chapter URLs (reading progress, read marks and bookmarks hang off
 * those rows), so it must never change.
 */
const val LOCAL_SINGLE_CHAPTER_NAME = ".__single__"

/** Whether [name] is the virtual chapter of a folder whose pages sit directly in it. */
fun isSingleChapterEntryName(name: String): Boolean = name == LOCAL_SINGLE_CHAPTER_NAME

/**
 * Whether this local chapter URL addresses a folder whose pages sit directly in it.
 *
 * Callers that only hold a chapter URL - the manga screen's action menu, the chapter move flow -
 * use this to tell a chapter that owns a file from one that is the work directory itself.
 */
fun String.isSingleChapterChapterUrl(): Boolean = isSingleChapterEntryName(substringAfterLast('/'))

/**
 * Whether [name] can be a page of a folder-level single chapter.
 *
 * Only the folder's own loose files count: subdirectories are chapters of their own, and the
 * cover the app writes next to them (`cover.jpg`, `cover.webp`) is metadata rather than page 1 -
 * in the two-level layout it lives above the chapter folder and never becomes a page, so the flat
 * layout has to exclude it explicitly or every single-chapter work would open on its own cover.
 */
fun isLoosePageName(name: String): Boolean {
    return PageImageName.hasImageExtension(name) && !isChapterCoverName(name)
}

/**
 * The loose pages of a manga folder, in the order the directory listed them.
 *
 * This is what a folder-level single chapter is made of. Callers that already know the folder has
 * a chapter entry of its own must not use it: those pages belong to no chapter.
 */
fun loosePages(files: List<UniFile>): List<UniFile> {
    return files.filter { file -> !file.isDirectory && isLoosePageName(file.name.orEmpty()) }
}

/**
 * The page a folder-level single work shows as its cover.
 *
 * Reading order rather than directory order, so the card shows the work's page 1 and matches what
 * the reader opens on.
 */
fun firstPageInReadingOrder(pages: List<UniFile>): UniFile? {
    return pages.minWithOrNull { first, second ->
        first.name.orEmpty().compareToCaseInsensitiveNaturalPageOrder(second.name.orEmpty())
    }
}
