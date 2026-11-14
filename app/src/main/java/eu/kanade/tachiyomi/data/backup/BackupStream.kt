package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.Closeable
import java.io.EOFException
import java.io.IOException

/** Encodes field 1 one manga at a time using the existing model serializer and unchanged wire numbers. */
internal class BackupWriter(
    private val sink: BufferedSink,
    private val parser: ProtoBuf,
    private val checkCancelled: () -> Unit = {},
) {
    var isEmpty = true
        private set

    fun writeEntry(entry: BackupManga) {
        checkCancelled()
        val bytes = parser.encodeToByteArray(BackupManga.serializer(), entry)
        checkCancelled()
        sink.writeBackupVarint(ENTRY_TAG)
        sink.writeBackupVarint(bytes.size.toLong())
        writeBytes(bytes)
        isEmpty = false
    }

    fun writeMetadata(metadata: Backup) {
        require(metadata.backupManga.isEmpty())
        checkCancelled()
        val bytes = parser.encodeToByteArray(Backup.serializer(), metadata)
        checkCancelled()
        writeBytes(bytes)
        if (bytes.isNotEmpty()) isEmpty = false
    }

    private fun writeBytes(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            checkCancelled()
            val count = minOf(bytes.size - offset, BUFFER_SIZE.toInt())
            sink.write(bytes, offset, count)
            offset += count
        }
    }
}

/** Owns its source; each pending entry must be consumed before advancing. Only selected metadata is retained. */
internal interface BackupEntryReader : Closeable {
    fun nextEntry(): Boolean
    fun readEntry(): BackupManga
    fun readTrackingIds(onTrackingId: (Int) -> Unit)
    fun skipEntry()
    fun readMetadata(): Backup
}

internal class BackupReader(
    source: BufferedSource,
    private val parser: ProtoBuf,
    private val metadataFields: Set<Int> = setOf(2, 101, 104, 105, 106, 107),
    private val checkCancelled: () -> Unit = {},
) : BackupEntryReader {
    private val source = object : ForwardingSource(source) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            checkCancelled()
            return super.read(sink, minOf(byteCount, BUFFER_SIZE))
        }
    }.buffer()
    private val metadata = Buffer()
    private var entryLength = NO_ENTRY

    override fun nextEntry(): Boolean {
        check(entryLength == NO_ENTRY)
        checkCancelled()
        while (!source.exhausted()) {
            checkCancelled()
            val tag = source.readBackupTag()
            val field = (tag ushr 3).toInt()
            if (field == 1) {
                if (tag != ENTRY_TAG) throw IOException("Invalid entry wire type")
                entryLength = source.readBackupLength()
                return true
            }
            if (field in metadataFields) {
                if (tag and 7 != 2L) throw IOException("Invalid metadata wire type")
                val length = source.readBackupLength()
                metadata.writeBackupVarint(tag)
                metadata.writeBackupVarint(length)
                var remaining = length
                while (remaining > 0) {
                    checkCancelled()
                    val count = minOf(remaining, BUFFER_SIZE)
                    metadata.write(source, count)
                    remaining -= count
                }
            } else {
                source.skipBackupField(tag, checkCancelled)
            }
        }
        return false
    }

    override fun readEntry(): BackupManga {
        checkCancelled()
        val bytes = source.readByteArray(takeEntryLength())
        checkCancelled()
        return decode(BackupManga.serializer(), bytes)
    }

    override fun readTrackingIds(onTrackingId: (Int) -> Unit) {
        source.readBackupMessage(takeEntryLength()) { entry ->
            var hasSource = false
            var hasUrl = false
            while (!entry.exhausted()) {
                checkCancelled()
                val tag = entry.readBackupTag()
                when (tag ushr 3) {
                    1L -> {
                        if (tag and 7 != 0L) throw IOException("Invalid source ID wire type")
                        entry.readBackupVarint()
                        hasSource = true
                    }
                    2L -> {
                        if (tag and 7 != 2L) throw IOException("Invalid URL wire type")
                        entry.skipBackupBytes(entry.readBackupLength(), checkCancelled)
                        hasUrl = true
                    }
                    18L -> {
                        if (tag and 7 != 2L) throw IOException("Invalid tracking wire type")
                        entry.readBackupMessage(entry.readBackupLength()) { tracking ->
                            var syncId: Int? = null
                            var hasLibraryId = false
                            while (!tracking.exhausted()) {
                                checkCancelled()
                                val trackingTag = tracking.readBackupTag()
                                when (trackingTag ushr 3) {
                                    1L -> {
                                        if (trackingTag and 7 != 0L) throw IOException("Invalid tracker ID wire type")
                                        syncId = tracking.readBackupVarint().toInt()
                                    }
                                    2L -> {
                                        if (trackingTag and 7 != 0L) throw IOException("Invalid library ID wire type")
                                        tracking.readBackupVarint()
                                        hasLibraryId = true
                                    }
                                    else -> tracking.skipBackupField(trackingTag, checkCancelled)
                                }
                            }
                            if (!hasLibraryId) throw IOException("Missing library ID")
                            onTrackingId(syncId ?: throw IOException("Missing tracker ID"))
                        }
                    }
                    else -> entry.skipBackupField(tag, checkCancelled)
                }
            }
            if (!hasSource || !hasUrl) throw IOException("Missing entry identity")
        }
    }

    override fun skipEntry() {
        source.skipBackupBytes(takeEntryLength(), checkCancelled)
    }

    override fun readMetadata(): Backup {
        if (entryLength != NO_ENTRY) skipEntry()
        while (nextEntry()) skipEntry()
        return decode(Backup.serializer(), metadata.readByteArray())
    }

    override fun close() {
        metadata.clear()
        source.close()
    }

    private fun takeEntryLength(): Long {
        check(entryLength != NO_ENTRY)
        return entryLength.also { entryLength = NO_ENTRY }
    }

    private fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        return try {
            parser.decodeFromByteArray(deserializer, bytes)
        } catch (error: SerializationException) {
            throw IOException(error)
        }
    }
}

private inline fun <T> BufferedSource.readBackupMessage(length: Long, block: (BufferedSource) -> T): T {
    val parent = this
    val bounded = object : Source {
        private var remaining = length

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (byteCount == 0L) return 0
            if (remaining == 0L) return -1
            val count = parent.read(sink, minOf(byteCount, remaining, BUFFER_SIZE))
            if (count == -1L) throw EOFException("Truncated backup message")
            remaining -= count
            return count
        }

        override fun timeout(): Timeout = parent.timeout()
        override fun close() = Unit
    }.buffer()
    return bounded.use { block(it) }
}

private fun BufferedSource.readBackupTag(): Long {
    val tag = readBackupVarint()
    if (tag !in 1..0xffffffffL || tag ushr 3 == 0L) throw IOException("Invalid field tag")
    return tag
}

private fun BufferedSource.readBackupVarint(): Long {
    var result = 0L
    for (shift in 0 until Long.SIZE_BITS step 7) {
        val byte = readByte().toInt() and 0xff
        if (shift == 63 && byte > 1) throw IOException("Malformed varint")
        result = result or ((byte and 0x7f).toLong() shl shift)
        if (byte and 0x80 == 0) return result
    }
    throw IOException("Malformed varint")
}

private fun BufferedSource.readBackupLength(): Long {
    val length = readBackupVarint()
    if (length !in 0..Int.MAX_VALUE) throw IOException("Invalid length $length")
    return length
}

private fun BufferedSource.skipBackupField(tag: Long, checkCancelled: () -> Unit) {
    val groups = ArrayDeque<Long>()
    var currentTag = tag
    while (true) {
        checkCancelled()
        when ((currentTag and 7).toInt()) {
            0 -> readBackupVarint()
            1 -> skipBackupBytes(8, checkCancelled)
            2 -> skipBackupBytes(readBackupLength(), checkCancelled)
            3 -> groups.addLast(currentTag ushr 3)
            4 -> {
                if (groups.removeLastOrNull() != currentTag ushr 3) throw IOException("Invalid end group")
            }
            5 -> skipBackupBytes(4, checkCancelled)
            else -> throw IOException("Unsupported wire type")
        }
        if (groups.isEmpty()) return
        currentTag = readBackupTag()
    }
}

private fun BufferedSource.skipBackupBytes(length: Long, checkCancelled: () -> Unit) {
    var remaining = length
    while (remaining > 0) {
        checkCancelled()
        val count = minOf(remaining, BUFFER_SIZE)
        skip(count)
        remaining -= count
    }
}

private fun BufferedSink.writeBackupVarint(value: Long) {
    var remaining = value
    while (remaining and 0x7fL.inv() != 0L) {
        writeByte((remaining and 0x7f).toInt() or 0x80)
        remaining = remaining ushr 7
    }
    writeByte(remaining.toInt())
}

private const val ENTRY_TAG = 10L
private const val NO_ENTRY = -1L
private const val BUFFER_SIZE = 8192L
