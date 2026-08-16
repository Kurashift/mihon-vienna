package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import mihon.core.archive.ArchiveReader
import tachiyomi.core.common.util.system.ImageUtil

/**
 * Loader used to load a chapter from an archive file.
 */
internal class ArchivePageLoader(
    private val file: UniFile,
    private val reader: ArchiveReader,
) : PageLoader() {
    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        // Reuse the sorted image entry names when the same archive is opened again, so
        // re-reading a chapter or preloading its neighbours doesn't re-scan the archive.
        val entryNames = LocalPageListCache.archivePageNames(
            uri = file.uri.toString(),
            size = file.length(),
            lastModified = file.lastModified(),
        ) {
            reader.useEntries { entries ->
                entries
                    .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                    .map { it.name }
                    .sortedWith { a, b -> a.compareToCaseInsensitiveNaturalOrder(b) }
                    .toList()
            }
        }
        return entryNames.mapIndexed { i, name ->
            ReaderPage(i).apply {
                stream = { reader.getInputStream(name)!! }
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        reader.close()
    }
}
