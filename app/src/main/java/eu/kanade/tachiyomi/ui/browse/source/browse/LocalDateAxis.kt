package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.MarkFilter
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.ReadingFilter
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaProgress

/**
 * What the local library's "date" ordering can order by.
 *
 * The ordering is the reader's own choice, not a consequence of the filters: a date is picked from
 * a menu like any other sort key, and the filters never move it. That is what keeps "good doujins
 * by import date" reachable, and what stops a date from appearing to change on its own when a
 * filter is toggled.
 *
 * The axes that read a per-work timestamp out of [MangaProgress] or a mark map only ever apply to
 * the local browse list, which is derived whole and filtered in the app layer - the source itself
 * has no access to progress or marks, and still serves global search with the import date.
 */
enum class LocalDateAxis {
    /** When the work's row was created: the date it entered the local library. */
    Imported,

    /**
     * The most recent moment the reader was opened on one of its chapters.
     *
     * Named [Opened] rather than "last read" because it is ungated - any visit to the reader
     * counts, however little was read - but it is *shown* as 最近在看, the word the nearby 在看
     * filter already uses. A second word for one concept only makes the reader wonder what the
     * difference is.
     */
    Opened,

    /** The most recent moment one of its read chapters was marked read. */
    Finished,

    /** The most recent moment one of its chapters was flagged. */
    Flagged,

    /** The most recent moment one of its chapters was marked as a good doujin. */
    GoodDoujin,
    ;

    /**
     * Whether this axis reads a per-work timestamp out of the progress map.
     *
     * The caller carries the progress map only for the axes that need it: progress is rewritten on
     * every chapter read, so pinning it into the value the paged list is keyed on would invalidate
     * that list for a change this axis cannot see. The mark axes read the mark maps instead, and
     * the import date reads the stored row.
     */
    val readsProgress: Boolean
        get() = this == Opened || this == Finished

    /**
     * The filter dimension this date belongs to, or null for the import date.
     *
     * This is what keeps one control's change from reaching the other: a date follows a filter
     * change only when its own dimension is what moved. Without it the reader's 好本时间 is stolen
     * the moment they touch the reading filter, and comparing 在读 against 读完 under one mark
     * filter — the reason for switching between them — becomes impossible to do without re-picking
     * the date every time.
     */
    val dimension: DateDimension?
        get() = when (this) {
            LocalDateAxis.Opened, LocalDateAxis.Finished -> DateDimension.Reading
            LocalDateAxis.Flagged, LocalDateAxis.GoodDoujin -> DateDimension.Mark
            LocalDateAxis.Imported -> null
        }
}

/** The filter a date belongs to: the reading filter, or the mark filter. */
enum class DateDimension {
    Reading,
    Mark,
}

/**
 * The dates offered under [readingFilter] and [markFilter], in the order to list them.
 *
 * The list's own subject leads: 在看 offers the opened date, 看完 the finish date, 标记 the flag date
 * and 好本子 the good-doujin date. Filters compose, so 看完 with 好本子 offers both.
 *
 * A filter that names no event offers no event date, on the grounds that a date the list is not
 * about is not what the reader is looking at: 全部 promises nothing about anyone, and 剩余 is
 * defined by what has *not* happened, so neither adds one. This is a deliberate narrowness, not a
 * data guarantee - 剩余 holds works with some chapters read, so a finish time would order most of
 * it by real values. What it avoids is offering a choice that reads as a mistake.
 *
 * 导入日期 is always present and always last. Every work has one, so it is the one date that can
 * never contradict a filter, and it is what keeps a list with no event date of its own sortable by
 * something. It trails because a list narrowed by an event is usually about that event.
 *
 * The two marks stay apart because they are two different marks on two different stores - a work
 * can be flagged without being a good doujin - so one date could not stand for both. Only one mark
 * filter can be active at a time, so the two never appear together.
 */
fun datesAvailableFor(
    readingFilter: ReadingFilter,
    markFilter: MarkFilter,
): List<LocalDateAxis> {
    return buildList {
        when (readingFilter) {
            ReadingFilter.IN_PROGRESS -> add(LocalDateAxis.Opened)
            ReadingFilter.FINISHED -> add(LocalDateAxis.Finished)
            // 全部 promises nothing; 剩余 is defined by what has not happened.
            ReadingFilter.ALL, ReadingFilter.UNREAD -> Unit
        }
        when (markFilter) {
            MarkFilter.FLAGGED -> add(LocalDateAxis.Flagged)
            MarkFilter.GOOD_DOUJIN -> add(LocalDateAxis.GoodDoujin)
            // Neither names a mark, and 未加入书架 names no date of its own either.
            MarkFilter.NONE, MarkFilter.NOT_IN_LIBRARY -> Unit
        }
        // Always last, and always present - see the doc above.
        add(LocalDateAxis.Imported)
    }
}

/**
 * What a filter change should do to the ordering.
 *
 * Modelled as a decision rather than a nullable date because "leave it alone" and "there is no date
 * to use here" are different answers, and only one of them should keep a date ordering.
 */
sealed interface DateFollow {
    /** Keep the ordering exactly as it is. */
    data object Keep : DateFollow

    /** Move to [axis], which the new filters do offer. */
    data class MoveTo(val axis: LocalDateAxis) : DateFollow

    /**
     * The new filters offer no event date at all (全部, 剩余, 未加入书架 with no mark), so the date
     * ordering has nothing left to mean. The caller drops back to the title key rather than to the
     * import date: a reader who was ordering by 最近在看 and lands on 全部 did not ask to see the
     * library in arrival order, and the title is the neutral way to show everything.
     */
    data object DropToTitle : DateFollow
}

/**
 * What a filter change does to the date ordering, decided in one place so no rule can shadow
 * another.
 *
 * A date follows only within its own [LocalDateAxis.dimension]:
 *
 * - The reader on 最近在看 who switches to 看完 is asking about finishes now, so the date follows
 *   them - the reading filter is the control that supplies that date, and it just chose otherwise.
 * - A date whose own filter was not touched stays put, even when the other filter changed what else
 *   is on offer. Looking at 好本时间 and switching 在读 against 读完 is comparing the same marked
 *   works by the same date; moving the date out from under them on every switch is what made that
 *   comparison impossible.
 * - If the date's own dimension no longer offers it (最近在看 chosen, then 全部, where nothing is
 *   promised), there is nothing to follow to and the ordering leaves the date key entirely - see
 *   [DropToTitle]. The reader asked to see everything, so a date ordering is no longer what they
 *   are looking at.
 *
 * The import date belongs to no dimension and therefore never follows. Picking it is the neutral
 * choice ("show me the library as it was built up"), so moving it would undo a deliberate pick on
 * every filter change - and it is what keeps 好本子 + 导入日期 reachable.
 */
fun followedDateAxis(current: LocalDateAxis, available: List<LocalDateAxis>): DateFollow {
    if (current in available) return DateFollow.Keep
    val dimension = current.dimension ?: return DateFollow.Keep
    val replacement = available.firstOrNull { it.dimension == dimension }
        ?: return DateFollow.DropToTitle
    return DateFollow.MoveTo(replacement)
}

/**
 * The timestamp [manga] sorts and groups by under this axis.
 *
 * [markedAt] is the work's most recent mark time of the kind this axis tracks, already resolved by
 * the caller from the mark filter in force; it is only read for the two mark axes, and only one of
 * them can be in use at a time.
 *
 * 0 means "no such event recorded" and is deliberately left as 0 rather than substituted: read as
 * the oldest possible time it sinks to the bottom of a newest-first list, and the date headers
 * already bucket 0 as unknown rather than as 1970. A work with no chapters, or one whose chapters
 * were all marked unread, therefore carries no finish time.
 */
fun LocalDateAxis.value(manga: Manga, progress: MangaProgress, markedAt: Long): Long {
    return when (this) {
        LocalDateAxis.Imported -> manga.dateAdded
        LocalDateAxis.Opened -> progress.lastOpenedAt
        LocalDateAxis.Finished -> progress.finishedAt
        LocalDateAxis.Flagged, LocalDateAxis.GoodDoujin -> markedAt
    }
}

/**
 * Orders by the timestamp [valueOf] reads for each work, in [ascending] direction.
 *
 * Ties fall back to the title order, flipped along with the direction: a run of equal timestamps -
 * and in particular the works with no timestamp at all, which all carry 0 - would otherwise come
 * out in an arbitrary order that reads as noise, and would look identical whichever way the list
 * was ordered. The natural comparator is the same one the source's own orderings use.
 */
fun dateAxisComparator(
    ascending: Boolean,
    valueOf: (Manga) -> Long,
): Comparator<Manga> {
    val byTitle = Comparator<Manga> { a, b ->
        a.title.compareToCaseInsensitiveNaturalOrder(b.title)
    }
    val byValue = Comparator<Manga> { a, b -> valueOf(a).compareTo(valueOf(b)) }
    return if (ascending) {
        byValue.then(byTitle)
    } else {
        byValue.reversed().then(byTitle.reversed())
    }
}
