package mihon.core.archive

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.InputStream

class ArchiveReader(pfd: ParcelFileDescriptor) : Closeable {
    private val size = pfd.statSize
    private val address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)

    private val lock = Any()
    private var activeStreams = 0
    private var closeRequested = false
    private var unmapped = false

    fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T {
        val stream = openStream()
        return try {
            block(generateSequence { stream.getNextEntry() })
        } finally {
            stream.close()
        }
    }

    fun getInputStream(entryName: String): InputStream? {
        val stream = openStream()
        try {
            while (true) {
                val entry = stream.getNextEntry() ?: break
                if (entry.name == entryName) {
                    // The caller owns and closes this stream, which releases the reader's mmap
                    // reference via onStreamClosed.
                    return stream
                }
            }
        } catch (e: ArchiveException) {
            stream.close()
            throw e
        }
        stream.close()
        return null
    }

    /**
     * Marks this reader as closed. The underlying mmap is only unmapped once every stream that
     * was opened against it has also been closed, so an in-flight scan or page read on another
     * coroutine never touches memory that has already been released.
     */
    override fun close() {
        synchronized(lock) {
            if (!closeRequested) {
                closeRequested = true
                munmapIfIdle()
            }
        }
    }

    private fun openStream(): ArchiveInputStream {
        synchronized(lock) {
            check(!closeRequested) { "ArchiveReader is closed" }
            activeStreams++
        }
        return ArchiveInputStream(address, size, ::onStreamClosed)
    }

    private fun onStreamClosed() {
        synchronized(lock) {
            activeStreams--
            munmapIfIdle()
        }
    }

    // Must be called while holding [lock].
    private fun munmapIfIdle() {
        if (closeRequested && activeStreams == 0 && !unmapped) {
            unmapped = true
            Os.munmap(address, size)
        }
    }
}
