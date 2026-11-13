package tachiyomi.domain.chapter.model

/**
 * The read state of one chapter, as it stood before an edit.
 *
 * [ChapterUpdate] cannot express a faithful restore: the shared update query forces
 * `last_page_read` to `total_pages` whenever the row is being written as read, so undoing a
 * "mark as unread" would move a half-read chapter's resume point to its last page. This carries
 * the three fields exactly as they were, for the dedicated restore query.
 */
data class ChapterReadState(
    val chapterId: Long,
    val read: Boolean,
    val lastPageRead: Long,
    val markedReadAt: Long,
)
