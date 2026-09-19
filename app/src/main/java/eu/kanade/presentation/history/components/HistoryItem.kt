package eu.kanade.presentation.history.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.util.lang.toTimestampString
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalChapterCover

private val HistoryItemHeight = 96.dp

@Composable
fun HistoryItem(
    history: HistoryWithRelations,
    onClickCover: () -> Unit,
    onClickResume: () -> Unit,
    onClickDelete: () -> Unit,
    onClickFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .combinedClickable(
                onClick = onClickResume,
                onLongClick = { menuExpanded = true },
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
            onClick = onClickCover,
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
            // Same rule the updates list uses: only an unfinished chapter that was actually
            // opened carries progress, so finished rows stay clean. The number is the page the
            // reader was left on, in the same x/y form the reader itself shows.
            val progressText = when {
                history.chapterRead ||
                    history.chapterLastPageRead <= 0L ||
                    history.chapterTotalPages <= 0L -> null
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

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            if (!history.coverData.isMangaFavorite) {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.strings.add_to_library)) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.CollectionsBookmark, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onClickFavorite()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.action_delete)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Delete, contentDescription = null)
                },
                onClick = {
                    menuExpanded = false
                    onClickDelete()
                },
            )
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
                onClickCover = {},
                onClickResume = {},
                onClickDelete = {},
                onClickFavorite = {},
            )
        }
    }
}
