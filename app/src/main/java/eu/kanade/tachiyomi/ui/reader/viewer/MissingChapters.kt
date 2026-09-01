package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import tachiyomi.domain.chapter.service.calculateChapterGap as domainCalculateChapterGap

/**
 * Number of chapters skipped between [lowerReaderChapter] and [higherReaderChapter].
 *
 * This is the only place that knows a local source never has missing chapters: its chapters are
 * exactly the files present on disk, so a numbering discontinuity is not a gap in a catalogue.
 * Both the viewer adapters and the transition UI must go through this function.
 */
fun calculateChapterGap(
    higherReaderChapter: ReaderChapter?,
    lowerReaderChapter: ReaderChapter?,
    isLocalSource: Boolean,
): Int {
    if (isLocalSource) return 0
    return domainCalculateChapterGap(
        higherReaderChapter?.chapter?.toDomainChapter(),
        lowerReaderChapter?.chapter?.toDomainChapter(),
    )
}
