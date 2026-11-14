package mihon.core.archive

import android.content.Context
import com.hippo.unifile.UniFile
import java.io.Closeable
import java.io.InputStream

/**
 * Writes a ZIP archive with stored (uncompressed) entries.
 *
 * Kept as the downloader's and local chapter transfer's entry point; the actual writer lives in
 * [ArchiveWriter] so page deletion can write 7z and tar archives through the same code.
 */
class ZipWriter(
    val context: Context,
    file: UniFile,
) : Closeable {
    private val delegate = ArchiveWriter(context, file, ArchiveFormat.ZIP)

    fun write(file: UniFile, onBytes: (Long) -> Unit = {}) {
        delegate.write(file, onBytes)
    }

    fun write(
        name: String,
        size: Long,
        input: InputStream,
        isDirectory: Boolean = false,
        onBytes: (Long) -> Unit = {},
    ) {
        delegate.write(name, size, input, isDirectory, onBytes)
    }

    override fun close() {
        delegate.close()
    }
}
