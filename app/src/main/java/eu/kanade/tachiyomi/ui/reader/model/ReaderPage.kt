package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    /**
     * Pixel size of this page's image, measured from the image header before the bitmap is
     * decoded.
     *
     * The webtoon viewer gives the holder this height before the image arrives. A page that
     * changes height when its bitmap lands moves every page below it by the difference — hundreds
     * of pixels on a long strip — which is what makes the reader appear to jump while scrolling
     * into pages that were never measured.
     */
    var imageExtent: Pair<Int, Int>? = null
}
