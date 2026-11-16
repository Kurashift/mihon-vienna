package eu.kanade.tachiyomi.data.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.IOException

class ChapterRenameReceiptTest {

    @Test
    fun `database failure restores files and cache in reverse order`() = runTest {
        val storage = Storage("first", "second")
        val failure = IOException("database failed")

        try {
            withChapterRenameRollback { receipts ->
                receipts.add(storage.rename("first", "first-new"))
                receipts.add(storage.rename("second", "second-new"))
                throw failure
            }
            fail<Unit>("Expected database failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }

        assertEquals(setOf("first", "second"), storage.files)
        assertEquals(storage.files, storage.cache)
        assertEquals(listOf("first-new", "second-new", "second", "first"), storage.renames)
    }

    @Test
    fun `partial multi rename success is compensated after the next rename fails`() = runTest {
        val storage = Storage("first", "second")
        storage.rejectedNames.add("second-new")

        try {
            withChapterRenameRollback { receipts ->
                receipts.add(storage.rename("first", "first-new"))
                receipts.add(storage.rename("second", "second-new"))
            }
            fail<Unit>("Expected rename failure")
        } catch (_: IOException) {
            assertEquals(setOf("first", "second"), storage.files)
            assertEquals(storage.files, storage.cache)
        }
    }

    @Test
    fun `cache failure after file rename restores that file before propagating`() = runTest {
        val storage = Storage("first")
        val failure = IOException("cache failed")
        storage.cacheFailure = failure

        try {
            storage.rename("first", "first-new")
            fail<Unit>("Expected cache failure")
        } catch (actual: IOException) {
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
        }

        assertEquals(setOf("first"), storage.files)
        assertEquals(storage.files, storage.cache)
    }

    @Test
    fun `failed compensation retains the renamed file and reports its actual location`() = runTest {
        val storage = Storage("first", "second")
        val failure = IOException("database failed")

        try {
            withChapterRenameRollback { receipts ->
                receipts.add(storage.rename("first", "first-new"))
                receipts.add(storage.rename("second", "second-new"))
                storage.rejectedNames.add("second")
                throw failure
            }
            fail<Unit>("Expected database failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
            val rollbackFailure = actual.suppressed.single()
            assertTrue(rollbackFailure is ChapterRenameRollbackException)
            assertTrue(rollbackFailure.message.orEmpty().contains("second-new -> second"))
            assertTrue(rollbackFailure.message.orEmpty().contains("files retained"))
        }

        assertEquals(setOf("first", "second-new"), storage.files)
        assertEquals(storage.files, storage.cache)
    }

    @Test
    fun `existing destination is retained and never overwritten`() = runTest {
        val storage = Storage("first", "first-new")

        try {
            storage.rename("first", "first-new")
            fail<Unit>("Expected collision failure")
        } catch (_: IOException) {
            assertEquals(setOf("first", "first-new"), storage.files)
            assertEquals(storage.files, storage.cache)
            assertTrue(storage.renames.isEmpty())
        }
    }

    @Test
    fun `rollback destination collision retains both files and their cache entries`() = runTest {
        val storage = Storage("first")
        val failure = IOException("database failed")

        try {
            withChapterRenameRollback { receipts ->
                receipts.add(storage.rename("first", "first-new"))
                storage.files.add("first")
                throw failure
            }
            fail<Unit>("Expected database failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
            assertTrue(actual.suppressed.single() is ChapterRenameRollbackException)
        }

        assertEquals(setOf("first", "first-new"), storage.files)
        assertEquals(storage.files, storage.cache)
    }

    @Test
    fun `cancellation during a rename records its receipt and restores under NonCancellable`() = runTest {
        val storage = Storage("first")
        val renamed = CompletableDeferred<Unit>()
        val finishCache = CompletableDeferred<Unit>()
        storage.beforeCache = { name ->
            if (name == "first-new") {
                renamed.complete(Unit)
                finishCache.await()
            } else {
                assertTrue(currentCoroutineContext().isActive)
                delay(1)
            }
        }
        val sync = async(start = CoroutineStart.UNDISPATCHED) {
            withChapterRenameRollback { receipts ->
                receipts.add(storage.rename("first", "first-new"))
                currentCoroutineContext().ensureActive()
            }
        }
        renamed.await()
        sync.cancel()
        finishCache.complete(Unit)
        sync.join()

        assertTrue(sync.isCancelled)
        assertEquals(setOf("first"), storage.files)
        assertEquals(storage.files, storage.cache)
    }

    private class Storage(vararg names: String) {
        val files = names.toMutableSet()
        val cache = names.toMutableSet()
        val renames = mutableListOf<String>()
        val rejectedNames = mutableSetOf<String>()
        var cacheFailure: Throwable? = null
        var beforeCache: suspend (String) -> Unit = {}

        suspend fun rename(oldName: String, newName: String): ChapterRenameReceipt {
            var currentName = oldName
            return ChapterRenameReceipt.rename(
                oldName = oldName,
                newName = newName,
                exists = files::contains,
                renameTo = { target ->
                    if (target in rejectedNames) {
                        false
                    } else {
                        check(files.remove(currentName))
                        files.add(target)
                        currentName = target
                        renames.add(target)
                        true
                    }
                },
                updateCache = { name ->
                    beforeCache(name)
                    cache.remove(oldName)
                    cache.remove(newName)
                    cache.add(name)
                    val otherName = if (name == oldName) newName else oldName
                    if (otherName in files) cache.add(otherName)
                    cacheFailure?.let { failure ->
                        cacheFailure = null
                        throw failure
                    }
                },
            )
        }
    }
}
