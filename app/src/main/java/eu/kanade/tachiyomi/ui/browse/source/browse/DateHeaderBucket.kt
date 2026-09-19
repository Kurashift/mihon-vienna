package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/**
 * How many days back the date ordering still separates day by day. Older entries are grouped by
 * month instead: a large library spreads over years, and one separator per day turns the list
 * into mostly headings.
 *
 * Deliberately the same window [eu.kanade.tachiyomi.util.lang.toRelativeString] names relatively,
 * so a day that earns its own heading is also a day that reads as "Today" or "3 days ago" instead
 * of a bare date.
 */
const val DATE_HEADER_RELATIVE_DAYS = 7

/**
 * The span of entries a date separator stands for.
 *
 * The kind travels with the date instead of being inferred from it later: a real date that happens
 * to be the first of a month is a [Day], and only [Month] carries a whole month, so the label and
 * the grouping can never disagree.
 */
sealed interface DateHeaderBucket {
    /** One day, recent enough to be named relatively. */
    data class Day(val date: LocalDate) : DateHeaderBucket

    /** Every older entry in the month of [date]; [date] is that month's first day. */
    data class Month(val date: LocalDate) : DateHeaderBucket

    /**
     * An entry the listing could not date. Kept apart rather than folded into 1970, which is what
     * treating a missing timestamp as epoch would show.
     */
    data object Unknown : DateHeaderBucket
}

/**
 * The bucket [date] belongs to under the date ordering, given the current [today].
 *
 * A date inside the relative window is its own bucket, so consecutive days stay apart and read
 * relatively. An older date collapses into its month, which keeps the separators few.
 */
fun dateHeaderBucket(date: LocalDate, today: LocalDate): DateHeaderBucket {
    return if (date.daysUntil(today) >= DATE_HEADER_RELATIVE_DAYS) {
        DateHeaderBucket.Month(LocalDate(date.year, date.month, 1))
    } else {
        DateHeaderBucket.Day(date)
    }
}

/** The bucket for an entry stamped [epochMillis], or [DateHeaderBucket.Unknown] when it has none. */
fun dateHeaderBucket(epochMillis: Long, today: LocalDate): DateHeaderBucket {
    if (epochMillis <= 0L) return DateHeaderBucket.Unknown
    return dateHeaderBucket(epochMillis.toLocalDate(), today)
}
