package eu.kanade.presentation.audio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.tachiyomi.data.audio.AudioHistoryEntry
import eu.kanade.tachiyomi.data.audio.AudioHistoryGroup
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import java.text.DateFormat
import java.util.Date

@Composable
fun AudioHistoryContent(
    groups: List<AudioHistoryGroup>,
    bottomBar: @Composable () -> Unit,
    navigateUp: () -> Unit,
    onClear: () -> Unit,
    onRemoveWork: (AudioHistoryGroup) -> Unit,
    onAddToPlaylist: (AudioHistoryEntry) -> Unit,
    onOpenWork: (AudioHistoryGroup) -> Unit,
    onClickEntry: (AudioHistoryEntry) -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var expandedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.audio_history),
                navigateUp = navigateUp,
                actions = {
                    if (groups.isNotEmpty()) {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.audio_history_clear),
                                    icon = Icons.Outlined.DeleteSweep,
                                    onClick = { showClearDialog = true },
                                ),
                            ),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = bottomBar,
    ) { contentPadding ->
        if (groups.isEmpty()) {
            EmptyScreen(
                stringRes = MR.strings.audio_history_empty,
                modifier = Modifier.padding(contentPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                groups.forEach { group ->
                    item(key = "work:${group.key}") {
                        HistoryWorkRow(
                            group = group,
                            expanded = group.key in expandedKeys,
                            onToggleExpanded = {
                                expandedKeys = if (group.key in expandedKeys) {
                                    expandedKeys - group.key
                                } else {
                                    expandedKeys + group.key
                                }
                            },
                            onOpenWork = { onOpenWork(group) },
                            onContinue = { onClickEntry(group.latest) },
                            onRemove = { onRemoveWork(group) },
                        )
                    }
                    if (group.key in expandedKeys) {
                        group.entries.forEach { entry ->
                            item(key = "history:${group.key}:${entry.item.mediaStreamUrl}") {
                                HistoryTrackRow(
                                    entry = entry,
                                    onClick = { onClickEntry(entry) },
                                    onAddToPlaylist = { onAddToPlaylist(entry) },
                                )
                            }
                        }
                    }
                    item(key = "divider:${group.key}") { HorizontalDivider() }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(MR.strings.audio_history_clear)) },
            text = { Text(stringResource(MR.strings.audio_history_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClear()
                    },
                ) {
                    Text(stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun HistoryWorkRow(
    group: AudioHistoryGroup,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenWork: () -> Unit,
    onContinue: () -> Unit,
    onRemove: () -> Unit,
) {
    val latest = group.latest
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = group.coverUrl,
            contentDescription = group.workTitle,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = group.workId > 0, onClick = onOpenWork),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = group.workTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // The track that was left last and where it was left, so the row says whether there is
            // anything to pick up without being opened first. The position keeps its own width and
            // the title takes what is left, so a long title never pushes the position out of view.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = latest.item.trackTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = " · ${trackProgress(latest)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            HistoryProgress(latest)
        }
        IconButton(onClick = onContinue) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(MR.strings.audio_history_continue))
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(MR.strings.audio_remove_work_from_history),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (expanded) {
                Icons.Outlined.KeyboardArrowDown
            } else {
                Icons.AutoMirrored.Outlined.KeyboardArrowRight
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun HistoryTrackRow(
    entry: AudioHistoryEntry,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 28.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Text(
                text = entry.item.trackTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = historyMeta(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onAddToPlaylist) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                contentDescription = stringResource(MR.strings.audio_add_to_playlist),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun historyMeta(entry: AudioHistoryEntry): String {
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.lastPlayedAt))
    return "${trackProgress(entry)} · $date"
}

/** Where a track was left, as `23:10 / 45:00`, or as the position alone when its length is unknown. */
private fun trackProgress(entry: AudioHistoryEntry): String {
    val position = formatDuration(entry.positionMs)
    val duration = entry.item.durationMs
    return if (duration > 0) "$position / ${formatDuration(duration)}" else position
}

/** How far into the track the position is, for the rows that already say it in words. */
@Composable
private fun HistoryProgress(entry: AudioHistoryEntry) {
    val duration = entry.item.durationMs
    if (duration <= 0) return
    LinearProgressIndicator(
        progress = { (entry.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f) },
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(2.dp),
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}
