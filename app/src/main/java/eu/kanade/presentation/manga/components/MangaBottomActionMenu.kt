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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.SwapCalls
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.DownloadDropdownMenu
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.model.Chapter
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
    readRanges: ReadRangeActions? = null,
    onMarkRangeClicked: ((chapters: List<Chapter>, read: Boolean) -> Unit)? = null,
    onDownloadClicked: (() -> Unit)? = null,
    onDeleteClicked: (() -> Unit)? = null,
    onDeleteLocalFilesClicked: (() -> Unit)? = null,
    onEditTranslatedTitleClicked: (() -> Unit)? = null,
    onToggleMarkClicked: (() -> Unit)? = null,
    onMoveClicked: (() -> Unit)? = null,
    marksSelected: Boolean = false,
    selectedCount: Int = 0,
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
            val confirm = remember { mutableStateListOf(false, false, false, false, false) }
            var resetJob by remember { mutableStateOf<Job?>(null) }
            val onLongClickItem: (Int) -> Unit = { toConfirmIndex ->
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
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                ActionGroup(
                    // Bookmarking and un-bookmarking are mutually exclusive, so one slot covers
                    // both instead of reserving two positions in the row.
                    actions = listOfNotNull(
                        onBookmarkClicked?.let { onClick ->
                            BottomBarAction(
                                title = stringResource(MR.strings.action_bookmark),
                                icon = Icons.Outlined.BookmarkAdd,
                                onClick = onClick,
                            )
                        },
                        onRemoveBookmarkClicked?.let { onClick ->
                            BottomBarAction(
                                title = stringResource(MR.strings.action_remove_bookmark),
                                icon = Icons.Outlined.BookmarkRemove,
                                onClick = onClick,
                            )
                        },
                    ),
                    confirm = confirm,
                    confirmIndex = CONFIRM_BOOKMARK,
                    onLongClickItem = onLongClickItem,
                )
                ActionGroup(
                    actions = listOfNotNull(
                        onAddToGoodDoujinClicked?.let { onClick ->
                            BottomBarAction(
                                title = stringResource(MR.strings.action_add_to_good_doujin),
                                icon = Icons.Outlined.FavoriteBorder,
                                onClick = onClick,
                            )
                        },
                        onRemoveFromGoodDoujinClicked?.let { onClick ->
                            BottomBarAction(
                                title = stringResource(MR.strings.action_remove_from_good_doujin),
                                icon = Icons.Filled.Favorite,
                                onClick = onClick,
                            )
                        },
                        onToggleMarkClicked?.let { onClick ->
                            BottomBarAction(
                                title = stringResource(
                                    if (marksSelected) {
                                        MR.strings.action_unmark_duplicate
                                    } else {
                                        MR.strings.action_mark_duplicate
                                    },
                                ),
                                icon = if (marksSelected) Icons.Filled.Flag else Icons.Outlined.Flag,
                                onClick = onClick,
                            )
                        },
                    ),
                    confirm = confirm,
                    confirmIndex = CONFIRM_MARK,
                    onLongClickItem = onLongClickItem,
                    groupTitle = stringResource(MR.strings.action_mark_group),
                    groupIcon = Icons.AutoMirrored.Outlined.Label,
                )
                ReadStatusGroup(
                    // The self toggle and the before/after shortcuts all act on the same read
                    // state, so they share one slot. "Read selected" and "mark as unread" never
                    // coexist: the self row is whichever one applies. The ranges only exist for a
                    // single selection, and a direction with nothing left to change is dropped, so
                    // the slot collapses back to a plain button whenever no range has work to do.
                    onMarkAsReadClicked = onMarkAsReadClicked,
                    onMarkAsUnreadClicked = onMarkAsUnreadClicked,
                    selectedCount = selectedCount,
                    readRanges = readRanges,
                    onMarkRangeClicked = onMarkRangeClicked,
                    confirm = confirm,
                    confirmIndex = CONFIRM_READ,
                    onLongClickItem = onLongClickItem,
                )
                if (onDownloadClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_download),
                        icon = Icons.Outlined.Download,
                        toConfirm = confirm[CONFIRM_DOWNLOAD],
                        onLongClick = { onLongClickItem(CONFIRM_DOWNLOAD) },
                        onClick = onDownloadClicked,
                    )
                }
                // Single-selection actions sit after the ones that survive multi-selection, so
                // entering selection mode drops the trailing buttons as one contiguous group
                // instead of tearing a hole in the middle of the row.
                if (onMoveClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_move_to),
                        icon = Icons.Outlined.DriveFileMove,
                        toConfirm = false,
                        onLongClick = {},
                        onClick = onMoveClicked,
                    )
                }
                if (onEditTranslatedTitleClicked != null) {
                    Button(
                        title = stringResource(MR.strings.edit_chapter_translated_title),
                        icon = Icons.Outlined.Edit,
                        toConfirm = confirm[CONFIRM_TITLE],
                        onLongClick = { onLongClickItem(CONFIRM_TITLE) },
                        onClick = onEditTranslatedTitleClicked,
                    )
                }
                // The trailing delete slot is either "remove the download" for sourced manga or
                // "erase the local files" for local manga. The two never coexist, so the row keeps
                // a single destructive action at the end.
                //
                // Only the one that erases files is set apart: a rule separates it and it takes
                // just the width its icon needs, so it cannot be hit by aiming at a neighbour.
                if (onDeleteLocalFilesClicked != null) {
                    VerticalDivider(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .height(24.dp),
                    )
                    Button(
                        title = stringResource(MR.strings.action_delete_local_files),
                        icon = Icons.Filled.Delete,
                        toConfirm = false,
                        onLongClick = {},
                        onClick = onDeleteLocalFilesClicked,
                        tint = MaterialTheme.colorScheme.error,
                        fill = false,
                    )
                } else if (onDeleteClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_delete),
                        icon = Icons.Outlined.Delete,
                        toConfirm = confirm[CONFIRM_DELETE],
                        onLongClick = { onLongClickItem(CONFIRM_DELETE) },
                        onClick = onDeleteClicked,
                    )
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
    tint: Color = LocalContentColor.current,
    // A trailing destructive action keeps its own narrow slot instead of an equal share, so it
    // reads as set apart from the group rather than as one more peer of it.
    fill: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    val animatedWeight by animateFloatAsState(
        targetValue = if (toConfirm) 2f else 1f,
        label = "weight",
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .then(if (fill) Modifier.weight(animatedWeight) else Modifier)
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
                tint = tint,
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
    onDeleteClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    modifier: Modifier = Modifier,
    // Only the shelf offers this: taking a work off the shelf is the shelf's own decision, while a
    // source listing has no shelf membership to drop.
    onRemoveFromLibraryClicked: (() -> Unit)? = null,
    // What the trailing erase button erases. Callers whose delete removes files name them so the
    // button cannot be mistaken for dropping a list entry; the plain default keeps the upstream
    // look for callers that have nothing more specific to say.
    deleteTitle: StringResource = MR.strings.action_delete,
    // Set only by a caller whose delete erases files from disk; that is the one destructive action
    // the row paints red and sets apart, matching how the detail screen marks its own.
    deleteTint: Color? = null,
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
            val confirm = remember { mutableStateListOf(false, false, false, false, false, false) }
            var resetJob by remember { mutableStateOf<Job?>(null) }
            val onLongClickItem: (Int) -> Unit = { toConfirmIndex ->
                confirm.indices.forEach { i -> confirm[i] = i == toConfirmIndex }
                resetJob?.cancel()
                resetJob = scope.launch {
                    delay(1.seconds)
                    if (isActive) confirm[toConfirmIndex] = false
                }
            }
            // Downloading is the one action that always has to fold the trailing pair into an
            // overflow menu; without it the row still fits every action inline.
            val itemOverflow = onDownloadClicked != null
            Row(
                modifier = Modifier
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .only(WindowInsetsSides.Bottom),
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Button(
                    // The shelf is what this action edits, so it carries the library's own mark
                    // rather than the plain label tag that the chapter menus use.
                    title = stringResource(MR.strings.action_move_category),
                    icon = Icons.Outlined.CollectionsBookmark,
                    toConfirm = confirm[0],
                    onLongClick = { onLongClickItem(0) },
                    onClick = onChangeCategoryClicked,
                )
                // The read toggles share one slot that opens a menu, the same pattern as the
                // detail screen's read-status entry: the pair is one tap deeper, and the row
                // keeps fewer icons to parse.
                var readExpanded by remember { mutableStateOf(false) }
                Button(
                    title = stringResource(MR.strings.action_read_group),
                    icon = ImageVector.vectorResource(R.drawable.ic_done_edit_24dp),
                    toConfirm = confirm[1],
                    onLongClick = { onLongClickItem(1) },
                    onClick = { readExpanded = !readExpanded },
                ) {
                    DropdownMenu(
                        expanded = readExpanded,
                        onDismissRequest = { readExpanded = false },
                        offset = BottomBarMenuDpOffset,
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.action_mark_as_read)) },
                            leadingIcon = { Icon(Icons.Outlined.DoneAll, contentDescription = null) },
                            onClick = {
                                readExpanded = false
                                onMarkAsReadClicked()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.action_mark_as_unread)) },
                            leadingIcon = { Icon(Icons.Outlined.RemoveDone, contentDescription = null) },
                            onClick = {
                                readExpanded = false
                                onMarkAsUnreadClicked()
                            },
                        )
                    }
                }
                if (onRemoveFromLibraryClicked != null) {
                    // Reads as the shelf picker's counterpart and wears the crossed-out bookmark
                    // the "not in library" filter uses: the picker files a work, this takes it off.
                    // It trails the reading actions rather than leading them, so the button that
                    // drops works from the shelf is not the one sitting first under the thumb.
                    Button(
                        title = stringResource(MR.strings.action_remove_from_library),
                        icon = Icons.Outlined.BookmarkRemove,
                        toConfirm = confirm[5],
                        onLongClick = { onLongClickItem(5) },
                        onClick = onRemoveFromLibraryClicked,
                    )
                }
                if (onDownloadClicked != null) {
                    var downloadExpanded by remember { mutableStateOf(false) }
                    Button(
                        title = stringResource(MR.strings.action_download),
                        icon = Icons.Outlined.Download,
                        toConfirm = confirm[2],
                        onLongClick = { onLongClickItem(2) },
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
                    if (onMigrateClicked != null) {
                        Button(
                            title = stringResource(MR.strings.migrate),
                            icon = Icons.Outlined.SwapCalls,
                            toConfirm = confirm[3],
                            onLongClick = { onLongClickItem(3) },
                            onClick = onMigrateClicked,
                        )
                    }
                    if (onDeleteClicked != null) {
                        // Red, filled and set apart only when the action erases files from the
                        // device - a local library's directories, or downloaded chapters. A delete
                        // that merely drops a list entry keeps the ordinary look. Either way the
                        // long press guard applies.
                        if (deleteTint != null) {
                            VerticalDivider(
                                modifier = Modifier
                                    .align(Alignment.CenterVertically)
                                    .height(24.dp),
                            )
                        }
                        Button(
                            // Named by the caller for what it actually erases: a bare "delete"
                            // beside a remove-from-shelf action reads as the latter, while the
                            // two do very different things to a work.
                            title = stringResource(deleteTitle),
                            icon = if (deleteTint != null) Icons.Filled.Delete else Icons.Outlined.Delete,
                            toConfirm = confirm[4],
                            onLongClick = { onLongClickItem(4) },
                            onClick = onDeleteClicked,
                            tint = deleteTint ?: LocalContentColor.current,
                            fill = deleteTint == null,
                        )
                    }
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
                            if (onMigrateClicked != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(MR.strings.migrate)) },
                                    onClick = onMigrateClicked,
                                )
                            }
                            if (onDeleteClicked != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(deleteTitle)) },
                                    onClick = onDeleteClicked,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val BottomBarMenuDpOffset = DpOffset(0.dp, 0.dp)

// Slots that a long press can label. Actions sharing a slot share one entry.
private const val CONFIRM_BOOKMARK = 0
private const val CONFIRM_MARK = 1
private const val CONFIRM_READ = 2
private const val CONFIRM_DOWNLOAD = 3
private const val CONFIRM_TITLE = 4
private const val CONFIRM_DELETE = 5

private data class BottomBarAction(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * The chapters the before/after entries would change, split by direction and by the state being
 * applied, and already narrowed to the chapters whose state actually differs. An empty list is
 * therefore a legitimate answer and simply means "this entry would change nothing" — the menu
 * leaves such an entry out entirely rather than showing it greyed out.
 *
 * Both sides include the selected chapter, so the two directions stay symmetric.
 */
data class ReadRangeActions(
    val beforeToRead: List<Chapter>,
    val beforeToUnread: List<Chapter>,
    val afterToRead: List<Chapter>,
    val afterToUnread: List<Chapter>,
) {
    /** Whether any before/after entry still has something to change. */
    fun hasChanges(): Boolean =
        beforeToRead.isNotEmpty() ||
            beforeToUnread.isNotEmpty() ||
            afterToRead.isNotEmpty() ||
            afterToUnread.isNotEmpty()
}

/**
 * One slot in the bottom action bar holding mutually exclusive or closely related actions.
 *
 * With a single action it behaves as a plain button: the label is that action's title and a tap runs
 * it right away, so the bar never opens a menu holding a lone entry. With several actions it shows
 * [groupTitle] and [groupIcon], which stay the same whatever the selection is — the point being that
 * the slot's identity must not flick between the icons of the actions it currently holds.
 */
@Composable
private fun RowScope.ActionGroup(
    actions: List<BottomBarAction>,
    confirm: List<Boolean>,
    confirmIndex: Int,
    onLongClickItem: (Int) -> Unit,
    groupTitle: String? = null,
    groupIcon: ImageVector? = null,
) {
    if (actions.isEmpty()) return
    if (actions.size == 1) {
        val action = actions.first()
        Button(
            title = action.title,
            icon = action.icon,
            toConfirm = confirm[confirmIndex],
            onLongClick = { onLongClickItem(confirmIndex) },
            onClick = action.onClick,
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Button(
        title = groupTitle ?: actions.first().title,
        icon = groupIcon ?: actions.first().icon,
        toConfirm = confirm[confirmIndex],
        onLongClick = { onLongClickItem(confirmIndex) },
        onClick = { expanded = true },
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = BottomBarMenuDpOffset,
        ) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.title) },
                    leadingIcon = { Icon(action.icon, contentDescription = null) },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

/**
 * The read-status slot of the bottom action bar.
 *
 * The slot always opens its menu, whatever the selection is. Marking read and marking unread are
 * two directions of one state, so a selection with only one direction left used to run on the
 * first tap while a mixed one asked — the same button behaving two ways depending on what was
 * picked. Every direction that still has work to do is offered instead: marking the selection
 * read, marking it unread, and — for a single selection — the before/after ranges. A direction
 * already entirely in the target state is dropped rather than greyed out, which keeps the menu a
 * short single column instead of a wide double one.
 */
@Composable
private fun RowScope.ReadStatusGroup(
    onMarkAsReadClicked: (() -> Unit)?,
    onMarkAsUnreadClicked: (() -> Unit)?,
    selectedCount: Int,
    readRanges: ReadRangeActions?,
    onMarkRangeClicked: ((chapters: List<Chapter>, read: Boolean) -> Unit)?,
    confirm: List<Boolean>,
    confirmIndex: Int,
    onLongClickItem: (Int) -> Unit,
) {
    val selfRead = onMarkAsReadClicked?.let { onClick ->
        BottomBarAction(
            title = stringResource(MR.strings.action_read_selected),
            // A single selection gets one check, a multi-selection gets two.
            icon = if (selectedCount == 1) Icons.Outlined.Done else Icons.Outlined.DoneAll,
            onClick = onClick,
        )
    }
    val selfUnread = onMarkAsUnreadClicked?.let { onClick ->
        BottomBarAction(
            title = stringResource(MR.strings.action_mark_as_unread),
            icon = Icons.Outlined.RemoveDone,
            onClick = onClick,
        )
    }
    val onRange = onMarkRangeClicked
    val ranges = readRanges?.takeIf { it.hasChanges() && onRange != null }
    val selfs = listOfNotNull(selfRead, selfUnread)

    if (selfs.isEmpty() && ranges == null) return
    val hasBefore = ranges?.let { it.beforeToRead.isNotEmpty() || it.beforeToUnread.isNotEmpty() } == true

    var expanded by remember { mutableStateOf(false) }
    Button(
        title = stringResource(MR.strings.action_read_group),
        icon = ImageVector.vectorResource(R.drawable.ic_done_edit_24dp),
        toConfirm = confirm[confirmIndex],
        onLongClick = { onLongClickItem(confirmIndex) },
        onClick = { expanded = true },
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = BottomBarMenuDpOffset,
        ) {
            selfs.forEach { self ->
                DropdownMenuItem(
                    text = { Text(self.title) },
                    leadingIcon = { Icon(self.icon, contentDescription = null) },
                    onClick = {
                        expanded = false
                        self.onClick()
                    },
                )
            }
            val rangeHandler = onRange
            if (ranges != null && rangeHandler != null) {
                ReadRangeBlock(
                    heading = stringResource(MR.strings.action_read_range_before),
                    toRead = ranges.beforeToRead,
                    toUnread = ranges.beforeToUnread,
                    readIcon = ImageVector.vectorResource(R.drawable.ic_done_prev_24dp),
                    unreadIcon = ImageVector.vectorResource(R.drawable.ic_undone_prev_24dp),
                    showDivider = selfs.isNotEmpty(),
                    onMarkRangeClicked = rangeHandler,
                    onDone = { expanded = false },
                )
                ReadRangeBlock(
                    heading = stringResource(MR.strings.action_read_range_after),
                    toRead = ranges.afterToRead,
                    toUnread = ranges.afterToUnread,
                    readIcon = ImageVector.vectorResource(R.drawable.ic_done_next_24dp),
                    unreadIcon = ImageVector.vectorResource(R.drawable.ic_undone_next_24dp),
                    showDivider = selfs.isNotEmpty() || hasBefore,
                    onMarkRangeClicked = rangeHandler,
                    onDone = { expanded = false },
                )
            }
        }
    }
}

/**
 * One before/after block of the read menu: a heading naming the direction, then an entry for each
 * state that still has chapters to change. A state already applied to every chapter in that
 * direction is dropped, and the block as a whole disappears when neither state has work to do, so
 * the menu never carries a row that would do nothing when tapped.
 */
@Composable
private fun ColumnScope.ReadRangeBlock(
    heading: String,
    toRead: List<Chapter>,
    toUnread: List<Chapter>,
    readIcon: ImageVector,
    unreadIcon: ImageVector,
    showDivider: Boolean,
    onMarkRangeClicked: (chapters: List<Chapter>, read: Boolean) -> Unit,
    onDone: () -> Unit,
) {
    if (toRead.isEmpty() && toUnread.isEmpty()) return
    if (showDivider) HorizontalDivider()
    Text(
        text = heading,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    ReadRangeEntry(
        title = stringResource(MR.strings.action_mark_as_read),
        icon = readIcon,
        chapters = toRead,
        read = true,
        onMarkRangeClicked = onMarkRangeClicked,
        onDone = onDone,
    )
    ReadRangeEntry(
        title = stringResource(MR.strings.action_mark_as_unread),
        icon = unreadIcon,
        chapters = toUnread,
        read = false,
        onMarkRangeClicked = onMarkRangeClicked,
        onDone = onDone,
    )
}

/**
 * A single before/after entry. Empty [chapters] means it would change nothing, so it is dropped
 * instead of sitting in the menu greyed out: with one entry per row there is no second half that
 * could swap places, which is the reason the old double column needed the placeholders.
 */
@Composable
private fun ReadRangeEntry(
    title: String,
    icon: ImageVector,
    chapters: List<Chapter>,
    read: Boolean,
    onMarkRangeClicked: (chapters: List<Chapter>, read: Boolean) -> Unit,
    onDone: () -> Unit,
) {
    if (chapters.isEmpty()) return
    DropdownMenuItem(
        text = { Text(title) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = {
            onDone()
            onMarkRangeClicked(chapters, read)
        },
    )
}
