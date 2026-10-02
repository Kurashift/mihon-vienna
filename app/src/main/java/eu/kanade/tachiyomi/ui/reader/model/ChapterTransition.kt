package eu.kanade.tachiyomi.ui.reader.model

sealed class ChapterTransition {

    abstract val from: ReaderChapter
    abstract val to: ReaderChapter?

    class Prev(
        override val from: ReaderChapter,
        override val to: ReaderChapter?,
    ) : ChapterTransition()

    class Next(
        override val from: ReaderChapter,
        override val to: ReaderChapter?,
        /**
         * Chapters jumped over between [from] and [to] because they were already read. The
         * transition UI subtracts this from the chapter-number gap so skipped read chapters are
         * not reported as missing chapters.
         */
        val skippedReadCount: Int = 0,
    ) : ChapterTransition()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChapterTransition) return false
        // Must be the same subclass; Prev and Next are never equal even with matching chapters.
        if (other.javaClass != javaClass) return false
        if (this !is Next) return from == other.from && to == other.to
        // Same-subclass check above means other is a Next too.
        other as Next
        return from == other.from && to == other.to && skippedReadCount == other.skippedReadCount
    }

    override fun hashCode(): Int {
        var result = javaClass.hashCode()
        result = 31 * result + from.hashCode()
        result = 31 * result + (to?.hashCode() ?: 0)
        if (this is Next) result = 31 * result + skippedReadCount
        return result
    }

    override fun toString(): String {
        return "${javaClass.simpleName}(from=${from.chapter.url}, to=${to?.chapter?.url})"
    }
}
