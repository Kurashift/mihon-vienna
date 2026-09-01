package eu.kanade.presentation.manga.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileDownloadOff
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.data.download.model.Download
import me.saket.swipe.SwipeableActionsBox
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

@Composable
fun MangaChapterListItem(
    title: String,
    subtitle: String? = null,
    readProgress: String?,
    scanlator: String?,
    read: Boolean,
    bookmark: Boolean,
    selected: Boolean,
    downloadIndicatorEnabled: Boolean,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    goodDoujinMarked: Boolean = false,
    flagMarked: Boolean = false,
    cover: Any? = null,
    readProgressFraction: Float? = null,
    onLongClick: (() -> Unit)?,
    onClick: () -> Unit,
    onDownloadClick: ((ChapterDownloadAction) -> Unit)?,
    onChapterSwipe: (LibraryPreferences.ChapterSwipeAction) -> Unit,
    onCopyTitle: (() -> Unit)? = null,
    copyTitleOnLongPress: Boolean = true,
    onTitleBoundsChanged: ((LayoutCoordinates) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val start = getSwipeAction(
        action = chapterSwipeStartAction,
        read = read,
        bookmark = bookmark,
        downloadState = downloadStateProvider(),
        goodDoujinMarked = goodDoujinMarked,
        background = MaterialTheme.colorScheme.primaryContainer,
        onSwipe = { onChapterSwipe(chapterSwipeStartAction) },
    )
    val end = getSwipeAction(
        action = chapterSwipeEndAction,
        read = read,
        bookmark = bookmark,
        downloadState = downloadStateProvider(),
        goodDoujinMarked = goodDoujinMarked,
        background = MaterialTheme.colorScheme.primaryContainer,
        onSwipe = { onChapterSwipe(chapterSwipeEndAction) },
    )

    SwipeableActionsBox(
        modifier = Modifier.clipToBounds(),
        startActions = listOfNotNull(start),
        endActions = listOfNotNull(end),
        swipeThreshold = swipeActionThreshold,
        backgroundUntilSwipeThreshold = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(
            modifier = modifier
                .selectedBackground(selected)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        ) {
            if (cover != null) {
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .width(96.dp)
                        .aspectRatio(2f / 3f)
                        .clip(MaterialTheme.shapes.extraSmall),
                ) {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
                        error = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(if (read) 0.55f else 1f),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .let { if (cover != null) it.heightIn(min = 144.dp) else it },
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val metadataStyle = MaterialTheme.typography.bodySmall
                        .merge(
                            color = LocalContentColor.current
                                .copy(alpha = if (read) DISABLED_ALPHA else SECONDARY_ALPHA),
                        )
                    // 「译名与原名」模式下原名作为第二行显示，长按复制的命中判定要覆盖
                    // 这两行整体：只挂在主标题上的话，按在原名那行会被当成选中章节。
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = when {
                            onCopyTitle != null && copyTitleOnLongPress -> Modifier.combinedClickable(
                                onClick = onClick,
                                onLongClick = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onCopyTitle()
                                },
                                // 波纹仍由外层整行的 clickable 提供，这里不要再叠一层。
                                indication = null,
                                interactionSource = null,
                            )
                            onTitleBoundsChanged != null -> Modifier.onGloballyPositioned(onTitleBoundsChanged)
                            else -> Modifier
                        },
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalContentColor.current.copy(alpha = if (read) DISABLED_ALPHA else 1f),
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ProvideTextStyle(value = metadataStyle) {
                            if (subtitle != null) {
                                Text(
                                    text = subtitle,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    ProvideTextStyle(value = metadataStyle) {
                        if (scanlator != null) {
                            Text(
                                text = scanlator,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(MaterialTheme.shapes.extraSmall),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (readProgressFraction != null) {
                        LinearProgressIndicator(
                            progress = { readProgressFraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .let { if (cover != null) it.heightIn(min = 144.dp) else it }
                    .padding(start = 10.dp, end = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                if (cover != null) {
                    // With a cover the rail has room to spare, so each marker gets a reserved
                    // slot and the group is centred as a whole. Slots stay put whether or not
                    // their marker is set, so the read marker keeps the exact same spot on every
                    // row and can be scanned down the list, and adding a flag or a heart never
                    // pushes anything around.
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                // The download indicator is 40dp tall, so both side groups are
                                // reserved at that height to keep the read marker centred.
                                modifier = Modifier.heightIn(min = 40.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (flagMarked) {
                                    StatusIcon(
                                        imageVector = Icons.Filled.Flag,
                                        contentDescription = stringResource(
                                            MR.strings.action_mark_duplicate,
                                        ),
                                    )
                                }
                                if (bookmark) {
                                    StatusIcon(
                                        imageVector = Icons.Filled.Bookmark,
                                        contentDescription = stringResource(
                                            MR.strings.action_filter_bookmarked,
                                        ),
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier.size(18.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (read) {
                                    StatusIcon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = stringResource(
                                            MR.strings.action_mark_as_read,
                                        ),
                                        size = 18.dp,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.heightIn(min = 40.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (goodDoujinMarked) {
                                    StatusIcon(
                                        imageVector = Icons.Filled.Favorite,
                                        contentDescription = stringResource(
                                            MR.strings.action_add_to_good_doujin,
                                        ),
                                    )
                                }
                                if (downloadIndicatorEnabled) {
                                    ChapterDownloadIndicator(
                                        enabled = true,
                                        downloadStateProvider = downloadStateProvider,
                                        downloadProgressProvider = downloadProgressProvider,
                                        onClick = { onDownloadClick?.invoke(it) },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (read) {
                            StatusIcon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = stringResource(MR.strings.action_mark_as_read),
                                size = 18.dp,
                            )
                        }
                        if (flagMarked) {
                            StatusIcon(
                                imageVector = Icons.Filled.Flag,
                                contentDescription = stringResource(MR.strings.action_mark_duplicate),
                            )
                        }
                        if (bookmark) {
                            StatusIcon(
                                imageVector = Icons.Filled.Bookmark,
                                contentDescription = stringResource(
                                    MR.strings.action_filter_bookmarked,
                                ),
                            )
                        }
                        if (goodDoujinMarked) {
                            StatusIcon(
                                imageVector = Icons.Filled.Favorite,
                                contentDescription = stringResource(
                                    MR.strings.action_add_to_good_doujin,
                                ),
                            )
                        }
                        if (downloadIndicatorEnabled) {
                            ChapterDownloadIndicator(
                                enabled = true,
                                downloadStateProvider = downloadStateProvider,
                                downloadProgressProvider = downloadProgressProvider,
                                onClick = { onDownloadClick?.invoke(it) },
                            )
                        }
                    }
                }

                if (readProgress != null) {
                    Text(
                        text = readProgress,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        color = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(
    imageVector: ImageVector,
    contentDescription: String,
    size: Dp = 16.dp,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = Modifier.size(size),
        tint = MaterialTheme.colorScheme.primary,
    )
}

private fun getSwipeAction(
    action: LibraryPreferences.ChapterSwipeAction,
    read: Boolean,
    bookmark: Boolean,
    downloadState: Download.State,
    goodDoujinMarked: Boolean,
    background: Color,
    onSwipe: () -> Unit,
): me.saket.swipe.SwipeAction? {
    return when (action) {
        LibraryPreferences.ChapterSwipeAction.ToggleRead -> swipeAction(
            icon = if (!read) Icons.Outlined.Done else Icons.Outlined.RemoveDone,
            background = background,
            isUndo = read,
            onSwipe = onSwipe,
        )
        LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> swipeAction(
            icon = if (bookmark) Icons.Outlined.BookmarkRemove else Icons.Outlined.BookmarkAdd,
            background = background,
            isUndo = bookmark,
            onSwipe = onSwipe,
        )
        LibraryPreferences.ChapterSwipeAction.AddToGoodDoujin -> swipeAction(
            icon = if (goodDoujinMarked) Icons.Outlined.FavoriteBorder else Icons.Filled.Favorite,
            background = background,
            isUndo = goodDoujinMarked,
            onSwipe = onSwipe,
        )
        LibraryPreferences.ChapterSwipeAction.Download -> swipeAction(
            icon = when (downloadState) {
                Download.State.NOT_DOWNLOADED, Download.State.ERROR -> Icons.Outlined.Download
                Download.State.QUEUE, Download.State.DOWNLOADING -> Icons.Outlined.FileDownloadOff
                Download.State.DOWNLOADED -> Icons.Outlined.Delete
            },
            background = background,
            onSwipe = onSwipe,
        )
        LibraryPreferences.ChapterSwipeAction.Disabled -> null
    }
}

private fun swipeAction(
    onSwipe: () -> Unit,
    icon: ImageVector,
    background: Color,
    isUndo: Boolean = false,
): me.saket.swipe.SwipeAction {
    return me.saket.swipe.SwipeAction(
        icon = {
            Icon(
                modifier = Modifier.padding(16.dp),
                imageVector = icon,
                tint = contentColorFor(background),
                contentDescription = null,
            )
        },
        background = background,
        onSwipe = onSwipe,
        isUndo = isUndo,
    )
}

private val swipeActionThreshold = 56.dp
