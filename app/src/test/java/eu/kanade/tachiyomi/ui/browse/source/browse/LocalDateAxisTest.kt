package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.MarkFilter
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.ReadingFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaProgress

class LocalDateAxisTest {

    @Test
    fun `the import date is always on offer and always last`() {
        // Every work has one, so it is the one date that can never contradict a filter - and the
        // fallback that keeps a filter with no date of its own sortable by something. It trails
        // because a list narrowed by an event is usually about that event, not about arrival.
        val lists = ReadingFilter.entries.flatMap { reading ->
            MarkFilter.entries.map { mark -> datesAvailableFor(reading, mark) }
        }

        lists.forEach { dates ->
            assertEquals(LocalDateAxis.Imported, dates.last())
            assertEquals(1, dates.count { it == LocalDateAxis.Imported })
        }
    }

    @Test
    fun `a filter that names no event offers no event date`() {
        // A deliberate narrowness, not a data guarantee: 剩余 holds works with some chapters read,
        // so a finish time would order most of it by real values. What this avoids is offering a
        // choice that reads as a mistake.
        assertEquals(
            listOf(LocalDateAxis.Imported),
            datesAvailableFor(ReadingFilter.UNREAD, MarkFilter.NONE),
        )
        // 全部 guarantees nothing either: most of a library was never opened.
        assertEquals(
            listOf(LocalDateAxis.Imported),
            datesAvailableFor(ReadingFilter.ALL, MarkFilter.NONE),
        )
    }

    @Test
    fun `each filter contributes the date it guarantees, leading the import date`() {
        assertEquals(
            listOf(LocalDateAxis.Opened, LocalDateAxis.Imported),
            datesAvailableFor(ReadingFilter.IN_PROGRESS, MarkFilter.NONE),
        )
        assertEquals(
            listOf(LocalDateAxis.Finished, LocalDateAxis.Imported),
            datesAvailableFor(ReadingFilter.FINISHED, MarkFilter.NONE),
        )
        assertEquals(
            listOf(LocalDateAxis.Flagged, LocalDateAxis.Imported),
            datesAvailableFor(ReadingFilter.ALL, MarkFilter.FLAGGED),
        )
        assertEquals(
            listOf(LocalDateAxis.GoodDoujin, LocalDateAxis.Imported),
            datesAvailableFor(ReadingFilter.ALL, MarkFilter.GOOD_DOUJIN),
        )
    }

    @Test
    fun `filters compose so their dates add up`() {
        // 读完 with 好本子: the list is both finished and marked, so both dates are real.
        assertEquals(
            listOf(LocalDateAxis.Finished, LocalDateAxis.GoodDoujin, LocalDateAxis.Imported),
            datesAvailableFor(ReadingFilter.FINISHED, MarkFilter.GOOD_DOUJIN),
        )
        assertEquals(
            listOf(LocalDateAxis.Opened, LocalDateAxis.Flagged, LocalDateAxis.Imported),
            datesAvailableFor(ReadingFilter.IN_PROGRESS, MarkFilter.FLAGGED),
        )
    }

    @Test
    fun `the two marks are separate dates`() {
        // 标记 and 好本子 are two different marks on two different stores: a work can be flagged
        // without being a good doujin. One date could not stand for both - a good-doujin list
        // ordered by flag times would read a store that list was never filtered against.
        assertEquals(
            listOf(LocalDateAxis.Flagged, LocalDateAxis.Imported),
            datesAvailableFor(ReadingFilter.ALL, MarkFilter.FLAGGED),
        )
        assertEquals(
            listOf(LocalDateAxis.GoodDoujin, LocalDateAxis.Imported),
            datesAvailableFor(ReadingFilter.ALL, MarkFilter.GOOD_DOUJIN),
        )
    }

    @Test
    fun `not on a shelf contributes no date of its own`() {
        // There is no shelf date on this screen, so it adds nothing beyond the import date.
        assertEquals(
            listOf(LocalDateAxis.Imported),
            datesAvailableFor(ReadingFilter.ALL, MarkFilter.NOT_IN_LIBRARY),
        )
        // It still composes with a reading filter that does have one.
        assertEquals(
            listOf(LocalDateAxis.Finished, LocalDateAxis.Imported),
            datesAvailableFor(ReadingFilter.FINISHED, MarkFilter.NOT_IN_LIBRARY),
        )
    }

    @Test
    fun `a date follows within its own dimension`() {
        // The reader on 最近在看 who switches to 看完 is asking about finishes now.
        assertEquals(
            DateFollow.MoveTo(LocalDateAxis.Finished),
            followedDateAxis(
                LocalDateAxis.Opened,
                datesAvailableFor(ReadingFilter.FINISHED, MarkFilter.NONE),
            ),
        )
        // And the same across the mark dimension.
        assertEquals(
            DateFollow.MoveTo(LocalDateAxis.GoodDoujin),
            followedDateAxis(
                LocalDateAxis.Flagged,
                datesAvailableFor(ReadingFilter.ALL, MarkFilter.GOOD_DOUJIN),
            ),
        )
    }

    @Test
    fun `a date whose own filter was not touched stays put`() {
        // 好本时间 chosen, then the reading filter changes. The mark filter is what supplies that
        // date and it did not move, so neither does the date. This is the case that makes 在读
        // against 读完 comparable under one mark filter: switching between them re-filters the
        // list without re-picking the date, so the two views can be read against each other.
        assertEquals(
            DateFollow.Keep,
            followedDateAxis(
                LocalDateAxis.GoodDoujin,
                datesAvailableFor(ReadingFilter.FINISHED, MarkFilter.GOOD_DOUJIN),
            ),
        )
        assertEquals(
            DateFollow.Keep,
            followedDateAxis(
                LocalDateAxis.GoodDoujin,
                datesAvailableFor(ReadingFilter.IN_PROGRESS, MarkFilter.GOOD_DOUJIN),
            ),
        )
        // Symmetrically: a reading date survives a change of mark filter.
        assertEquals(
            DateFollow.Keep,
            followedDateAxis(
                LocalDateAxis.Opened,
                datesAvailableFor(ReadingFilter.IN_PROGRESS, MarkFilter.FLAGGED),
            ),
        )
    }

    @Test
    fun `a date whose own dimension drops it follows or leaves the key`() {
        // 最近在看 chosen, then 标记: the reading filter no longer supplies that date, but it still
        // supplies none at all, so there is nothing in the dimension to follow to.
        assertEquals(
            DateFollow.DropToTitle,
            followedDateAxis(
                LocalDateAxis.Opened,
                datesAvailableFor(ReadingFilter.ALL, MarkFilter.FLAGGED),
            ),
        )
        // 看完 chosen, then 在读: the reading dimension still has a date, so it follows to it.
        assertEquals(
            DateFollow.MoveTo(LocalDateAxis.Opened),
            followedDateAxis(
                LocalDateAxis.Finished,
                datesAvailableFor(ReadingFilter.IN_PROGRESS, MarkFilter.NONE),
            ),
        )
    }

    @Test
    fun `the import date never follows`() {
        // Picking it is the neutral choice - "show me the library as it was built up" - so a filter
        // change must not undo it. It is also what keeps 好本子 + 导入日期 reachable.
        ReadingFilter.entries.forEach { reading ->
            MarkFilter.entries.forEach { mark ->
                assertEquals(
                    DateFollow.Keep,
                    followedDateAxis(LocalDateAxis.Imported, datesAvailableFor(reading, mark)),
                    "import date must not follow $reading/$mark",
                )
            }
        }
    }

    @Test
    fun `the two filters own different dimensions`() {
        assertEquals(DateDimension.Reading, LocalDateAxis.Opened.dimension)
        assertEquals(DateDimension.Reading, LocalDateAxis.Finished.dimension)
        assertEquals(DateDimension.Mark, LocalDateAxis.Flagged.dimension)
        assertEquals(DateDimension.Mark, LocalDateAxis.GoodDoujin.dimension)
        assertEquals(null, LocalDateAxis.Imported.dimension)
    }

    @Test
    fun `only the progress dates read the progress map`() {
        // The caller carries the progress map for exactly these two; pinning it into the value the
        // paged list is keyed on would invalidate that list on every chapter read.
        assertEquals(true, LocalDateAxis.Opened.readsProgress)
        assertEquals(true, LocalDateAxis.Finished.readsProgress)
        assertEquals(false, LocalDateAxis.Imported.readsProgress)
        assertEquals(false, LocalDateAxis.Flagged.readsProgress)
        assertEquals(false, LocalDateAxis.GoodDoujin.readsProgress)
    }

    @Test
    fun `each date reads its own field`() {
        val manga = manga(title = "A", dateAdded = 100L)
        val progress = MangaProgress(
            totalChapters = 3,
            readCount = 3,
            finishedCount = 3,
            lastRead = 200L,
            finishedAt = 300L,
            lastOpenedAt = 250L,
        )

        assertEquals(100L, LocalDateAxis.Imported.value(manga, progress, markedAt = 400L))
        // The opened date reads the ungated open time, not the gated read time: a work the reader
        // dipped into and left is still one they just opened.
        assertEquals(250L, LocalDateAxis.Opened.value(manga, progress, markedAt = 400L))
        assertEquals(300L, LocalDateAxis.Finished.value(manga, progress, markedAt = 400L))
        assertEquals(400L, LocalDateAxis.Flagged.value(manga, progress, markedAt = 400L))
        assertEquals(400L, LocalDateAxis.GoodDoujin.value(manga, progress, markedAt = 400L))
    }

    @Test
    fun `a date with no recorded event reads zero rather than a substitute`() {
        // Zero has to stay zero: the comparator sinks it to the bottom of a newest-first list and
        // the header buckets it as unknown. Substituting epoch or now would place it wrongly.
        val untouched = MangaProgress.EMPTY

        assertEquals(0L, LocalDateAxis.Opened.value(manga(), untouched, markedAt = 0L))
        assertEquals(0L, LocalDateAxis.Finished.value(manga(), untouched, markedAt = 0L))
        assertEquals(0L, LocalDateAxis.Flagged.value(manga(), untouched, markedAt = 0L))
        assertEquals(0L, LocalDateAxis.GoodDoujin.value(manga(), untouched, markedAt = 0L))
    }

    @Test
    fun `descending puts the newest first and the undated last`() {
        val entries = listOf(
            manga(title = "old", dateAdded = 10L),
            manga(title = "none", dateAdded = 0L),
            manga(title = "new", dateAdded = 30L),
        )

        val ordered = entries.sortedWith(
            dateAxisComparator(ascending = false) { it.dateAdded },
        )

        assertEquals(listOf("new", "old", "none"), ordered.map(Manga::title))
    }

    @Test
    fun `ascending puts the undated first`() {
        val entries = listOf(
            manga(title = "old", dateAdded = 10L),
            manga(title = "none", dateAdded = 0L),
            manga(title = "new", dateAdded = 30L),
        )

        val ordered = entries.sortedWith(
            dateAxisComparator(ascending = true) { it.dateAdded },
        )

        assertEquals(listOf("none", "old", "new"), ordered.map(Manga::title))
    }

    @Test
    fun `equal timestamps fall back to the title in the same direction`() {
        // A run of works with no timestamp all carry 0. Without a flipped tiebreaker the run would
        // read the same whichever way the list is ordered, which looks like the sort did nothing.
        val entries = listOf(
            manga(title = "B", dateAdded = 0L),
            manga(title = "A", dateAdded = 0L),
            manga(title = "C", dateAdded = 0L),
        )

        assertEquals(
            listOf("A", "B", "C"),
            entries.sortedWith(dateAxisComparator(ascending = true) { it.dateAdded }).map(Manga::title),
        )
        assertEquals(
            listOf("C", "B", "A"),
            entries.sortedWith(dateAxisComparator(ascending = false) { it.dateAdded }).map(Manga::title),
        )
    }

    private fun manga(title: String = "title", dateAdded: Long = 0L): Manga = Manga.create().copy(
        title = title,
        dateAdded = dateAdded,
    )
}
