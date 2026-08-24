package tachiyomi.domain.storage.service

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import java.io.File

class StorageManager(
    private val context: Context,
    storagePreferences: StoragePreferences,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var baseDirUri: String = storagePreferences.baseStorageDirectory.get()

    private var baseDir: UniFile? = getBaseDir(baseDirUri)

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .shareIn(scope, SharingStarted.Lazily, 1)

    init {
        storagePreferences.baseStorageDirectory.changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                baseDirUri = uri
                baseDir = getBaseDir(uri)
                baseDir?.let { parent ->
                    parent.createDirectory(AUTOMATIC_BACKUPS_PATH)
                    parent.createDirectory(LOCAL_SOURCE_PATH).also {
                        DiskUtil.createNoMediaFile(it, context)
                    }
                    parent.createDirectory(DOWNLOADS_PATH).also {
                        DiskUtil.createNoMediaFile(it, context)
                    }
                }
                _changes.send(Unit)
            }
            .launchIn(scope)
    }

    private fun getBaseDir(uri: String): UniFile? {
        return UniFile.fromUri(context, uri.toUri())
            .takeIf { it?.exists() == true }
    }

    fun getAutomaticBackupsDirectory(): UniFile? {
        return baseDir?.createDirectory(AUTOMATIC_BACKUPS_PATH)
    }

    fun getDownloadsDirectory(): UniFile? {
        return baseDir?.createDirectory(DOWNLOADS_PATH)
    }

    fun getLocalSourceDirectory(): UniFile? {
        return baseDir?.createDirectory(LOCAL_SOURCE_PATH).also {
            DiskUtil.createNoMediaFile(it, context)
        }
    }

    /**
     * Direct-file fallback for devices whose document provider temporarily hides a valid tree.
     * This is only available after the user grants Android's all-files access. Other app storage
     * continues to use SAF, and the fallback remains scoped to the configured local-source folder.
     */
    fun getDirectLocalSourceDirectory(): UniFile? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !Environment.isExternalStorageManager()) {
            return null
        }
        val relativeBasePath = runCatching {
            val uri = baseDirUri.toUri()
            val documentId = when {
                DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
                else -> return null
            }
            primaryStorageRelativePath(documentId)
        }.getOrNull() ?: return null

        val directory = File(Environment.getExternalStorageDirectory(), relativeBasePath)
            .resolve(LOCAL_SOURCE_PATH)
        return UniFile.fromFile(directory)
            ?.takeIf { it.exists() && it.isDirectory }
            ?.also { DiskUtil.createNoMediaFile(it, context) }
    }

    /** Asks the document provider to discard stale directory metadata before a removal check. */
    fun refreshLocalSourceDirectoryMetadata() {
        val directory = baseDir?.findFile(LOCAL_SOURCE_PATH) ?: return
        if (directory.uri.scheme != "content") return
        runCatching {
            context.contentResolver.refresh(directory.uri, Bundle.EMPTY, null)
        }
    }

    /**
     * Checks one previously indexed manga directory without relying on the provider's child list.
     * A failed query remains unknown so it can never be mistaken for a confirmed deletion.
     */
    fun createLocalSourceDirectoryEntryStateLookup(): (String) -> LocalSourceDirectoryEntryState {
        val directDirectory = getDirectLocalSourceDirectory()
        if (directDirectory != null) {
            val directFile = directDirectory.filePath?.let(::File)
                ?: return { LocalSourceDirectoryEntryState.UNKNOWN }
            return { name ->
                val child = directFile.resolve(name)
                if (child.exists() && child.isDirectory) {
                    LocalSourceDirectoryEntryState.EXISTS
                } else {
                    LocalSourceDirectoryEntryState.MISSING
                }
            }
        }

        val directory = baseDir?.findFile(LOCAL_SOURCE_PATH)
            ?: return { LocalSourceDirectoryEntryState.UNKNOWN }
        val directoryUri = directory.uri
        if (directoryUri.authority != EXTERNAL_STORAGE_PROVIDER_AUTHORITY) {
            return { name ->
                val child = runCatching { directory.findFile(name) }.getOrNull()
                if (child == null) {
                    LocalSourceDirectoryEntryState.MISSING
                } else if (runCatching { child.exists() && child.isDirectory }.getOrDefault(false)) {
                    LocalSourceDirectoryEntryState.EXISTS
                } else {
                    LocalSourceDirectoryEntryState.MISSING
                }
            }
        }

        val parentDocumentId = runCatching { DocumentsContract.getDocumentId(directoryUri) }.getOrNull()
            ?: return { LocalSourceDirectoryEntryState.UNKNOWN }
        return { name ->
            val childDocumentId = storageChildDocumentId(parentDocumentId, name)
            if (childDocumentId == null) {
                LocalSourceDirectoryEntryState.UNKNOWN
            } else {
                val childUri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, childDocumentId)
                runCatching {
                    context.contentResolver.query(
                        childUri,
                        arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (!cursor.moveToFirst()) {
                            LocalSourceDirectoryEntryState.MISSING
                        } else if (
                            cursor.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
                        ) {
                            LocalSourceDirectoryEntryState.EXISTS
                        } else {
                            LocalSourceDirectoryEntryState.MISSING
                        }
                    } ?: LocalSourceDirectoryEntryState.UNKNOWN
                }.getOrDefault(LocalSourceDirectoryEntryState.UNKNOWN)
            }
        }
    }
}

enum class LocalSourceDirectoryEntryState {
    EXISTS,
    MISSING,
    UNKNOWN,
}

internal fun primaryStorageRelativePath(documentId: String): String? {
    val volume = documentId.substringBefore(':', missingDelimiterValue = "")
    if (!volume.equals("primary", ignoreCase = true)) return null

    val path = documentId.substringAfter(':', missingDelimiterValue = "")
        .replace('\\', '/')
        .trim('/')
    if (path.split('/').any { it == ".." }) return null
    return path
}

internal fun storageChildDocumentId(parentDocumentId: String, childName: String): String? {
    if (childName.isBlank() || childName == "." || childName == "..") return null
    if (childName.any { it == '/' || it == '\\' }) return null
    return "$parentDocumentId/$childName"
}

private const val AUTOMATIC_BACKUPS_PATH = "autobackup"
private const val DOWNLOADS_PATH = "downloads"
private const val LOCAL_SOURCE_PATH = "local"
private const val EXTERNAL_STORAGE_PROVIDER_AUTHORITY = "com.android.externalstorage.documents"
