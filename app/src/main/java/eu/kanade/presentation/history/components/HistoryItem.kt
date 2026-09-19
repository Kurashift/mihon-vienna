package eu.kanade.presentation.history.components

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.util.lang.toTimestampString
import kotlinx.coroutines.launch
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalChapterCover
import kotlin.math.roundToInt

private val HistoryItemHeight = 96.dp

// 左滑露出垃圾桶的宽度：单个图标按钮的舒适触达区，与章节列表快滑的 56dp 阈值同量级。
private val HistoryRevealWidth = 72.dp

private enum class HistorySwipe { Closed, Revealed }

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
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealPx = remember(density) { with(density) { HistoryRevealWidth.toPx() } }
    val currentOnDeleteSwipe by rememberUpdatedState(onClickDeleteSwipe)

    // 左滑是「露出并停住」而不是划过阈值即触发：条目停在露出一半以上（36dp）的位置，
    // 垃圾桶变成可点按钮，点按才删除；点条目本体或右滑收回。锚点拖拽由
    // AnchoredDraggableState 的就近锚点回弹负责，这里只管声明两个位置。
    val swipeState = remember(history.id) { AnchoredDraggableState(initialValue = HistorySwipe.Closed) }
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

    Box(modifier = modifier.clipToBounds()) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                // Same constant as the content row: the box hosting both is measured without a
                // height bound inside the list, where fillMaxHeight degrades to wrapping the
                // icon and the strip would end up half the entry's height.
                .height(HistoryItemHeight)
                .width(HistoryRevealWidth)
                .graphicsLayer {
                    // 垃圾桶条必须跟着内容边缘一起滑：收起时整条被平移到右边界之外，
                    // 经 clipToBounds 裁掉——既不可见也不可点，只随左滑滑进来。内容行
                    // 背景是透明的，靠「内容盖住它」遮不住静止状态的条。
                    translationX = (swipeState.offset.takeIf { it.isFinite() } ?: 0f) + revealPx
                }
                .background(MaterialTheme.colorScheme.errorContainer),
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
                    contentDescription = stringResource(MR.strings.action_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Row(
            modifier = Modifier
                .offset { IntOffset(swipeState.offset.takeIf { it.isFinite() }?.roundToInt() ?: 0, 0) }
                .anchoredDraggable(
                    state = swipeState,
                    orientation = Orientation.Horizontal,
                    enabled = !selectionMode,
                    flingBehavior = flingBehavior,
                )
                .selectedBackground(selected)
                .combinedClickable(
                    onClick = {
                        if (swipeState.settledValue == HistorySwipe.Revealed) {
                            scope.launch { swipeState.animateTo(HistorySwipe.Closed) }
                        } else if (selectionMode) {
                            onClickToggleSelection()
                        } else {
                            onClickResume()
                        }
                    },
                    onLongClick = {
                        if (swipeState.settledValue == HistorySwipe.Revealed) {
                            scope.launch { swipeState.animateTo(HistorySwipe.Closed) }
                        } else {
                            onClickToggleSelection()
                        }
                    },
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
