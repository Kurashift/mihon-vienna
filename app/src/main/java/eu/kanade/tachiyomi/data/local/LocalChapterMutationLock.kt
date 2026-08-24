package eu.kanade.tachiyomi.data.local

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val LOCAL_CHAPTER_MUTATION_LOCK_COUNT = 64
private val localChapterMutationMutexes = List(LOCAL_CHAPTER_MUTATION_LOCK_COUNT) { Mutex() }

suspend fun <T> withLocalChapterMutationLock(mangaUrl: String, block: suspend () -> T): T {
    val index = (mangaUrl.hashCode() and Int.MAX_VALUE) % localChapterMutationMutexes.size
    return localChapterMutationMutexes[index].withLock { block() }
}
