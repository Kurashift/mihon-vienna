package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalPageOrder
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.source.local.image.isChapterCoverName
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Loader used to load a chapter from a directory given on [file].
 *
 * [excludeCoverFiles] is for a folder that is both the work and its only chapter: there the cover
 * the app writes (`cover.jpg`) sits next to the pages, so it has to be dropped or it would open as
 * page 1. A chapter folder of its own keeps every image it holds, including one that happens to be
 * called cover.webp, which is why the filter is opt-in rather than the default.
 */
internal class DirectoryPageLoader(
    val file: UniFile,
    private val fileSystem: LocalSourceFileSystem = Injekt.get(),
    private val excludeCoverFiles: Boolean = false,
) : PageLoader() {

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        return fileSystem.getFreshFilesInDirectory(file)
            .filter {
                !it.isDirectory && ImageUtil.isImagePage(it.name) { it.openInputStream() } &&
                    !(excludeCoverFiles && isChapterCoverName(it.name.orEmpty()))
            }
            .sortedWith { f1, f2 ->
                f1.name.orEmpty().compareToCaseInsensitiveNaturalPageOrder(f2.name.orEmpty())
            }
            .mapIndexed { i, file ->
                val streamFn = { file.openInputStream() }
                // Measured here, before any holder exists: a holder laid out at the viewport height
                // and collapsed when the image arrives moves every page below it, which is what
                // makes the reader jump while scrolling fast into pages that were never measured.
                val extent = PageExtentProbe.readHeader(streamFn)?.let(PageExtentProbe::measureHeader)
                ReaderPage(i).apply {
                    stream = streamFn
                    status = Page.State.Ready
                    imageExtent = extent
                }
            }
    }
}
