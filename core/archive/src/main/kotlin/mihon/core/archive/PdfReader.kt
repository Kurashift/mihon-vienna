package mihon.core.archive

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Wrapper over [PdfRenderer] that renders a PDF's pages as images.
 *
 * A PDF has no image files to hand out the way an archive or an epub does, so the page is
 * rasterized on demand instead. The reader then treats each rendered page exactly like an
 * image page from any other local format, which keeps dual-page splitting, the automatic
 * background, zoom and "save/share/set as cover" working without any format-specific code.
 *
 * The renderer takes ownership of [pfd] and closes it, so this class must not close it too.
 * Only one page may be open at a time, so the whole open-render-close sequence is serialized
 * by [lock] even though [PdfRenderer] itself is thread-safe.
 */
class PdfReader(pfd: ParcelFileDescriptor) : Closeable {

    private val renderer = PdfRenderer(pfd)

    private val lock = Any()

    val pageCount: Int
        get() = synchronized(lock) { renderer.pageCount }

    /**
     * Renders [index] scaled to fit inside a [maxWidth] x [maxHeight] box, preserving the page's
     * aspect ratio.
     *
     * The page is scaled up when it is smaller than the box: PDF content is vector, so a page
     * rendered at its native size (1 point = 1 pixel, i.e. 72 dpi) is too coarse to zoom into.
     */
    fun renderPage(index: Int, maxWidth: Int, maxHeight: Int): Bitmap {
        synchronized(lock) {
            renderer.openPage(index).use { page ->
                // A malformed MediaBox reports a zero dimension, which would make the scale
                // infinite and the bitmap size meaningless. The callers all treat a failed page
                // as an unreadable one, so failing here is better than attempting the allocation.
                check(page.width > 0 && page.height > 0) {
                    "PDF page ${index + 1} has no usable size (${page.width}x${page.height})"
                }

                val scale = min(
                    maxWidth.toFloat() / page.width,
                    maxHeight.toFloat() / page.height,
                )
                val width = max(1, (page.width * scale).roundToInt())
                val height = max(1, (page.height * scale).roundToInt())

                // PdfRenderer only accepts ARGB_8888, and a null transform makes it fit the
                // whole page to the bitmap, so the box above is all the scaling that is needed.
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    // A fresh bitmap is transparent, and the renderer only paints where the page
                    // has content - so a page without a background of its own would leave those
                    // pixels transparent. They are encoded as black once the alpha is dropped on
                    // the way into a JPEG, turning a text-only page into black-on-black. Paper is
                    // white, so the page starts white.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } catch (e: Throwable) {
                    bitmap.recycle()
                    throw e
                }
                return bitmap
            }
        }
    }

    /**
     * [renderPage] encoded as a JPEG stream, which is what a page loader hands the reader.
     *
     * JPEG rather than PNG: a rendered page is a full-screen photographic-sized image, and the
     * lossless alternative costs an order of magnitude more memory to decode for a difference
     * that does not survive scaling to the screen.
     */
    fun renderPageAsStream(index: Int, maxWidth: Int, maxHeight: Int): InputStream {
        val bitmap = renderPage(index, maxWidth, maxHeight)
        try {
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
            return ByteArrayInputStream(output.toByteArray())
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        synchronized(lock) {
            renderer.close()
        }
    }

    companion object {
        /**
         * The box a reader page is rendered into. A PDF page is normally smaller than this at
         * 72 dpi, so it is scaled up, leaving text sharp when the reader zooms in.
         */
        const val READER_PAGE_MAX_SIZE = 2560

        /**
         * The box a manga cover is rendered into. Smaller than a reader page: the cover is
         * written to disk as `cover.jpg` and is never shown larger than a screen.
         */
        const val COVER_MAX_SIZE = 1200

        private const val JPEG_QUALITY = 90
    }
}
