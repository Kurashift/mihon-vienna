package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.protobuf.ProtoBuf
import okio.BufferedSource
import okio.buffer
import okio.gzip
import okio.source
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class BackupDecoder(
    private val context: Context,
    private val parser: ProtoBuf = Injekt.get(),
) {
    /** Restore preflight fully decodes and discards each entry, then checks EOF including the gzip trailer. */
    fun decodeMetadata(
        uri: Uri,
        onlySources: Boolean = false,
        onTrackingId: ((Int) -> Unit)? = null,
        checkCancelled: () -> Unit = {},
    ): Metadata {
        return openReader(uri, if (onlySources) setOf(101) else null, checkCancelled).use { reader ->
            var entryCount = 0
            while (orInvalid { reader.nextEntry() }) {
                if (onlySources) {
                    orInvalid { reader.readTrackingIds { onTrackingId?.invoke(it) } }
                } else {
                    orInvalid { reader.readEntry() }.tracking.forEach { onTrackingId?.invoke(it.syncId) }
                    checkCancelled()
                }
                entryCount++
            }
            Metadata(entryCount, orInvalid { reader.readMetadata() })
        }
    }

    fun decodeEntries(uri: Uri): Flow<BackupManga> = flow {
        val coroutineContext = currentCoroutineContext()
        openReader(uri, emptySet()) { coroutineContext.ensureActive() }.use { reader ->
            while (orInvalid { reader.nextEntry() }) {
                emit(orInvalid { reader.readEntry() })
            }
        }
    }

    private fun openReader(
        uri: Uri,
        metadataFields: Set<Int>? = null,
        checkCancelled: () -> Unit = {},
    ): BackupEntryReader {
        checkCancelled()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
        val source = input.source().buffer()
        try {
            val signature = orInvalid { source.peek().readShort().toInt() }
            val backupSource: BufferedSource = when (signature) {
                0x1f8b -> source.gzip().buffer()
                MAGIC_JSON_SIGNATURE1, MAGIC_JSON_SIGNATURE2, MAGIC_JSON_SIGNATURE3 -> {
                    throw IOException(context.stringResource(MR.strings.invalid_backup_file_json))
                }
                else -> source
            }
            return if (metadataFields == null) {
                BackupReader(backupSource, parser, checkCancelled = checkCancelled)
            } else {
                BackupReader(backupSource, parser, metadataFields, checkCancelled)
            }
        } catch (error: Throwable) {
            try {
                source.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            throw error
        }
    }

    private inline fun <T> orInvalid(block: () -> T): T {
        return try {
            block()
        } catch (error: IOException) {
            throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown), error)
        }
    }

    data class Metadata(
        val entryCount: Int,
        val backup: Backup,
    )

    companion object {
        private const val MAGIC_JSON_SIGNATURE1 = 0x7b7d
        private const val MAGIC_JSON_SIGNATURE2 = 0x7b22
        private const val MAGIC_JSON_SIGNATURE3 = 0x7b0a
    }
}
