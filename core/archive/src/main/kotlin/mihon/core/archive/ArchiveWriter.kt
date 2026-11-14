package mihon.core.archive

import android.content.Context
import android.system.Os
import android.system.StructStat
import com.hippo.unifile.UniFile
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Streams entries into a new archive of the given [ArchiveFormat].
 *
 * Used when a page is removed from a chapter archive: the surviving entries are copied into a
 * staged file, which then replaces the original. ZIP entries are stored uncompressed, matching the
 * downloader's writer: page images are already compressed, so deflating them again only costs CPU.
 * 7z has no store mode, so a rewrite recompresses the whole chapter (libarchive writes LZMA2 in a
 * solid block) and takes noticeably longer than the ZIP path. tar is uncompressed and cheap.
 */
class ArchiveWriter(
    private val context: Context,
    file: UniFile,
    format: ArchiveFormat,
) : Closeable {
    private val pfd = file.openFileDescriptor(context, "wt")
    private val archive = Archive.writeNew()
    private val entry = ArchiveEntry.new2(archive)
    private val buffer = ByteBuffer.allocateDirect(8192)

    init {
        try {
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
            when (format) {
                ArchiveFormat.ZIP -> {
                    Archive.writeSetFormatZip(archive)
                    Archive.writeZipSetCompressionStore(archive)
                }
                ArchiveFormat.SEVEN_ZIP -> Archive.writeSetFormat7zip(archive)
                ArchiveFormat.TAR -> Archive.writeSetFormatPaxRestricted(archive)
            }
            Archive.writeOpenFd(archive, pfd.fd)
        } catch (e: ArchiveException) {
            close()
            throw e
        }
    }

    fun write(file: UniFile, onBytes: (Long) -> Unit = {}) {
        file.openFileDescriptor(context, "r").use {
            val fd = it.fileDescriptor
            ArchiveEntry.clear(entry)
            ArchiveEntry.setPathnameUtf8(entry, file.name)
            val stat = Os.fstat(fd)
            ArchiveEntry.setStat(entry, stat.toArchiveStat())
            Archive.writeHeader(archive, entry)
            while (true) {
                buffer.clear()
                Os.read(fd, buffer)
                if (buffer.position() == 0) break
                onBytes(buffer.position().toLong())
                buffer.flip()
                Archive.writeData(archive, buffer)
            }
            Archive.writeFinishEntry(archive)
        }
    }

    fun write(
        name: String,
        size: Long,
        input: InputStream,
        isDirectory: Boolean = false,
        onBytes: (Long) -> Unit = {},
    ) {
        ArchiveEntry.clear(entry)
        ArchiveEntry.setPathnameUtf8(entry, name)
        ArchiveEntry.setSize(entry, if (isDirectory) 0L else size)
        ArchiveEntry.setFiletype(entry, if (isDirectory) ArchiveEntry.AE_IFDIR else ArchiveEntry.AE_IFREG)
        ArchiveEntry.setPerm(entry, if (isDirectory) 0x1ED else 0x1A4)
        Archive.writeHeader(archive, entry)
        val bytes = ByteArray(8192)
        while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            if (count == 0) continue
            onBytes(count.toLong())
            buffer.clear()
            buffer.put(bytes, 0, count)
            buffer.flip()
            Archive.writeData(archive, buffer)
        }
        Archive.writeFinishEntry(archive)
    }

    override fun close() {
        ArchiveEntry.free(entry)
        Archive.writeFree(archive)
        pfd.close()
    }
}

private fun StructStat.toArchiveStat() = ArchiveEntry.StructStat().apply {
    stDev = st_dev
    stMode = st_mode
    stNlink = st_nlink.toInt()
    stUid = st_uid
    stGid = st_gid
    stRdev = st_rdev
    stSize = st_size
    stBlksize = st_blksize
    stBlocks = st_blocks
    stAtim = st_atime.toTimespec()
    stMtim = st_mtime.toTimespec()
    stCtim = st_ctime.toTimespec()
    stIno = st_ino
}

private fun Long.toTimespec() = ArchiveEntry.StructTimespec().also { it.tvSec = this }
