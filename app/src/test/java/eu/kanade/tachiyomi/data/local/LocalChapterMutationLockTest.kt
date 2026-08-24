package eu.kanade.tachiyomi.data.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalChapterMutationLockTest {

    @Test
    fun `mutations for the same manga are serialized`() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = launch {
            withLocalChapterMutationLock("manga-a") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            withLocalChapterMutationLock("manga-a") {
                secondEntered.complete(Unit)
            }
        }

        runCurrent()
        assertFalse(secondEntered.isCompleted)

        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered.isCompleted)
    }

    @Test
    fun `mutations for different mangas can proceed independently`() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = launch {
            withLocalChapterMutationLock("manga-a") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            withLocalChapterMutationLock("manga-b") {
                secondEntered.complete(Unit)
            }
        }

        runCurrent()
        assertTrue(secondEntered.isCompleted)

        releaseFirst.complete(Unit)
        first.join()
        second.join()
    }
}
