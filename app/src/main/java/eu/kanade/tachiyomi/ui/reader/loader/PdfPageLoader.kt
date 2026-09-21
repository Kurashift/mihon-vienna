package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.core.archive.PdfReader

/**
 * Loader used to load a chapter from a .pdf file.
 *
 * A PDF has no image files to list, so every page is a rendered one. Rendering is deferred to
 * the [ReaderPage.stream] lambda rather than done up front: a chapter is loaded as soon as its
 * neighbouring one is within preload range, and rasterizing every page of a large PDF at that
 * point would cost far more memory than the reader needs to show.
 */
internal class PdfPageLoader(private val reader: PdfReader) : PageLoader() {

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        return (0 until reader.pageCount).map { i ->
            ReaderPage(i).apply {
                stream =
                    { reader.renderPageAsStream(i, PdfReader.READER_PAGE_MAX_SIZE, PdfReader.READER_PAGE_MAX_SIZE) }
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
