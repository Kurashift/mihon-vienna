package tachiyomi.domain.manga.model

data class MangaProgress(
    val totalChapters: Long,
    val readCount: Long,
    val finishedCount: Long,
    val lastRead: Long,
    /**
     * The most recent "moment it was read" among this work's read chapters, 0 when there is none.
     *
     * Sourced from the chapters' own read timestamps rather than from history, which is refreshed
     * every time the reader is opened. A work whose chapters were all marked unread carries 0.
     */
    val finishedAt: Long = 0L,
    /**
     * When the reader was last opened on any of this work's chapters, 0 if never.
     *
     * Deliberately ungated: unlike [lastRead], which only counts a chapter that was finished or
     * read past its second page, this moves the moment the reader is opened and closed. That is
     * what "last opened" means, and it is what the browse list's 最近打开 ordering reads - a work
     * the reader just dipped into belongs at the top, whether or not they read far enough for
     * [lastRead] to notice.
     */
    val lastOpenedAt: Long = 0L,
) {
    val hasBeenRead: Boolean
        get() = readCount > 0 || lastRead > 0

    val hasFinished: Boolean
        get() = totalChapters > 0 && finishedCount == totalChapters

    companion object {
        val EMPTY = MangaProgress(0, 0, 0, 0)
    }
}

data class MangaProgressByMangaId(
    val mangaId: Long,
    val url: String,
    val progress: MangaProgress,
)
