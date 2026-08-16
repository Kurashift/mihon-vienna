package tachiyomi.domain.manga.model

data class MangaProgress(
    val totalChapters: Long,
    val readCount: Long,
    val finishedCount: Long,
    val lastRead: Long,
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
    val lastOpenedAt: Long,
)
