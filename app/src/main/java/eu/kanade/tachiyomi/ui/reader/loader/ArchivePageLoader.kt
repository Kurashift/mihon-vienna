package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalPageOrder
import mihon.core.archive.ArchiveReader
import tachiyomi.source.local.io.Archive

/**
 * Loader used to load a chapter from an archive file.
 */
internal class ArchivePageLoader(
    private val file: UniFile,
    private val reader: ArchiveReader,
) : PageLoader() {
    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        // Reuse the sorted image entries when the same archive is opened again, so re-reading a
        // chapter or preloading its neighbours doesn't re-scan the archive. Each entry's header is
        // read once here, which is also where the page size comes from: the webtoon viewer needs it
        // before a holder is laid out, or the holder collapses when the image arrives and drags
        // everything below it along.
        val entries = LocalPageListCache.archivePages(
            uri = file.uri.toString(),
            size = file.length(),
            lastModified = file.lastModified(),
        ) {
            reader.useEntries { archiveEntries ->
                archiveEntries
                    .filter { Archive.isPageEntry(it.name, it.isFile) }
                    .mapNotNull { entry ->
                        val header = PageExtentProbe.readHeader { reader.getInputStream(entry.name)!! }
                            ?: return@mapNotNull null
                        if (!PageExtentProbe.isImagePage(entry.name, header)) return@mapNotNull null
                        val extent = PageExtentProbe.measureHeader(header)
                        LocalArchivePage(
                            name = entry.name,
                            width = extent?.first ?: 0,
                            height = extent?.second ?: 0,
                        )
                    }
                    .sortedWith { a, b -> a.name.compareToCaseInsensitiveNaturalPageOrder(b.name) }
                    .toList()
            }
        }
        return entries.mapIndexed { i, entry ->
            ReaderPage(i).apply {
                stream = { reader.getInputStream(entry.name)!! }
                status = Page.State.Ready
                if (entry.width > 0 && entry.height > 0) {
                    imageExtent = entry.width to entry.height
                }
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
