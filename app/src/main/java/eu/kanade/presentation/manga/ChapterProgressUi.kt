package eu.kanade.presentation.manga

import tachiyomi.domain.chapter.model.Chapter

internal data class ChapterProgressUi(
    val readPages: Long,
    val totalPages: Long,
) {
    val fraction: Float
        get() = (readPages.toFloat() / totalPages).coerceIn(0f, 1f)
}

/**
 * Converts persisted chapter state into progress that is safe to present.
 *
 * A total page count of zero means the denominator is unknown, so no determinate reading
 * progress is shown. The final page is reserved for chapters explicitly marked as read; stale
 * unread progress must never make a chapter look complete.
 */
internal fun Chapter.toChapterProgressUi(): ChapterProgressUi? {
    if (totalPages <= 0L) return null

    val displayedReadPages = if (read) {
        totalPages
    } else {
        lastPageRead.coerceIn(0L, (totalPages - 1L).coerceAtLeast(0L))
    }
    return ChapterProgressUi(
        readPages = displayedReadPages,
        totalPages = totalPages,
    )
}
