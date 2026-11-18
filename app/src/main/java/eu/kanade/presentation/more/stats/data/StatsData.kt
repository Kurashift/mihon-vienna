package eu.kanade.presentation.more.stats.data

sealed interface StatsData {
    data class Overview(
        val readWorkCount: Long? = null,
        val completedWorkCount: Long? = null,
        val readChapterCount: Long? = null,
        val totalReadDuration: Long? = null,
    ) : StatsData

    data class LocalLibrary(
        val workCount: Long? = null,
        val chapterCount: Long? = null,
        val readChapterCount: Long? = null,
    ) : StatsData

    data class Library(
        val workCount: Long? = null,
        val localWorkCount: Long? = null,
        val onlineWorkCount: Long? = null,
    ) : StatsData

    data class Downloads(val chapterCount: Long? = null) : StatsData

    data class Trackers(
        val trackedTitleCount: Long? = null,
        val meanScore: Double? = null,
        val trackerCount: Long? = null,
    ) : StatsData
}
