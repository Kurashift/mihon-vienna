package eu.kanade.presentation.manga.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.SwapCalls
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DownloadDropdownMenu
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Duration.Companion.seconds

@Composable
fun MangaBottomActionMenu(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onBookmarkClicked: (() -> Unit)? = null,
    onRemoveBookmarkClicked: (() -> Unit)? = null,
    onAddToGoodDoujinClicked: (() -> Unit)? = null,
    onRemoveFromGoodDoujinClicked: (() -> Unit)? = null,
    onMarkAsReadClicked: (() -> Unit)? = null,
    onMarkAsUnreadClicked: (() -> Unit)? = null,
    onMarkPreviousAsReadClicked: (() -> Unit)? = null,
    onMarkFollowingAsReadClicked: (() -> Unit)? = null,
    onDownloadClicked: (() -> Unit)? = null,
    onDeleteClicked: (() -> Unit)? = null,
    onEditTranslatedTitleClicked: (() -> Unit)? = null,
    onToggleMarkClicked: (() -> Unit)? = null,
    onMoveClicked: (() -> Unit)? = null,
    marksSelected: Boolean = false,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Bottom),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        val scope = rememberCoroutineScope()
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.large.copy(bottomEnd = ZeroCornerSize, bottomStart = ZeroCornerSize),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            val haptic = LocalHapticFeedback.current
            val confirm = remember {
                mutableStateListOf(false, false, false, false, false, false, false, false, false, false, false)
            }
            var resetJob by remember { mutableStateOf<Job?>(null) }
            val onLongClickItem: (Int) -> Unit = { toConfirmIndex ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                confirm.indices.forEach { i -> confirm[i] = i == toConfirmIndex }
                resetJob?.cancel()
                resetJob = scope.launch {
                    delay(1.seconds)
                    if (isActive) confirm[toConfirmIndex] = false
                }
            }
            Row(
                modifier = Modifier
                    .padding(
                        WindowInsets.navigationBars
                            .only(WindowInsetsSides.Bottom)
                            .asPaddingValues(),
                    )
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                if (onBookmarkClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_bookmark),
                        icon = Icons.Outlined.BookmarkAdd,
                        toConfirm = confirm[0],
                        onLongClick = { onLongClickItem(0) },
                        onClick = onBookmarkClicked,
                    )
                }
                if (onRemoveBookmarkClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_remove_bookmark),
                        icon = Icons.Outlined.BookmarkRemove,
                        toConfirm = confirm[1],
                        onLongClick = { onLongClickItem(1) },
                        onClick = onRemoveBookmarkClicked,
                    )
                }
                val hasGoodDoujinAction = onAddToGoodDoujinClicked != null || onRemoveFromGoodDoujinClicked != null
                if (hasGoodDoujinAction || onToggleMarkClicked != null) {
                    var marksExpanded by remember { mutableStateOf(false) }
                    Button(
                        title = stringResource(MR.strings.action_mark_group),
                        icon = Icons.AutoMirrored.Outlined.Label,
                        toConfirm = false,
                        onLongClick = {},
                        onClick = { marksExpanded = true },
                    ) {
                        DropdownMenu(
                            expanded = marksExpanded,
                            onDismissRequest = { marksExpanded = false },
                            offset = BottomBarMenuDpOffset,
                        ) {
                            if (onAddToGoodDoujinClicked != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(MR.strings.action_add_to_good_doujin)) },
                                    leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, contentDescription = null) },
                                    onClick = {
                                        marksExpanded = false
                                        onAddToGoodDoujinClicked()
                                    },
                                )
                            }
                            if (onRemoveFromGoodDoujinClicked != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(MR.strings.action_remove_from_good_doujin)) },
                                    leadingIcon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
                                    onClick = {
                                        marksExpanded = false
                                        onRemoveFromGoodDoujinClicked()
                                    },
                                )
                            }
                            if (onToggleMarkClicked != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (marksSelected) {
                                                    MR.strings.action_unmark_duplicate
                                                } else {
                                                    MR.strings.action_mark_duplicate
                                                },
                                            ),
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (marksSelected) Icons.Filled.Flag else Icons.Outlined.Flag,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        marksExpanded = false
                                        onToggleMarkClicked()
                                    },
                                )
                            }
                        }
                    }
                }
                if (onMarkAsReadClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_mark_as_read),
                        icon = Icons.Outlined.DoneAll,
                        toConfirm = confirm[4],
                        onLongClick = { onLongClickItem(4) },
                        onClick = onMarkAsReadClicked,
                    )
                }
                if (onMarkAsUnreadClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_mark_as_unread),
                        icon = Icons.Outlined.RemoveDone,
                        toConfirm = confirm[5],
                        onLongClick = { onLongClickItem(5) },
                        onClick = onMarkAsUnreadClicked,
                    )
                }
                if (onDownloadClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_download),
                        icon = Icons.Outlined.Download,
                        toConfirm = confirm[7],
                        onLongClick = { onLongClickItem(7) },
                        onClick = onDownloadClicked,
                    )
                }
                if (onEditTranslatedTitleClicked != null) {
                    Button(
                        title = stringResource(MR.strings.edit_chapter_translated_title),
                        icon = Icons.Outlined.Edit,
                        toConfirm = confirm[10],
                        onLongClick = { onLongClickItem(10) },
                        onClick = onEditTranslatedTitleClicked,
                    )
                }
                if (onMoveClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_move_to),
                        icon = Icons.Outlined.DriveFileMove,
                        toConfirm = false,
                        onLongClick = {},
                        onClick = onMoveClicked,
                    )
                }
                if (onDeleteClicked != null || onMarkPreviousAsReadClicked != null ||
                    onMarkFollowingAsReadClicked != null
                ) {
                    var moreExpanded by remember { mutableStateOf(false) }
                    Button(
                        title = stringResource(MR.strings.label_more),
                        icon = Icons.Outlined.MoreVert,
                        toConfirm = false,
                        onLongClick = {},
                        onClick = { moreExpanded = true },
                    ) {
                        DropdownMenu(
                            expanded = moreExpanded,
                            onDismissRequest = { moreExpanded = false },
                            offset = BottomBarMenuDpOffset,
                        ) {
                            if (onMarkPreviousAsReadClicked != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(MR.strings.action_mark_previous_as_read)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = ImageVector.vectorResource(R.drawable.ic_done_prev_24dp),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        moreExpanded = false
                                        onMarkPreviousAsReadClicked()
                                    },
                                )
                            }
                            if (onMarkFollowingAsReadClicked != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(MR.strings.action_mark_following_as_read)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = ImageVector.vectorResource(R.drawable.ic_done_next_24dp),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        moreExpanded = false
                                        onMarkFollowingAsReadClicked()
                                    },
                                )
                            }
                            if (onDeleteClicked != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(MR.strings.action_delete)) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                    onClick = {
                                        moreExpanded = false
                                        onDeleteClicked()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.Button(
    title: String,
    icon: ImageVector,
    toConfirm: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    val animatedWeight by animateFloatAsState(
        targetValue = if (toConfirm) 2f else 1f,
        label = "weight",
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .weight(animatedWeight)
            .combinedClickable(
                interactionSource = null,
                indication = ripple(bounded = false),
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
            )
            AnimatedVisibility(
                visible = toConfirm,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Text(
                    text = title,
                    overflow = TextOverflow.Visible,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        content?.invoke()
    }
}

@Composable
fun LibraryBottomActionMenu(
    visible: Boolean,
    onChangeCategoryClicked: () -> Unit,
    onMarkAsReadClicked: () -> Unit,
    onMarkAsUnreadClicked: () -> Unit,
    onDownloadClicked: ((DownloadAction) -> Unit)?,
    onDeleteClicked: () -> Unit,
    onMigrateClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(delayMillis = 300)),
        exit = shrinkVertically(animationSpec = tween()),
    ) {
        val scope = rememberCoroutineScope()
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.large.copy(bottomEnd = ZeroCornerSize, bottomStart = ZeroCornerSize),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            val haptic = LocalHapticFeedback.current
            val confirm = remember { mutableStateListOf(false, false, false, false, false, false) }
            var resetJob by remember { mutableStateOf<Job?>(null) }
            val onLongClickItem: (Int) -> Unit = { toConfirmIndex ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                confirm.indices.forEach { i -> confirm[i] = i == toConfirmIndex }
                resetJob?.cancel()
                resetJob = scope.launch {
                    delay(1.seconds)
                    if (isActive) confirm[toConfirmIndex] = false
                }
            }
            val itemOverflow = onDownloadClicked != null
            Row(
                modifier = Modifier
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .only(WindowInsetsSides.Bottom),
                    )
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                Button(
                    title = stringResource(MR.strings.action_move_category),
                    icon = Icons.AutoMirrored.Outlined.Label,
                    toConfirm = confirm[0],
                    onLongClick = { onLongClickItem(0) },
                    onClick = onChangeCategoryClicked,
                )
                Button(
                    title = stringResource(MR.strings.action_mark_as_read),
                    icon = Icons.Outlined.DoneAll,
                    toConfirm = confirm[1],
                    onLongClick = { onLongClickItem(1) },
                    onClick = onMarkAsReadClicked,
                )
                Button(
                    title = stringResource(MR.strings.action_mark_as_unread),
                    icon = Icons.Outlined.RemoveDone,
                    toConfirm = confirm[2],
                    onLongClick = { onLongClickItem(2) },
                    onClick = onMarkAsUnreadClicked,
                )
                if (onDownloadClicked != null) {
                    var downloadExpanded by remember { mutableStateOf(false) }
                    Button(
                        title = stringResource(MR.strings.action_download),
                        icon = Icons.Outlined.Download,
                        toConfirm = confirm[3],
                        onLongClick = { onLongClickItem(3) },
                        onClick = { downloadExpanded = !downloadExpanded },
                    ) {
                        DownloadDropdownMenu(
                            expanded = downloadExpanded,
                            onDismissRequest = { downloadExpanded = false },
                            onDownloadClicked = onDownloadClicked,
                            offset = BottomBarMenuDpOffset,
                        )
                    }
                }
                if (!itemOverflow) {
                    Button(
                        title = stringResource(MR.strings.migrate),
                        icon = Icons.Outlined.SwapCalls,
                        toConfirm = confirm[4],
                        onLongClick = { onLongClickItem(4) },
                        onClick = onMigrateClicked,
                    )
                    Button(
                        title = stringResource(MR.strings.action_delete),
                        icon = Icons.Outlined.Delete,
                        toConfirm = confirm[5],
                        onLongClick = { onLongClickItem(5) },
                        onClick = onDeleteClicked,
                    )
                } else {
                    var overflowMenuOpen by remember { mutableStateOf(false) }
                    Button(
                        title = stringResource(MR.strings.label_more),
                        icon = Icons.Outlined.MoreVert,
                        toConfirm = false,
                        onLongClick = {},
                        onClick = { overflowMenuOpen = true },
                    ) {
                        DropdownMenu(
                            expanded = overflowMenuOpen,
                            onDismissRequest = { overflowMenuOpen = false },
                            offset = BottomBarMenuDpOffset,
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.migrate)) },
                                onClick = onMigrateClicked,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.action_delete)) },
                                onClick = onDeleteClicked,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val BottomBarMenuDpOffset = DpOffset(0.dp, 0.dp)
