package eu.kanade.tachiyomi.ui.reader.model

data class ViewerChapters(
    val currChapter: ReaderChapter,
    val prevChapter: ReaderChapter?,
    val nextChapter: ReaderChapter?,
    /**
     * Chapters jumped over between [currChapter] and [nextChapter] because they were already
     * read. Zero when no skip happened. The transition UI subtracts this from the chapter-number
     * gap so skipped read chapters are not reported as missing chapters.
     */
    val nextSkippedReadCount: Int = 0,
) {

    fun ref() {
        currChapter.ref()
        prevChapter?.ref()
        nextChapter?.ref()
    }

    fun unref() {
        currChapter.unref()
        prevChapter?.unref()
        nextChapter?.unref()
    }
}
