package eu.kanade.tachiyomi.ui.reader.loader

import android.graphics.BitmapFactory
import logcat.LogPriority
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Reads a page image's pixel size from its header, without decoding the image.
 *
 * The webtoon viewer gives a holder this size before the image arrives, so the holder is laid out
 * at the height its image will occupy and nothing moves when the bitmap lands. A page laid out at
 * the viewport height instead collapses by hundreds of pixels when decoded, and everything below
 * it moves: slow scrolling hides that behind a relayout, a fast fling does not.
 *
 * Sizes are collected while the page list is built, from the same header read that decides whether
 * an entry is an image at all. That keeps the cost at zero extra reads and, more importantly, keeps
 * the measurement away from a reader that is already drawing pages: the local archive streams are
 * backed by one mmap, and reading them concurrently with a decode corrupts the heap.
 */
internal object PageExtentProbe {

    /** Enough for the header of every format the reader supports, JPEG with a large EXIF block included. */
    private const val HEADER_BYTES = 128 * 1024

    /**
     * Copies a bounded prefix of [openStream] into a plain array.
     *
     * [mihon.core.archive.ArchiveInputStream] hands libarchive the whole array regardless of the
     * requested window, so the stream must only ever be read with `off = 0` and `len = size`, and
     * must never be handed to `BitmapFactory.decodeStream`, which reads through a shared array with
     * a sub-range.
     */
    fun readHeader(openStream: () -> InputStream): ByteArray? {
        return try {
            openStream().use { input ->
                val buffer = ByteArray(HEADER_BYTES)
                var total = 0
                while (total < buffer.size) {
                    val read = input.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                if (total == 0) {
                    null
                } else if (total == buffer.size) {
                    buffer
                } else {
                    buffer.copyOf(total)
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Failed to read an image header" }
            null
        }
    }

    /** The image's `width to height` from an already-read [header], or null when it is not an image. */
    fun measureHeader(header: ByteArray): Pair<Int, Int>? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(header, 0, header.size, options)
            val width = options.outWidth
            val height = options.outHeight
            if (width > 0 && height > 0) width to height else null
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Failed to measure an image header" }
            null
        }
    }

    /** Whether [name] is a page, sniffing [header] when the name carries no usable extension. */
    fun isImagePage(name: String, header: ByteArray): Boolean {
        return ImageUtil.isImagePage(name) { ByteArrayInputStream(header) }
    }
}
