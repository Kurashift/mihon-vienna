package eu.kanade.tachiyomi.ui.browse.source.browse

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DateHeaderBucketTest {

    private val today = LocalDate(2026, 9, 19)

    @Test
    fun `today and the days inside the window each get their own bucket`() {
        assertEquals(
            DateHeaderBucket.Day(LocalDate(2026, 9, 19)),
            dateHeaderBucket(LocalDate(2026, 9, 19), today),
        )
        assertEquals(
            DateHeaderBucket.Day(LocalDate(2026, 9, 16)),
            dateHeaderBucket(LocalDate(2026, 9, 16), today),
        )
    }

    @Test
    fun `the last day inside the window is still its own bucket`() {
        // Six days back is the oldest day that still reads relatively, so it keeps a heading of
        // its own rather than collapsing into the month with the rest.
        assertEquals(
            DateHeaderBucket.Day(LocalDate(2026, 9, 13)),
            dateHeaderBucket(LocalDate(2026, 9, 13), today),
        )
    }

    @Test
    fun `older dates collapse into their month`() {
        assertEquals(
            DateHeaderBucket.Month(LocalDate(2026, 9, 1)),
            dateHeaderBucket(LocalDate(2026, 9, 12), today),
        )
        assertEquals(
            DateHeaderBucket.Month(LocalDate(2026, 8, 1)),
            dateHeaderBucket(LocalDate(2026, 8, 20), today),
        )
    }

    @Test
    fun `every day of an old month lands in the same bucket`() {
        val days = listOf(1, 7, 15, 28).map { LocalDate(2026, 7, it) }

        assertEquals(1, days.map { dateHeaderBucket(it, today) }.distinct().size)
    }

    @Test
    fun `the first of a recent month is a day, not a month`() {
        // The first of September is inside the window, so it must not be mistaken for the month
        // bucket that the older days of September collapse into.
        val recentFirst = LocalDate(2026, 9, 15)
        assertEquals(DateHeaderBucket.Day(recentFirst), dateHeaderBucket(recentFirst, today))
    }

    @Test
    fun `an entry without a timestamp is kept apart from 1970`() {
        assertEquals(DateHeaderBucket.Unknown, dateHeaderBucket(0L, today))
        assertEquals(DateHeaderBucket.Unknown, dateHeaderBucket(-1L, today))
    }

    @Test
    fun `a stamped entry buckets by its date`() {
        val day = LocalDate(2026, 8, 20)
        val millis = day.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

        assertEquals(
            dateHeaderBucket(day, today),
            dateHeaderBucket(millis, today),
        )
    }
}
