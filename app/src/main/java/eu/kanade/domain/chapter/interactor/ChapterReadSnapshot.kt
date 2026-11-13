package eu.kanade.domain.chapter.interactor

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterReadState

/**
 * Captures the read state of these chapters so a read/unread edit can be taken back.
 *
 * Pair it with [readStateCandidates] before the edit, so only the chapters that are about to
 * change are held in memory - on a large local series that is a few fields per chapter, not a
 * second copy of the list.
 */
internal fun List<Chapter>.toReadStates(): List<ChapterReadState> = map { chapter ->
    ChapterReadState(
        chapterId = chapter.id,
        read = chapter.read,
        lastPageRead = chapter.lastPageRead,
        markedReadAt = chapter.markedReadAt,
    )
}
