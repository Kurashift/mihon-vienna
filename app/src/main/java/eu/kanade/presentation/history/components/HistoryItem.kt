package eu.kanade.presentation.history.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.manga.components.swipeAction
import eu.kanade.presentation.manga.components.swipeActionThreshold
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.util.lang.toTimestampString
import me.saket.swipe.SwipeableActionsBox
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalChapterCover

private val HistoryItemHeight = 96.dp

@Composable
fun HistoryItem(
    history: HistoryWithRelations,
    selected: Boolean,
    selectionMode: Boolean,
    onClickCover: () -> Unit,
    onClickResume: () -> Unit,
    onClickDeleteSwipe: () -> Unit,
    onClickToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 快滑动作实例必须按内容缓存（同 MangaChapterGridItem）：me.saket.swipe 把「已越过阈值」
    // 记在实例上，实例一换就把旧动作当新快滑，越阈的震动会跟着重组重放；onSwipe 走
    // rememberUpdatedState 取最新回调，不参与缓存键。
    val deleteBackground = MaterialTheme.colorScheme.errorContainer
    val currentOnDeleteSwipe by rememberUpdatedState(onClickDeleteSwipe)
    val endActions = remember(history.id, selectionMode, deleteBackground) {
        listOfNotNull(
            swipeAction(
                onSwipe = { currentOnDeleteSwipe() },
                icon = Icons.Outlined.Delete,
                background = deleteBackground,
            ).takeIf { !selectionMode },
        )
    }
    SwipeableActionsBox(
        modifier = modifier.clipToBounds(),
        endActions = endActions,
        swipeThreshold = swipeActionThreshold,
        backgroundUntilSwipeThreshold = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(
            modifier = Modifier
                .selectedBackground(selected)
                .combinedClickable(
                    onClick = if (selectionMode) onClickToggleSelection else onClickResume,
                    onLongClick = onClickToggleSelection,
                )
                .height(HistoryItemHeight)
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isLocal = history.coverData.sourceId == LocalSource.ID
            val cover = if (isLocal) {
                LocalChapterCover(
                    chapterId = history.chapterId,
                    chapterUrl = history.chapterUrl,
                    version = history.chapterVersion xor history.chapterDateUpload xor history.chapterLastModifiedAt,
                )
            } else {
                history.coverData
            }
            val title = if (isLocal) history.chapterDisplayName else history.title
            MangaCover.Book(
                modifier = Modifier.fillMaxHeight(),
                data = cover,
                onClick = onClickCover.takeIf { !selectionMode },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.small),
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val readAt = remember(history.readAt) { history.readAt?.toTimestampString() }
                // Whether an entry carries progress follows the updates list rule: only a chapter
                // that was actually opened does. A finished chapter keeps showing the full ratio
                // instead of going quiet, so the row still reads as "read to the end".
                val progressText = when {
                    history.chapterTotalPages <= 0L -> null
                    history.chapterRead -> stringResource(
                        MR.strings.chapter_progress_ratio,
                        history.chapterTotalPages,
                        history.chapterTotalPages,
                    )
                    history.chapterLastPageRead <= 0L -> null
                    else -> stringResource(
                        MR.strings.chapter_progress_ratio,
                        (history.chapterLastPageRead + 1).coerceAtMost(history.chapterTotalPages),
                        history.chapterTotalPages,
                    )
                }
                val chapterText = if (history.chapterNumber > -1) {
                    stringResource(MR.strings.chapter_number_format, formatChapterNumber(history.chapterNumber))
                } else {
                    null
                }
                if (chapterText != null || progressText != null || readAt != null) {
                    val metaStyle = MaterialTheme.typography.bodySmall
                    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (chapterText != null) {
                                Text(
                                    text = chapterText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = metaStyle,
                                    color = metaColor,
                                )
                            }
                            if (chapterText != null && progressText != null) {
                                CompositionLocalProvider(LocalTextStyle provides metaStyle) {
                                    DotSeparatorText()
                                }
                            }
                            if (progressText != null) {
                                Text(
                                    text = progressText,
                                    maxLines = 1,
                                    style = metaStyle,
                                    color = metaColor,
                                )
                            }
                        }
                        if (readAt != null) {
                            Text(
                                text = readAt,
                                maxLines = 1,
                                style = metaStyle,
                                color = metaColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun HistoryItemPreviews(
    @PreviewParameter(HistoryWithRelationsProvider::class)
    historyWithRelations: HistoryWithRelations,
) {
    TachiyomiPreviewTheme {
        Surface {
            HistoryItem(
                history = historyWithRelations,
                selected = false,
                selectionMode = false,
                onClickCover = {},
                onClickResume = {},
                onClickDeleteSwipe = {},
                onClickToggleSelection = {},
            )
        }
    }
}
