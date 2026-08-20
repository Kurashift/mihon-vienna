package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalChapterCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LocalReadReviewScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val database = remember { Injekt.get<Database>() }
        val updateChapter = remember { Injekt.get<UpdateChapter>() }
        val readChapters by remember(database) {
            database.chaptersQueries
                .getReadChaptersBySource(LocalSource.ID, ::mapReadLocalChapter)
                .subscribeToList(Dispatchers.IO)
        }.collectAsState(initial = emptyList())
        val grouped = remember(readChapters) { readChapters.groupBy(LocalReadReviewItem::mangaId) }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.local_read_review_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            if (readChapters.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.local_read_review_empty,
                    modifier = Modifier.padding(contentPadding),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(116.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = stringResource(MR.strings.local_read_review_count, readChapters.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    grouped.values.forEach { mangaChapters ->
                        val mangaTitle = mangaChapters.first().mangaTitle
                        item(
                            key = "manga-${mangaChapters.first().mangaId}",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            Column(modifier = Modifier.padding(top = 6.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            navigator.push(MangaScreen(mangaChapters.first().mangaId, true))
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = mangaTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = mangaChapters.size.toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = stringResource(MR.strings.marks_list_open_manga),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                        items(
                            items = mangaChapters,
                            key = LocalReadReviewItem::chapterId,
                        ) { chapter ->
                            LocalReadReviewItem(
                                item = chapter,
                                onOpen = {
                                    context.startActivity(
                                        ReaderActivity.newIntent(
                                            context = context,
                                            mangaId = chapter.mangaId,
                                            chapterId = chapter.chapterId,
                                            pageIndex = 0,
                                        ),
                                    )
                                },
                                onMarkUnread = {
                                    scope.launch {
                                        updateChapter.await(
                                            ChapterUpdate(
                                                id = chapter.chapterId,
                                                read = false,
                                                lastPageRead = 0,
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalReadReviewItem(
    item: LocalReadReviewItem,
    onOpen: () -> Unit,
    onMarkUnread: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 5.dp, vertical = 7.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = onOpen),
        ) {
            AsyncImage(
                model = LocalChapterCover(
                    chapterId = item.chapterId,
                    chapterUrl = item.chapterUrl,
                    version = item.version xor item.dateUpload xor item.lastModifiedAt,
                ),
                contentDescription = item.chapterName,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
                error = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onMarkUnread,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(36.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color.Black.copy(alpha = 0.68f)),
            ) {
                Icon(
                    imageVector = Icons.Outlined.RemoveDone,
                    contentDescription = stringResource(MR.strings.action_mark_as_unread),
                    tint = Color.White,
                )
            }
        }
        Text(
            text = item.chapterName,
            style = MaterialTheme.typography.bodySmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, top = 5.dp, end = 2.dp),
        )
        if (item.totalPages > 0) {
            Text(
                text = stringResource(
                    MR.strings.chapter_progress_ratio,
                    item.totalPages,
                    item.totalPages,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

private data class LocalReadReviewItem(
    val chapterId: Long,
    val mangaId: Long,
    val mangaTitle: String,
    val chapterUrl: String,
    val chapterName: String,
    val totalPages: Long,
    val dateUpload: Long,
    val lastModifiedAt: Long,
    val version: Long,
)

private fun mapReadLocalChapter(
    chapter_id: Long,
    manga_id: Long,
    manga_title: String,
    chapter_url: String,
    chapter_name: String,
    total_pages: Long,
    date_upload: Long,
    last_modified_at: Long,
    version: Long,
) = LocalReadReviewItem(
    chapterId = chapter_id,
    mangaId = manga_id,
    mangaTitle = manga_title,
    chapterUrl = chapter_url,
    chapterName = chapter_name,
    totalPages = total_pages,
    dateUpload = date_upload,
    lastModifiedAt = last_modified_at,
    version = version,
)
