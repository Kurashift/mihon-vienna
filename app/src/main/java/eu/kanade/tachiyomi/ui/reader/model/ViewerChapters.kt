package eu.kanade.tachiyomi.ui.reader.model

data class ViewerChapters(
    val currChapter: ReaderChapter,
    val prevChapter: ReaderChapter?,
    val nextChapter: ReaderChapter?,
    /**
     * Read chapters skipped between [currChapter] and [nextChapter]. They are present in the
     * chain, so a numbering-based "missing chapters" warning must not count them as missing.
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
