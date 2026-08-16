package eu.kanade.tachiyomi.ui.reader

import java.util.concurrent.ConcurrentHashMap

internal class ReaderProgressSession {

    private val settledPages = ConcurrentHashMap<Long, Int>()

    fun recordInitial(chapterId: Long, pageIndex: Int) {
        settledPages.putIfAbsent(chapterId, pageIndex)
    }

    fun recordSettled(chapterId: Long, pageIndex: Int) {
        settledPages[chapterId] = pageIndex
    }

    fun beginEntry(chapterId: Long) {
        settledPages.remove(chapterId)
    }

    fun getSettled(chapterId: Long): Int? = settledPages[chapterId]

    fun getForExit(chapterId: Long): Int? = settledPages[chapterId]
}
