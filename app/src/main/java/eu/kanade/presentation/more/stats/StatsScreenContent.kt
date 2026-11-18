package eu.kanade.presentation.more.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import eu.kanade.presentation.more.stats.components.StatsItem
import eu.kanade.presentation.more.stats.components.StatsOverviewItem
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.presentation.util.toDurationString
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Composable
fun StatsScreenContent(state: StatsScreenState.Success, paddingValues: PaddingValues) {
    LazyColumn(
        contentPadding = paddingValues,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        item { OverviewSection(state.overview) }
        item { LocalLibrarySection(state.localLibrary) }
        item { LibrarySection(state.library, state.downloads) }
        item { TrackerSection(state.trackers) }
    }
}

private fun Long?.statText(): String = this?.toString() ?: "—"

@Composable
private fun LazyItemScope.OverviewSection(data: StatsData.Overview) {
    val context = LocalContext.current
    val none = stringResource(MR.strings.none)
    val duration = remember(data.totalReadDuration, context, none) {
        data.totalReadDuration
            ?.toDuration(DurationUnit.MILLISECONDS)
            ?.toDurationString(context, fallback = none) ?: "—"
    }
    SectionCard(MR.strings.stats_reading_overview) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            StatsOverviewItem(
                data.readWorkCount.statText(),
                stringResource(MR.strings.stats_read_works),
                Icons.Outlined.LocalLibrary,
            )
            StatsOverviewItem(
                data.completedWorkCount.statText(),
                stringResource(MR.strings.stats_completed_works),
                Icons.Outlined.CollectionsBookmark,
            )
            StatsOverviewItem(
                data.readChapterCount.statText(),
                stringResource(MR.strings.stats_read_chapters),
                Icons.Outlined.DoneAll,
            )
            StatsOverviewItem(
                duration,
                stringResource(MR.strings.stats_total_read_duration),
                Icons.Outlined.Schedule,
            )
        }
    }
}

@Composable
private fun LazyItemScope.LocalLibrarySection(data: StatsData.LocalLibrary) {
    SectionCard(MR.strings.stats_local_library) {
        Row {
            StatsItem(data.workCount.statText(), stringResource(MR.strings.stats_current_works))
            StatsItem(data.chapterCount.statText(), stringResource(MR.strings.stats_current_chapters))
            StatsItem(data.readChapterCount.statText(), stringResource(MR.strings.stats_current_read_chapters))
        }
    }
}

@Composable
private fun LazyItemScope.LibrarySection(data: StatsData.Library, downloads: StatsData.Downloads) {
    SectionCard(MR.strings.stats_library) {
        Row {
            StatsItem(data.workCount.statText(), stringResource(MR.strings.stats_favorite_works))
            StatsItem(data.localWorkCount.statText(), stringResource(MR.strings.stats_local_favorites))
            StatsItem(data.onlineWorkCount.statText(), stringResource(MR.strings.stats_online_favorites))
        }
        HorizontalDivider(Modifier.padding(vertical = MaterialTheme.padding.small))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(MR.strings.stats_online_downloads),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val count = downloads.chapterCount
            Text(
                text = if (count == null) {
                    count.statText()
                } else {
                    pluralStringResource(MR.plurals.stats_downloaded_chapter_count, count.toInt(), count)
                },
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun LazyItemScope.TrackerSection(data: StatsData.Trackers) {
    val notApplicable = stringResource(MR.strings.not_applicable)
    val score = when {
        data.trackedTitleCount == null -> "—"
        data.meanScore?.isFinite() == true -> "%.2f ★".format(Locale.ENGLISH, data.meanScore)
        else -> notApplicable
    }
    SectionCard(MR.strings.label_tracker_section) {
        Row {
            StatsItem(data.trackedTitleCount.statText(), stringResource(MR.strings.label_tracked_titles))
            StatsItem(score, stringResource(MR.strings.label_mean_score))
            StatsItem(data.trackerCount.statText(), stringResource(MR.strings.stats_logged_in_trackers))
        }
    }
}
