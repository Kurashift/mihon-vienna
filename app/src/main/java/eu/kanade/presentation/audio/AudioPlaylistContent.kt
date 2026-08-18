package eu.kanade.presentation.audio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import eu.kanade.tachiyomi.data.audio.AudioPlaylistGroup
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

@Composable
fun AudioPlaylistContent(
    groups: List<AudioPlaylistGroup>,
    bottomBar: @Composable () -> Unit,
    navigateUp: () -> Unit,
    onOpenWork: (AudioPlaylistGroup) -> Unit,
    onClickTrack: (AudioPlaylistGroup, Int) -> Unit,
    onPlayWork: (AudioPlaylistGroup) -> Unit,
    onRemoveTrack: (AudioPlayItem) -> Unit,
    onRemoveWork: (AudioPlaylistGroup) -> Unit,
    onPlayAll: () -> Unit,
    onClear: () -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var expandedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedFolders by remember { mutableStateOf<Set<String>>(emptySet()) }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.audio_playlist),
                navigateUp = navigateUp,
                actions = {
                    if (groups.isNotEmpty()) {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.audio_playlist_play_all),
                                    icon = Icons.Outlined.PlayArrow,
                                    onClick = onPlayAll,
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.audio_playlist_clear),
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
                stringRes = MR.strings.audio_playlist_empty,
                modifier = Modifier.padding(contentPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                groups.forEach { group ->
                    item(key = "work:${group.key}") {
                        PlaylistWorkRow(
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
                            onPlay = { onPlayWork(group) },
                            onRemove = { onRemoveWork(group) },
                        )
                    }
                    if (group.key in expandedKeys) {
                        group.tracks
                            .groupBy { it.folderPath }
                            .toList()
                            .forEach { (folderPath, folderTracks) ->
                                if (folderPath.isNotBlank()) {
                                    val expanded = folderPath in expandedFolders
                                    item(key = "folder:${group.key}:$folderPath") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    expandedFolders = if (expanded) {
                                                        expandedFolders - folderPath
                                                    } else {
                                                        expandedFolders + folderPath
                                                    }
                                                }
                                                .padding(start = 28.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                imageVector = if (expanded) {
                                                    Icons.Outlined.KeyboardArrowDown
                                                } else {
                                                    Icons.AutoMirrored.Outlined.KeyboardArrowRight
                                                },
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Icon(
                                                imageVector = Icons.Outlined.Folder,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Text(
                                                text = folderPath,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(start = 6.dp),
                                            )
                                        }
                                    }
                                    if (expanded) {
                                        folderTracks.forEachIndexed { index, item ->
                                            item(key = "track:${group.key}:${item.mediaStreamUrl}") {
                                                PlaylistTrackRow(
                                                    item = item,
                                                    onClick = { onClickTrack(group, group.tracks.indexOfFirst { it.mediaStreamUrl == item.mediaStreamUrl }) },
                                                    onRemove = { onRemoveTrack(item) },
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    folderTracks.forEachIndexed { index, item ->
                                        item(key = "track:${group.key}:${item.mediaStreamUrl}") {
                                            PlaylistTrackRow(
                                                item = item,
                                                onClick = { onClickTrack(group, group.tracks.indexOfFirst { it.mediaStreamUrl == item.mediaStreamUrl }) },
                                                onRemove = { onRemoveTrack(item) },
                                            )
                                        }
                                    }
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
            title = { Text(stringResource(MR.strings.audio_playlist_clear)) },
            text = { Text(stringResource(MR.strings.audio_playlist_clear_confirm)) },
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
private fun PlaylistWorkRow(
    group: AudioPlaylistGroup,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenWork: () -> Unit,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
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
            Text(
                text = stringResource(MR.strings.audio_playlist_work_tracks, group.tracks.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPlay) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(MR.strings.action_play))
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Outlined.RemoveCircleOutline,
                contentDescription = stringResource(MR.strings.audio_playlist_remove_work),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun PlaylistTrackRow(
    item: AudioPlayItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
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
        Text(
            text = item.trackTitle,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Outlined.RemoveCircleOutline,
                contentDescription = stringResource(MR.strings.audio_playlist_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
