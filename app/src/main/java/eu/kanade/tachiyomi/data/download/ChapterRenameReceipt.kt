package eu.kanade.tachiyomi.data.download

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

class ChapterRenameReceipt private constructor(
    val oldName: String,
    val newName: String,
    private val restore: suspend () -> Unit,
) {
    suspend fun rollback() = withContext(NonCancellable) {
        restore()
    }

    companion object {
        internal suspend fun rename(
            oldName: String,
            newName: String,
            exists: (String) -> Boolean,
            renameTo: (String) -> Boolean,
            updateCache: suspend (String) -> Unit,
        ): ChapterRenameReceipt = withContext(NonCancellable) {
            if (exists(newName)) {
                throw IOException("Chapter download already exists: $newName")
            }
            if (!renameTo(newName)) {
                throw IOException("Could not rename chapter download: $oldName -> $newName")
            }
            var currentName = newName
            val receipt = ChapterRenameReceipt(oldName, newName) {
                var failure: Throwable? = null
                try {
                    if (currentName != oldName) {
                        if (exists(oldName) || !renameTo(oldName)) {
                            throw IOException(
                                "Could not restore chapter download: $newName -> $oldName; files retained",
                            )
                        }
                        currentName = oldName
                    }
                } catch (error: Throwable) {
                    failure = error
                }
                try {
                    updateCache(currentName)
                } catch (error: Throwable) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
                failure?.let { throw it }
            }
            try {
                updateCache(newName)
            } catch (failure: Throwable) {
                try {
                    receipt.rollback()
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(ChapterRenameRollbackException(receipt, rollbackFailure))
                }
                throw failure
            }
            receipt
        }
    }
}

class ChapterRenameRollbackException(
    receipt: ChapterRenameReceipt,
    cause: Throwable,
) : IOException(
    "Failed to restore chapter download: ${receipt.newName} -> ${receipt.oldName}; files retained",
    cause,
)

internal suspend fun <T> withChapterRenameRollback(
    block: suspend (MutableList<ChapterRenameReceipt>) -> T,
): T {
    val receipts = mutableListOf<ChapterRenameReceipt>()
    try {
        return block(receipts)
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            receipts.asReversed().forEach { receipt ->
                try {
                    receipt.rollback()
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(ChapterRenameRollbackException(receipt, rollbackFailure))
                }
            }
        }
        throw failure
    }
}
