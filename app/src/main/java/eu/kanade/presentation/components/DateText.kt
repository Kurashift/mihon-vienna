package eu.kanade.presentation.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.browse.source.browse.DateHeaderBucket
import eu.kanade.tachiyomi.util.lang.toRelativeString
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import kotlin.time.Instant

@Composable
fun relativeDateText(
    dateEpochMillis: Long,
): String {
    return relativeDateText(
        localDate = Instant.fromEpochMilliseconds(dateEpochMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
            .takeIf { dateEpochMillis != 0L },
    )
}

@Composable
fun relativeDateText(
    localDate: LocalDate?,
): String {
    val context = LocalContext.current

    val preferences = remember { Injekt.get<UiPreferences>() }
    val relativeTime = remember { preferences.relativeTime.get() }
    val dateFormat = remember { UiPreferences.dateFormat(preferences.dateFormat.get()) }

    return localDate?.toRelativeString(
        context = context,
        relative = relativeTime,
        dateFormat = dateFormat,
    )
        ?: stringResource(MR.strings.not_applicable)
}

/**
 * Label for a date separator in a list ordered by date.
 *
 * A day inside the relative window reads as "Today" / "3 days ago", which is what makes a run of
 * freshly added works scannable. A month stands for every older entry in it, so it reads as that
 * month; naming it relatively would produce something like "37 days ago", which says nothing
 * about which entries the separator covers.
 */
@Composable
fun dateHeaderText(bucket: DateHeaderBucket): String {
    return when (bucket) {
        is DateHeaderBucket.Day -> relativeDateText(bucket.date)
        is DateHeaderBucket.Month -> monthText(bucket.date)
        is DateHeaderBucket.Unknown -> stringResource(MR.strings.not_applicable)
    }
}

@Composable
private fun monthText(month: LocalDate): String {
    val context = LocalContext.current
    return remember(month) {
        // Asking the platform for the locale's own year+month layout keeps this on the same
        // convention the rest of the app uses, instead of inventing a second date style.
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMMMM")
        java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            .format(month.toJavaLocalDate())
    }
}
