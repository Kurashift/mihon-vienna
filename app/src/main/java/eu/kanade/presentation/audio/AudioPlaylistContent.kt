package eu.kanade.presentation.audio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.audio.components.AudioCover
import eu.kanade.presentation.audio.components.AudioFolderRow
import eu.kanade.presentation.audio.components.AudioQueueRow
import eu.kanade.presentation.audio.components.AudioTrackRow
import eu.kanade.presentation.audio.components.buildAudioQueueRows
import eu.kanade.presentation.audio.components.currentWorkKey
import eu.kanade.presentation.audio.components.initialExpandedFolders
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import eu.kanade.tachiyomi.data.audio.AudioPlaylistGroup
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

@Composable
fun AudioPlaylistContent(
    groups: List<AudioPlaylistGroup>,
    currentItem: AudioPlayItem?,
    isPlaying: Boolean,
    bottomBar: @Composable () -> Unit,
    navigateUp: () -> Unit,
    onOpenWork: (AudioPlaylistGroup) -> Unit,
    onClickTrack: (AudioPlaylistGroup, Int) -> Unit,
    onPlayWork: (AudioPlaylistGroup) -> Unit,
    onPlayAll: () -> Unit,
    onClear: () -> Unit,
    onRemoveSelected: (Set<String>) -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    // Selection is keyed by stream url, not by row position, so it survives expand/collapse and
    // maps straight onto the store's bulk removal.
    //
    // Selection mode is tracked separately from the selection itself: unticking the last row must
    // not drop the user out of the mode they are still working in (they may be about to tick
    // something else), so only the close button, the back gesture or emptying the list ends it.
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val allUrls = remember(groups) {
        groups.flatMapTo(mutableSetOf()) { group -> group.tracks.map { it.mediaStreamUrl } }
    }

    fun exitSelection() {
        selectionMode = false
        selected = emptySet()
    }

    fun toggle(urls: Collection<String>) {
        selectionMode = true
        val urlsSet = urls.toSet()
        selected = if (urlsSet.all { it in selected }) selected - urlsSet else selected + urlsSet
    }

    BackHandler(enabled = selectionMode) {
        exitSelection()
    }

    // The list can change under the selection (a track removed elsewhere, the whole playlist
    // cleared). A non-empty check matters here: an empty selection is a normal state while
    // selecting, and `none {}` would read it as "everything went stale" and bail out.
    LaunchedEffect(groups) {
        if (groups.isEmpty()) {
            if (selectionMode) exitSelection()
        } else if (selected.isNotEmpty() && selected.none { it in allUrls }) {
            selected = emptySet()
        }
    }

    // Seeded once from whatever is playing, then handed over to the user: nothing here may pull
    // the expansion back, or a manual collapse would be undone by the next playback event.
    var expandedKeys by remember {
        mutableStateOf(currentWorkKey(groups, currentItem)?.let(::setOf) ?: emptySet())
    }
    var expandedFolders by remember { mutableStateOf(initialExpandedFolders(currentItem)) }

    val playingWorkKey = remember(groups, currentItem) { currentWorkKey(groups, currentItem) }
    val rowsByGroup = remember(groups, expandedFolders) {
        groups.associate { group ->
            group.key to buildAudioQueueRows(group.tracks, expandedFolders)
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            // The overload with an explicit `isActionMode` rather than the `actionModeCounter` one:
            // the counter overload derives the mode from `count > 0`, which would collapse the mode
            // the moment the last row is unticked.
            AppBar(
                titleContent = {
                    if (selectionMode) {
                        AppBarTitle(selected.size.toString())
                    } else {
                        AppBarTitle(stringResource(MR.strings.audio_playlist))
                    }
                },
                navigateUp = navigateUp,
                actions = {
                    if (selectionMode) {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_select_all),
                                    icon = Icons.Outlined.SelectAll,
                                    onClick = {
                                        selectionMode = true
                                        selected = allUrls.toSet()
                                    },
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_select_inverse),
                                    icon = Icons.Outlined.FlipToBack,
                                    onClick = {
                                        selectionMode = true
                                        selected = allUrls - selected
                                    },
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.audio_playlist_remove),
                                    icon = Icons.Outlined.Delete,
                                    enabled = selected.isNotEmpty(),
                                    // A lone track was picked by long press, so the intent is
                                    // already unambiguous; anything larger can hide a whole
                                    // collapsed work.
                                    onClick = {
                                        if (selected.size > 1) {
                                            showRemoveDialog = true
                                        } else {
                                            onRemoveSelected(selected)
                                            exitSelection()
                                        }
                                    },
                                ),
                            ),
                        )
                    } else if (groups.isNotEmpty()) {
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
                isActionMode = selectionMode,
                onCancelActionMode = ::exitSelection,
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
                    val workUrls = group.tracks.map { it.mediaStreamUrl }
                    item(key = "work:${group.key}") {
                        PlaylistWorkRow(
                            group = group,
                            expanded = group.key in expandedKeys,
                            isCurrentWork = group.key == playingWorkKey,
                            selectionState = if (selectionMode) {
                                selectionStateOf(workUrls, selected)
                            } else {
                                null
                            },
                            onToggleExpanded = {
                                expandedKeys = if (group.key in expandedKeys) {
                                    expandedKeys - group.key
                                } else {
                                    expandedKeys + group.key
                                }
                            },
                            onOpenWork = { onOpenWork(group) },
                            onPlay = { onPlayWork(group) },
                            onToggleSelection = { toggle(workUrls) },
                            onLongClick = { toggle(workUrls) },
                        )
                    }
                    if (group.key in expandedKeys) {
                        items(
                            items = rowsByGroup[group.key].orEmpty(),
                            key = { "${group.key}:${it.key}" },
                        ) { row ->
                            when (row) {
                                is AudioQueueRow.Folder -> {
                                    val folderExpanded = row.path in expandedFolders
                                    AudioFolderRow(
                                        title = row.path,
                                        expanded = folderExpanded,
                                        depth = 1,
                                        trackCount = row.trackCount,
                                        onClick = {
                                            expandedFolders = if (folderExpanded) {
                                                expandedFolders - row.path
                                            } else {
                                                expandedFolders + row.path
                                            }
                                        },
                                        selectionState = if (selectionMode) {
                                            selectionStateOf(row.trackUrls, selected)
                                        } else {
                                            null
                                        },
                                        onToggleSelection = { toggle(row.trackUrls) },
                                        onLongClick = { toggle(row.trackUrls) },
                                    )
                                }

                                is AudioQueueRow.Track -> {
                                    val isCurrent =
                                        currentItem?.mediaStreamUrl == row.item.mediaStreamUrl
                                    val url = row.item.mediaStreamUrl
                                    AudioTrackRow(
                                        title = row.item.trackTitle,
                                        number = row.number,
                                        durationMs = row.item.durationMs,
                                        isCurrent = isCurrent,
                                        isPlaying = isCurrent && isPlaying,
                                        depth = 2,
                                        onClick = { onClickTrack(group, row.number - 1) },
                                        selectionState = if (selectionMode) {
                                            ToggleableState(url in selected)
                                        } else {
                                            null
                                        },
                                        onToggleSelection = { toggle(listOf(url)) },
                                        onLongClick = { toggle(listOf(url)) },
                                    )
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
                        exitSelection()
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

    // Only shown when more than one track is ticked: a collapsed work can hide dozens of tracks
    // behind a single checkbox, and the top bar counts ticked rows rather than tracks.
    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(stringResource(MR.strings.audio_playlist_remove)) },
            text = {
                Text(
                    stringResource(
                        MR.strings.audio_playlist_remove_selected_confirm,
                        selected.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveDialog = false
                        onRemoveSelected(selected)
                        exitSelection()
                    },
                ) {
                    Text(stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
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
    isCurrentWork: Boolean,
    selectionState: ToggleableState?,
    onToggleExpanded: () -> Unit,
    onOpenWork: () -> Unit,
    onPlay: () -> Unit,
    onToggleSelection: () -> Unit,
    onLongClick: () -> Unit,
) {
    val inSelectionMode = selectionState != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isCurrentWork) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = { if (inSelectionMode) onToggleSelection() else onToggleExpanded() },
                onLongClick = onLongClick,
            )
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AudioCover(
            coverUrl = group.coverUrl,
            contentDescription = group.workTitle,
            modifier = Modifier
                .size(56.dp)
                // While selecting the row itself toggles, so opening the work from its cover
                // would put a second, conflicting action on the same tap target.
                .clickable(enabled = group.workId > 0 && !inSelectionMode, onClick = onOpenWork),
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
        if (inSelectionMode) {
            // Takes the place of both trailing icons: there is nothing to play or expand while the
            // row's whole job is to be ticked.
            TriStateCheckbox(
                state = selectionState!!,
                onClick = onToggleSelection,
            )
        } else {
            IconButton(onClick = onPlay) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(MR.strings.action_play),
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
}

/** Collapses a set of track urls to the state of the group that owns them. */
private fun selectionStateOf(urls: List<String>, selected: Set<String>): ToggleableState {
    if (urls.isEmpty()) return ToggleableState.Off
    val selectedCount = urls.count { it in selected }
    return when (selectedCount) {
        0 -> ToggleableState.Off
        urls.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}
