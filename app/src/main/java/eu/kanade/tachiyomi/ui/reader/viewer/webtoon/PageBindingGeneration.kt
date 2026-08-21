package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

internal class PageBindingGeneration {
    private var current = 0L

    fun next(): Long = ++current

    fun invalidate() {
        current++
    }

    fun isCurrent(generation: Long): Boolean = generation == current
}
