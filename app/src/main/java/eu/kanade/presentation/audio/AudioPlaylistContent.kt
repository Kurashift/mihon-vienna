package eu.kanade.presentation.audio

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.audio.components.AudioBottomAction
import eu.kanade.presentation.audio.components.AudioBottomActionMenu
import eu.kanade.presentation.audio.components.AudioCover
import eu.kanade.presentation.audio.components.AudioFolderRow
import eu.kanade.presentation.audio.components.AudioTrackRow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import kotlin.math.roundToInt

/**
 * One work in the queue. Tracks are grouped by folder inside it, so the screen can show a work
 * once even when its tracks came from several folders, and still let each folder be deleted on
 * its own.
 */
data class AudioQueueSection(
    /** Stable work identity; also what remembers whether the work is open. */
    val key: String,
    val workTitle: String,
    val coverUrl: String?,
    val startIndex: Int,
    val tracks: List<AudioPlayItem>,
    val folders: List<AudioQueueFolder>,
)

/** One folder inside a queued work. A blank [path] means tracks that sit at the work root. */
data class AudioQueueFolder(
    val path: String,
    val startIndex: Int,
    val tracks: List<AudioPlayItem>,
)

fun buildQueueSections(tracks: List<AudioPlayItem>): List<AudioQueueSection> {
    if (tracks.isEmpty()) return emptyList()

    // Work order follows the first time a work appears; folder order follows the first time a
    // folder appears inside that work. The store keeps the queue in this shape, so the screen's
    // order is the playback order rather than a second, independent grouping.
    val workOrder = mutableListOf<String>()
    val workTracks = mutableMapOf<String, MutableList<AudioPlayItem>>()
    val workStart = mutableMapOf<String, Int>()
    tracks.forEachIndexed { index, item ->
        val key = item.queueWorkKey()
        if (key !in workTracks) {
            workTracks[key] = mutableListOf()
            workOrder += key
            workStart[key] = index
        }
        workTracks.getValue(key).add(item)
    }

    return workOrder.map { key ->
        val start = workStart.getValue(key)
        val tracksForWork = workTracks.getValue(key)
        val byFolder = linkedMapOf<String, MutableList<AudioPlayItem>>()
        tracksForWork.forEach { item ->
            byFolder.getOrPut(item.folderPath) { mutableListOf() }.add(item)
        }
        var offset = 0
        val folders = byFolder.map { (path, folderTracks) ->
            AudioQueueFolder(
                path = path,
                startIndex = start + offset,
                tracks = folderTracks,
            ).also { offset += folderTracks.size }
        }
        val first = tracksForWork.first()
        AudioQueueSection(
            key = key,
            workTitle = first.workTitle,
            coverUrl = first.coverUrl,
            startIndex = start,
            tracks = folders.flatMap { it.tracks },
            folders = folders,
        )
    }
}

private fun AudioPlayItem.queueWorkKey(): String = "$workId|$workTitle"

internal fun folderStateKey(workKey: String, path: String): String = "$workKey|$path"

@Composable
fun AudioPlaylistContent(
    sections: List<AudioQueueSection>,
    currentItem: AudioPlayItem?,
    isPlaying: Boolean,
    /** Work sections explicitly opened by the user, persisted across visits. */
    expandedSections: Set<String>,
    /** Work sections explicitly closed by the user, persisted across visits. */
    collapsedSections: Set<String>,
    /** Folders inside a work explicitly opened by the user, persisted across visits. */
    expandedFolders: Set<String>,
    /** Folders inside a work explicitly closed by the user, persisted across visits. */
    collapsedFolders: Set<String>,
    bottomBar: @Composable () -> Unit,
    tabRow: @Composable () -> Unit = {},
    navigateUp: () -> Unit,
    onOpenWork: (AudioQueueSection) -> Unit,
    onClickTrack: (Int) -> Unit,
    onPlaySection: (Int) -> Unit,
    onToggleSection: (String, Boolean) -> Unit,
    onToggleFolder: (String, Boolean) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onPlayAll: () -> Unit,
    onClear: () -> Unit,
    onRemoveSelected: (Set<String>) -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    // Selection is keyed by stream url, not by row position, so it survives collapse and maps
    // straight onto the store's bulk removal.
    //
    // Selection mode is tracked separately from the selection itself: unticking the last row must
    // not drop the user out of the mode they are still working in (they may be about to tick
    // something else), so only the close button, the back gesture or emptying the list ends it.
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val allUrls = remember(sections) {
        sections.flatMapTo(mutableSetOf()) { section -> section.tracks.map { it.mediaStreamUrl } }
    }

    // The work and folder that own the current track are the only ones open by default. Anything
    // the user has explicitly opened or closed keeps that state; untouched rows fall back to this.
    val currentSectionKey = remember(sections, currentItem?.mediaStreamUrl) {
        currentItem?.mediaStreamUrl?.let { url ->
            sections.firstOrNull { section -> section.tracks.any { it.mediaStreamUrl == url } }?.key
        }
    }
    val currentFolderKey = remember(sections, currentItem?.mediaStreamUrl) {
        currentItem?.mediaStreamUrl?.let { url ->
            sections.firstNotNullOfOrNull { section ->
                section.folders.firstOrNull { folder ->
                    folder.tracks.any { it.mediaStreamUrl == url }
                }?.let { folder -> folderStateKey(section.key, folder.path) }
            }
        }
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

    // The list can change under the selection (a track removed elsewhere, the whole queue
    // cleared). A non-empty check matters here: an empty selection is a normal state while
    // selecting, and `none {}` would read it as "everything went stale" and bail out.
    LaunchedEffect(sections) {
        if (sections.isEmpty()) {
            if (selectionMode) exitSelection()
        } else if (selected.isNotEmpty() && selected.none { it in allUrls }) {
            selected = emptySet()
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            Column {
                // The overload with an explicit `isActionMode` rather than the `actionModeCounter`
                // one: the counter overload derives the mode from `count > 0`, which would collapse
                // the mode the moment the last row is unticked.
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
                        if (sections.isNotEmpty()) {
                            AppBarActions(
                                if (selectionMode) {
                                    // Playing the queue is not what a selection is for, so play-all
                                    // steps aside. Clearing stays: it acts on the same list the ticks
                                    // are going on, and it sits rightmost, after the two pickers.
                                    listOf(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_select_all),
                                            icon = Icons.Outlined.SelectAll,
                                            onClick = { selected = allUrls.toSet() },
                                        ),
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_select_inverse),
                                            icon = Icons.Outlined.FlipToBack,
                                            onClick = { selected = allUrls - selected },
                                        ),
                                        AppBar.Action(
                                            title = stringResource(MR.strings.audio_playlist_clear),
                                            icon = Icons.Outlined.DeleteSweep,
                                            onClick = { showClearDialog = true },
                                        ),
                                    )
                                } else {
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
                                    )
                                },
                            )
                        }
                    },
                    isActionMode = selectionMode,
                    onCancelActionMode = ::exitSelection,
                    scrollBehavior = scrollBehavior,
                )
                tabRow()
            }
        },
        bottomBar = {
            if (selectionMode) {
                AudioBottomActionMenu(
                    visible = true,
                    actions = listOf(
                        AudioBottomAction(
                            title = stringResource(MR.strings.audio_playlist_remove),
                            icon = Icons.Outlined.Delete,
                            enabled = selected.isNotEmpty(),
                            // A lone track was picked by long press, so the intent is already
                            // unambiguous; anything larger can hide a whole collapsed section.
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
            } else {
                bottomBar()
            }
        },
    ) { contentPadding ->
        if (sections.isEmpty()) {
            EmptyScreen(
                stringRes = MR.strings.audio_playlist_empty,
                modifier = Modifier.padding(contentPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                sections.forEachIndexed { sectionIndex, section ->
                    val sectionUrls = section.tracks.map { it.mediaStreamUrl }
                    val workExpanded = when {
                        section.key in expandedSections -> true
                        section.key in collapsedSections -> false
                        else -> section.key == currentSectionKey
                    }
                    // A work with a single folder needs no folder row: the work header already
                    // carries that path, and deleting the work and deleting its only folder are
                    // the same action. Folder rows appear once a work really has several.
                    val hasFolderRows = section.folders.size > 1

                    item(key = "section:${section.key}") {
                        SwipeToDeleteRow(
                            itemKey = "section:${section.key}",
                            enabled = !selectionMode,
                            onDelete = { onRemoveSelected(sectionUrls.toSet()) },
                        ) {
                            QueueSectionHeader(
                                section = section,
                                collapsed = !workExpanded,
                                isCurrent = section.key == currentSectionKey,
                                selectionState = if (selectionMode) {
                                    selectionStateOf(sectionUrls, selected)
                                } else {
                                    null
                                },
                                onToggleCollapsed = { onToggleSection(section.key, !workExpanded) },
                                onOpenWork = { onOpenWork(section) },
                                onPlaySection = { onPlaySection(section.startIndex) },
                                onToggleSelection = { toggle(sectionUrls) },
                                onLongClick = { toggle(sectionUrls) },
                            )
                        }
                    }

                    if (workExpanded) {
                        section.folders.forEach { folder ->
                            val folderKey = folderStateKey(section.key, folder.path)
                            if (folder.path.isBlank() || !hasFolderRows) {
                                folder.tracks.forEachIndexed { localIndex, item ->
                                    val globalIndex = folder.startIndex + localIndex
                                    item(key = "track:${section.key}:${item.mediaStreamUrl}") {
                                        QueueTrackRow(
                                            item = item,
                                            number = globalIndex + 1,
                                            depth = 1,
                                            isCurrent = currentItem?.mediaStreamUrl == item.mediaStreamUrl,
                                            isPlaying = isPlaying,
                                            selectionState = if (selectionMode) {
                                                ToggleableState(item.mediaStreamUrl in selected)
                                            } else {
                                                null
                                            },
                                            onClick = { onClickTrack(globalIndex) },
                                            onToggleSelection = { toggle(listOf(item.mediaStreamUrl)) },
                                            onLongClick = { toggle(listOf(item.mediaStreamUrl)) },
                                            onDelete = { onRemoveTrack(item.mediaStreamUrl) },
                                        )
                                    }
                                }
                            } else {
                                val folderExpanded = when {
                                    folderKey in expandedFolders -> true
                                    folderKey in collapsedFolders -> false
                                    else -> folderKey == currentFolderKey
                                }
                                val folderUrls = folder.tracks.map { it.mediaStreamUrl }
                                item(key = "folder:${section.key}:${folder.path}") {
                                    SwipeToDeleteRow(
                                        itemKey = "folder:${section.key}:${folder.path}",
                                        enabled = !selectionMode,
                                        onDelete = { onRemoveSelected(folderUrls.toSet()) },
                                    ) {
                                        AudioFolderRow(
                                            title = folder.path,
                                            expanded = folderExpanded,
                                            onClick = { onToggleFolder(folderKey, !folderExpanded) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .then(
                                                    if (folderKey == currentFolderKey && !selectionMode) {
                                                        Modifier.background(
                                                            MaterialTheme.colorScheme.secondaryContainer,
                                                        )
                                                    } else {
                                                        Modifier
                                                    },
                                                ),
                                            depth = 1,
                                            trackCount = folder.tracks.size,
                                            selectionState = if (selectionMode) {
                                                selectionStateOf(folderUrls, selected)
                                            } else {
                                                null
                                            },
                                            onToggleSelection = { toggle(folderUrls) },
                                            onLongClick = { toggle(folderUrls) },
                                        )
                                    }
                                }
                                if (folderExpanded) {
                                    folder.tracks.forEachIndexed { localIndex, item ->
                                        val globalIndex = folder.startIndex + localIndex
                                        item(key = "track:${section.key}:${folder.path}:${item.mediaStreamUrl}") {
                                            QueueTrackRow(
                                                item = item,
                                                number = globalIndex + 1,
                                                depth = 2,
                                                isCurrent = currentItem?.mediaStreamUrl == item.mediaStreamUrl,
                                                isPlaying = isPlaying,
                                                selectionState = if (selectionMode) {
                                                    ToggleableState(item.mediaStreamUrl in selected)
                                                } else {
                                                    null
                                                },
                                                onClick = { onClickTrack(globalIndex) },
                                                onToggleSelection = { toggle(listOf(item.mediaStreamUrl)) },
                                                onLongClick = { toggle(listOf(item.mediaStreamUrl)) },
                                                onDelete = { onRemoveTrack(item.mediaStreamUrl) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (sectionIndex != sections.lastIndex) {
                        item(key = "divider:${section.key}") { HorizontalDivider() }
                    }
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

    // Only shown when more than one track is ticked: a collapsed section can hide dozens of tracks
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
private fun QueueTrackRow(
    item: AudioPlayItem,
    number: Int,
    depth: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    selectionState: ToggleableState?,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    SwipeToDeleteRow(
        itemKey = item.mediaStreamUrl,
        enabled = selectionState == null,
        onDelete = onDelete,
    ) {
        AudioTrackRow(
            title = item.trackTitle,
            number = number,
            durationMs = item.durationMs,
            isCurrent = isCurrent,
            isPlaying = isCurrent && isPlaying,
            depth = depth,
            onClick = onClick,
            selectionState = selectionState,
            onToggleSelection = onToggleSelection,
            onLongClick = onLongClick,
        )
    }
}

@Composable
private fun QueueSectionHeader(
    section: AudioQueueSection,
    collapsed: Boolean,
    isCurrent: Boolean,
    selectionState: ToggleableState?,
    onToggleCollapsed: () -> Unit,
    onOpenWork: () -> Unit,
    onPlaySection: () -> Unit,
    onToggleSelection: () -> Unit,
    onLongClick: () -> Unit,
) {
    val inSelectionMode = selectionState != null
    val namedFolderCount = section.folders.count { it.path.isNotBlank() }
    val subtitle = when {
        section.folders.size == 1 && section.folders[0].path.isNotBlank() -> section.folders[0].path
        namedFolderCount > 1 -> stringResource(
            MR.strings.audio_playlist_folder_count,
            namedFolderCount,
        )
        else -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isCurrent && !inSelectionMode) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = { if (inSelectionMode) onToggleSelection() else onToggleCollapsed() },
                onLongClick = onLongClick,
            )
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inSelectionMode) {
            TriStateCheckbox(
                state = selectionState,
                onClick = onToggleSelection,
            )
        } else {
            AudioCover(
                coverUrl = section.coverUrl,
                contentDescription = section.workTitle,
                modifier = Modifier
                    .size(32.dp)
                    .clickable(onClick = onOpenWork),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(
                text = section.workTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!inSelectionMode) {
            Text(
                text = stringResource(MR.strings.audio_quick_track_count, section.tracks.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onPlaySection) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(MR.strings.action_play),
                )
            }
            Icon(
                imageVector = if (collapsed) {
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight
                } else {
                    Icons.Outlined.KeyboardArrowDown
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** How far a row slides to reveal its delete button: one comfortable icon touch target. */
private val QueueRevealWidth = 72.dp

private enum class QueueSwipe { Closed, Revealed }

/**
 * A row that can be swiped left to reveal a delete button. The row itself stays in charge of taps
 * and long presses; this only adds the horizontal drag and the strip behind it. Used for tracks,
 * folders and whole works, so the gesture reads the same at every level.
 */
@Composable
private fun SwipeToDeleteRow(
    itemKey: String,
    enabled: Boolean,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealPx = remember(density) { with(density) { QueueRevealWidth.toPx() } }
    val currentOnDelete by rememberUpdatedState(onDelete)

    // Swiping reveals the button and stops; the button is what deletes. A row that deleted itself
    // on a fling would be far too easy to lose on a mis-swipe.
    val swipeState = remember(itemKey) { AnchoredDraggableState(initialValue = QueueSwipe.Closed) }
    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = swipeState,
        positionalThreshold = { with(density) { (QueueRevealWidth / 2).toPx() } },
        animationSpec = spring(),
    )
    LaunchedEffect(swipeState, revealPx) {
        swipeState.updateAnchors(
            DraggableAnchors {
                QueueSwipe.Closed at 0f
                QueueSwipe.Revealed at -revealPx
            },
        )
    }
    LaunchedEffect(enabled) {
        if (!enabled) swipeState.animateTo(QueueSwipe.Closed)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) {
        // The strip is measured against the row rather than given a fixed height: the row's height
        // follows its text, and fillMaxHeight inside a lazy list has no bound to fill. The extra
        // clip keeps the strip from peeking through the row's transparent corners at rest.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clipToBounds(),
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(QueueRevealWidth)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    // Slides in with the row: at rest it is pushed past the right edge and clipped,
                    // so it is neither visible nor tappable.
                    .graphicsLayer {
                        translationX = (swipeState.offset.takeIf { it.isFinite() } ?: 0f) + revealPx
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(
                    onClick = {
                        scope.launch { swipeState.animateTo(QueueSwipe.Closed) }
                        currentOnDelete()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(MR.strings.audio_playlist_remove),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset {
                    IntOffset(swipeState.offset.takeIf { it.isFinite() }?.roundToInt() ?: 0, 0)
                }
                // The row content is transparent unless it carries a current/selected fill; without
                // this the red strip shows through the right edge of every ordinary row.
                .background(MaterialTheme.colorScheme.surface)
                .anchoredDraggable(
                    state = swipeState,
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    flingBehavior = flingBehavior,
                ),
        ) {
            content()
        }
    }
}

/** Collapses a set of track urls to the state of the section that owns them. */
private fun selectionStateOf(urls: List<String>, selected: Set<String>): ToggleableState {
    if (urls.isEmpty()) return ToggleableState.Off
    val selectedCount = urls.count { it in selected }
    return when (selectedCount) {
        0 -> ToggleableState.Off
        urls.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}
