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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.audio.components.AudioBottomAction
import eu.kanade.presentation.audio.components.AudioBottomActionMenu
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.tachiyomi.data.audio.AudioHistoryEntry
import eu.kanade.tachiyomi.data.audio.AudioHistoryGroup
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import kotlin.math.roundToInt

/** How far the row slides to reveal the delete button: one comfortable icon touch target. */
private val HistoryRevealWidth = 72.dp

private enum class HistorySwipe { Closed, Revealed }

@Composable
fun AudioHistoryContent(
    groups: List<AudioHistoryGroup>,
    /** Work the player is on, so the row it belongs to reads as playing, as it does in the queue. */
    currentWorkId: Long?,
    bottomBar: @Composable () -> Unit,
    tabRow: @Composable () -> Unit = {},
    navigateUp: () -> Unit,
    onClear: () -> Unit,
    onRemoveWork: (AudioHistoryGroup) -> Unit,
    onRemoveSelected: (Set<String>) -> Unit,
    onPlayAll: () -> Unit,
    onOpenWork: (AudioHistoryGroup) -> Unit,
    onClickEntry: (AudioHistoryEntry) -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    // Same selection model as the queue and the rest of the app: long press starts it, the bar at
    // the bottom acts on it, and unticking the last row keeps the mode open.
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val allKeys = remember(groups) { groups.mapTo(mutableSetOf()) { it.key } }

    fun exitSelection() {
        selectionMode = false
        selected = emptySet()
    }

    fun toggle(key: String) {
        selectionMode = true
        selected = if (key in selected) selected - key else selected + key
    }

    BackHandler(enabled = selectionMode) {
        exitSelection()
    }

    // A cleared list, or one the user pruned elsewhere, can leave the selection pointing at rows
    // that are gone. An empty selection is a normal state, so only a non-empty one is checked.
    LaunchedEffect(groups) {
        if (groups.isEmpty()) {
            if (selectionMode) exitSelection()
        } else if (selected.isNotEmpty() && selected.none { it in allKeys }) {
            selected = emptySet()
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            Column {
                AppBar(
                    titleContent = {
                        if (selectionMode) {
                            AppBarTitle(selected.size.toString())
                        } else {
                            AppBarTitle(stringResource(MR.strings.audio_history))
                        }
                    },
                    navigateUp = navigateUp,
                    actions = {
                        if (groups.isNotEmpty()) {
                            AppBarActions(
                                if (selectionMode) {
                                    // Playing everything is not what a selection is for, so play-all
                                    // steps aside. Clearing stays: it acts on the same list the ticks
                                    // are going on, and it sits rightmost, after the two pickers.
                                    listOf(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_select_all),
                                            icon = Icons.Outlined.SelectAll,
                                            onClick = { selected = allKeys.toSet() },
                                        ),
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_select_inverse),
                                            icon = Icons.Outlined.FlipToBack,
                                            onClick = { selected = allKeys - selected },
                                        ),
                                        AppBar.Action(
                                            title = stringResource(MR.strings.audio_history_clear),
                                            icon = Icons.Outlined.DeleteSweep,
                                            onClick = { showClearDialog = true },
                                        ),
                                    )
                                } else {
                                    listOf(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.audio_history_play_all),
                                            icon = Icons.Outlined.PlayArrow,
                                            onClick = onPlayAll,
                                        ),
                                        AppBar.Action(
                                            title = stringResource(MR.strings.audio_history_clear),
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
                            title = stringResource(MR.strings.audio_history_remove),
                            icon = Icons.Outlined.Delete,
                            enabled = selected.isNotEmpty(),
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
                groups.forEachIndexed { index, group ->
                    item(key = "work:${group.key}") {
                        HistoryWorkRow(
                            group = group,
                            selectionMode = selectionMode,
                            selected = group.key in selected,
                            isCurrent = group.workId > 0 && group.workId == currentWorkId,
                            onOpenWork = { onOpenWork(group) },
                            onContinue = { onClickEntry(group.latest) },
                            onToggleSelection = { toggle(group.key) },
                            onLongClick = { toggle(group.key) },
                            onDeleteSwipe = { onRemoveWork(group) },
                        )
                    }
                    if (index != groups.lastIndex) {
                        item(key = "divider:${group.key}") { HorizontalDivider() }
                    }
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

    // Only for more than one row: a single row was picked deliberately, and asking again would be
    // the second confirmation for one decision.
    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(stringResource(MR.strings.audio_history_remove)) },
            text = {
                Text(stringResource(MR.strings.audio_history_remove_selected_confirm, selected.size))
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
private fun HistoryWorkRow(
    group: AudioHistoryGroup,
    selectionMode: Boolean,
    selected: Boolean,
    isCurrent: Boolean,
    onOpenWork: () -> Unit,
    onContinue: () -> Unit,
    onToggleSelection: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteSwipe: () -> Unit,
) {
    val latest = group.latest
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealPx = remember(density) { with(density) { HistoryRevealWidth.toPx() } }
    val currentOnDeleteSwipe by rememberUpdatedState(onDeleteSwipe)

    // Swiping reveals the button and stops; the button is what deletes. A row that deleted itself
    // on a fling would be far too easy to lose on a mis-swipe.
    val swipeState = remember(group.key) { AnchoredDraggableState(initialValue = HistorySwipe.Closed) }
    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = swipeState,
        positionalThreshold = { with(density) { (HistoryRevealWidth / 2).toPx() } },
        animationSpec = spring(),
    )
    LaunchedEffect(swipeState, revealPx) {
        swipeState.updateAnchors(
            DraggableAnchors {
                HistorySwipe.Closed at 0f
                HistorySwipe.Revealed at -revealPx
            },
        )
    }
    LaunchedEffect(selectionMode) {
        if (selectionMode) swipeState.animateTo(HistorySwipe.Closed)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) {
        // The strip is measured against the row rather than given a fixed height: the row's height
        // follows its text, and fillMaxHeight inside a lazy list has no bound to fill.
        Box(modifier = Modifier.matchParentSize()) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(HistoryRevealWidth)
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
                        scope.launch { swipeState.animateTo(HistorySwipe.Closed) }
                        currentOnDeleteSwipe()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(MR.strings.audio_remove_work_from_history),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset {
                    IntOffset(swipeState.offset.takeIf { it.isFinite() }?.roundToInt() ?: 0, 0)
                }
                .anchoredDraggable(
                    state = swipeState,
                    orientation = Orientation.Horizontal,
                    enabled = !selectionMode,
                    flingBehavior = flingBehavior,
                )
                .background(
                    // The queue's "playing now" fill, but not while selecting: then the only thing
                    // a row's fill may say is whether it is ticked.
                    if (selected || (isCurrent && !selectionMode)) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                )
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelection() else onContinue() },
                    onLongClick = onLongClick,
                )
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = null)
            } else {
                AsyncImage(
                    model = group.coverUrl,
                    contentDescription = group.workTitle,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = group.workId > 0, onClick = onOpenWork),
                    contentScale = ContentScale.Crop,
                )
            }
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
                // Only the work and the last track it was left on. Where exactly in that track,
                // and when, is what the player is for; putting it here made the list read as a log.
                Text(
                    text = latest.item.trackTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!selectionMode) {
                IconButton(onClick = onContinue) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = stringResource(MR.strings.audio_history_continue),
                    )
                }
            }
        }
    }
}
