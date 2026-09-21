package mihon.core.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import com.hippo.unifile.UniFile

internal fun UniFile.openFileDescriptor(context: Context, mode: String): ParcelFileDescriptor =
    context.contentResolver.openFileDescriptor(uri, mode) ?: error("Failed to open file descriptor: ${filePath ?: uri}")

fun UniFile.archiveReader(context: Context) = openFileDescriptor(context, "r").use { ArchiveReader(it) }

fun UniFile.epubReader(context: Context) = EpubReader(archiveReader(context))

/**
 * Unlike the readers above, the descriptor is handed over without closing it: [PdfReader] takes
 * ownership and releases it on close, and the renderer needs it to stay open while it works.
 * The constructor rejects a password-protected or corrupt file, so the descriptor is closed by
 * hand on that path instead.
 */
fun UniFile.pdfReader(context: Context): PdfReader {
    val pfd = openFileDescriptor(context, "r")
    return try {
        PdfReader(pfd)
    } catch (e: Throwable) {
        pfd.close()
        throw e
    }
}
