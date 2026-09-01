package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.mylists.MY_LIST_COVER_ASPECT_RATIO
import eu.kanade.presentation.mylists.MY_LIST_MANGA_COVER_ASPECT_RATIO
import eu.kanade.presentation.mylists.MyListChapterTitle
import eu.kanade.presentation.mylists.MyListContentGap
import eu.kanade.presentation.mylists.MyListCover
import eu.kanade.presentation.mylists.MyListCoverWidth
import eu.kanade.presentation.mylists.MyListEmptyState
import eu.kanade.presentation.mylists.MyListHorizontalPadding
import eu.kanade.presentation.mylists.MyListRowVerticalPadding
import eu.kanade.presentation.mylists.MyListSelectionIndicator
import eu.kanade.presentation.mylists.MyListShareGroup
import eu.kanade.presentation.mylists.buildMyListShareText
import eu.kanade.presentation.mylists.formatListTime
import eu.kanade.presentation.mylists.myListChapterTitle
import eu.kanade.presentation.mylists.myListEntryMatchesQuery
import eu.kanade.presentation.mylists.myListSearchQuery
import eu.kanade.presentation.mylists.rememberLocalChapterDisplayMode
import eu.kanade.presentation.mylists.rememberMyListExportLauncher
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalChapterCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 已读完篇目：按漫画汇总全部已读完的本地篇目，一页里快速回看，也能撤销误标。
 *
 * 与标记/好本子清单共用同一套卡片观感、溢出菜单与导出格式，
 * 只是主体形态保持为封面网格——快速扫图确认本子是这个页面存在的意义。
 */
class LocalReadReviewScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val database = remember { Injekt.get<Database>() }
        val readChapters by remember(database) {
            database.chaptersQueries
                .getReadChaptersBySource(LocalSource.ID, ::mapReadLocalChapter)
                .subscribeToList(Dispatchers.IO)
        }.collectAsState(initial = emptyList())
        // 组内按读完时间倒序，组间按该漫画最近读完时间倒序 —— 最近误标的排最前。
        val grouped = remember(readChapters) {
            readChapters
                .groupBy(LocalReadReviewItem::mangaId)
                .map { (_, chapters) ->
                    MangaGroup(
                        mangaId = chapters.first().mangaId,
                        mangaTitle = chapters.first().mangaTitle,
                        chapters = chapters.sortedByDescending(LocalReadReviewItem::lastReadAt),
                    )
                }
                .sortedByDescending { it.chapters.first().lastReadAt }
        }

        var query by remember { mutableStateOf<String?>(null) }
        // 搜索按漫画名匹配，篇目名命中时也保留整个漫画分组。译名与原名都可搜：
        // 详情页开着译名时用户在页面上看到的是译名，按原名也应能搜到同一条。
        val visibleGroups = remember(grouped, query) {
            val q = myListSearchQuery(query)
            if (q.isEmpty()) {
                grouped
            } else {
                grouped.filter { group ->
                    group.chapters.any { chapter ->
                        myListEntryMatchesQuery(
                            query = q,
                            mangaTitle = group.mangaTitle,
                            chapterName = chapter.chapterName,
                            translatedName = chapter.chapterTranslatedName,
                        )
                    }
                }
            }
        }
        val displayMode = rememberLocalChapterDisplayMode()
        // 显示名只在这里算一次，导出和二级页复用同一结果，不会两处各算一遍走样。
        val titleByChapterId = remember(visibleGroups, displayMode) {
            visibleGroups
                .flatMap { it.chapters }
                .associate { chapter ->
                    chapter.chapterId to myListChapterTitle(
                        chapterName = chapter.chapterName,
                        translatedName = chapter.chapterTranslatedName,
                        displayMode = displayMode,
                    )
                }
        }
        val mangaIds = remember(visibleGroups) { visibleGroups.map { it.mangaId } }
        val mangaById = rememberMangaMap(mangaIds)
        val listTitle = stringResource(MR.strings.local_read_review_title)
        val exportText = exportTextOf(listTitle, visibleGroups, titleByChapterId)
        val exportList = rememberMyListExportLauncher(
            filename = "mihon_read_review.txt",
            text = exportText,
        )

        Scaffold(
            topBar = { scrollBehavior ->
                SearchToolbar(
                    searchQuery = query,
                    onChangeSearchQuery = { query = it },
                    placeholderText = stringResource(MR.strings.local_read_review_search_hint),
                    titleContent = {
                        Text(
                            text = listTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.export),
                                    icon = Icons.Outlined.FileUpload,
                                    onClick = exportList,
                                    enabled = exportText.isNotEmpty(),
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            if (readChapters.isEmpty()) {
                MyListEmptyState(
                    stringRes = MR.strings.local_read_review_empty,
                    contentPadding = contentPadding,
                )
            } else {
                ScrollbarLazyColumn(contentPadding = contentPadding) {
                    visibleGroups.forEach { group ->
                        item(key = "manga-${group.mangaId}") {
                            LocalReviewMangaRow(
                                group = group,
                                coverModel = mangaById[group.mangaId]?.asMangaCover(),
                                onClick = {
                                    navigator.push(
                                        LocalReadReviewDetailScreen(group.mangaId, group.mangaTitle),
                                    )
                                },
                                onOpenManga = {
                                    openMangaScreen(navigator, scope, context, group.mangaId)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 二级页：一部漫画下的全部已读完篇目，按读完时间倒序。
 * 点行打开阅读器，长按多选后顶栏可批量标记未读。
 */
class LocalReadReviewDetailScreen(
    private val mangaId: Long,
    private val mangaTitle: String,
) : Screen() {

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
        val chapters = remember(readChapters) {
            readChapters
                .filter { it.mangaId == mangaId }
                .sortedByDescending(LocalReadReviewItem::lastReadAt)
        }
        val coverByChapterId = rememberChapterCovers(chapters)
        // 与一级页取同一份偏好，两页的篇目名显示保持一致。
        val displayMode = rememberLocalChapterDisplayMode()
        val titleByChapterId = remember(chapters, displayMode) {
            chapters.associate { chapter ->
                chapter.chapterId to myListChapterTitle(
                    chapterName = chapter.chapterName,
                    translatedName = chapter.chapterTranslatedName,
                    displayMode = displayMode,
                )
            }
        }

        var selectedChapterIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
        var showClearConfirm by remember { mutableStateOf(false) }
        var pendingUnreadIds by remember { mutableStateOf<Set<Long>?>(null) }
        val exportText = exportTextOf(
            listTitle = mangaTitle,
            groups = listOf(MangaGroup(mangaId, mangaTitle, chapters)),
            titleByChapterId = titleByChapterId,
        )
        val exportList = rememberMyListExportLauncher(
            filename = "mihon_read_review.txt",
            text = exportText,
        )

        LaunchedEffect(chapters) {
            val availableIds = chapters.mapTo(mutableSetOf()) { it.chapterId }
            selectedChapterIds = selectedChapterIds.intersect(availableIds)
        }
        BackHandler(enabled = selectedChapterIds.isNotEmpty()) {
            selectedChapterIds = emptySet()
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = mangaTitle,
                    subtitle = stringResource(MR.strings.local_read_review_count, chapters.size),
                    onClickTitle = { openMangaScreen(navigator, scope, context, mangaId) },
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.export),
                                    icon = Icons.Outlined.FileUpload,
                                    onClick = exportList,
                                    enabled = exportText.isNotEmpty(),
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.local_read_review_clear),
                                    icon = Icons.Outlined.RemoveDone,
                                    onClick = { showClearConfirm = true },
                                ),
                            ),
                        )
                    },
                    actionModeCounter = selectedChapterIds.size,
                    onCancelActionMode = { selectedChapterIds = emptySet() },
                    actionModeActions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_mark_as_unread),
                                    icon = Icons.Outlined.RemoveDone,
                                    onClick = { pendingUnreadIds = selectedChapterIds },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            if (chapters.isEmpty()) {
                MyListEmptyState(
                    stringRes = MR.strings.local_read_review_empty,
                    contentPadding = contentPadding,
                )
            } else {
                ScrollbarLazyColumn(contentPadding = contentPadding) {
                    chapters.forEach { chapter ->
                        item(key = chapter.chapterId) {
                            LocalReviewChapterRow(
                                item = chapter,
                                title = titleByChapterId[chapter.chapterId],
                                coverModel = coverByChapterId[chapter.chapterId],
                                selectionMode = selectedChapterIds.isNotEmpty(),
                                selected = chapter.chapterId in selectedChapterIds,
                                onClick = {
                                    context.startActivity(
                                        ReaderActivity.newIntent(
                                            context = context,
                                            mangaId = chapter.mangaId,
                                            chapterId = chapter.chapterId,
                                            pageIndex = 0,
                                        ),
                                    )
                                },
                                onToggleSelection = {
                                    selectedChapterIds = selectedChapterIds.toMutableSet().apply {
                                        if (!add(chapter.chapterId)) remove(chapter.chapterId)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        if (showClearConfirm) {
            ConfirmDialog(
                text = stringResource(MR.strings.local_read_review_clear_confirm, chapters.size),
                confirmText = stringResource(MR.strings.local_read_review_clear),
                onConfirm = {
                    scope.launch {
                        markUnread(updateChapter, chapters.map { it.chapterId })
                        selectedChapterIds = emptySet()
                        showClearConfirm = false
                    }
                },
                onDismiss = { showClearConfirm = false },
            )
        }

        pendingUnreadIds?.let { ids ->
            ConfirmDialog(
                text = stringResource(MR.strings.local_read_review_mark_unread_selected_confirm, ids.size),
                confirmText = stringResource(MR.strings.action_mark_as_unread),
                onConfirm = {
                    scope.launch {
                        markUnread(updateChapter, ids.toList())
                        selectedChapterIds = selectedChapterIds - ids
                        pendingUnreadIds = null
                    }
                },
                onDismiss = { pendingUnreadIds = null },
            )
        }
    }
}

/**
 * 一级页的漫画行：方封 + 标题 + 篇目数与最近读完时间，点整行下钻到篇目列表，
 * 点封面直接进这部漫画的详情页。
 */
@Composable
private fun LocalReviewMangaRow(
    group: MangaGroup,
    coverModel: Any?,
    onClick: () -> Unit,
    onOpenManga: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MyListHorizontalPadding, vertical = MyListRowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MyListCover(
            model = coverModel,
            contentDescription = group.mangaTitle,
            modifier = Modifier.width(MyListCoverWidth),
            aspectRatio = MY_LIST_MANGA_COVER_ASPECT_RATIO,
            onClick = onOpenManga,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MyListContentGap),
        ) {
            Text(
                text = group.mangaTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    MR.strings.local_read_review_group_subtitle,
                    group.chapters.size,
                    formatListTime(group.chapters.first().lastReadAt),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 二级页的篇目行：封面 + 篇目名 + 读完时间，点开阅读器，长按多选。
 */
@Composable
private fun LocalReviewChapterRow(
    item: LocalReadReviewItem,
    title: MyListChapterTitle?,
    coverModel: Any?,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelection() else onClick() },
                onLongClick = onToggleSelection,
            )
            .padding(horizontal = MyListHorizontalPadding, vertical = MyListRowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MyListCover(
            model = coverModel,
            contentDescription = title?.primary ?: item.chapterName,
            modifier = Modifier.width(MyListCoverWidth),
            aspectRatio = MY_LIST_COVER_ASPECT_RATIO,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MyListContentGap),
        ) {
            Text(
                text = title?.primary ?: item.chapterName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            // 「译名与原名」时补一行原名，与详情页同一屏能看到两种名字一致。
            title?.secondary?.let { original ->
                Text(
                    text = original,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatListTime(item.lastReadAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selectionMode) {
            MyListSelectionIndicator(selected = selected)
        }
    }
}

/**
 * 一次取回一级页全部漫画，返回 mangaId → Manga 的映射。查不到的缺席，行上显示占位封面。
 */
@Composable
private fun rememberMangaMap(mangaIds: List<Long>): Map<Long, Manga> {
    val repository = remember { Injekt.get<MangaRepository>() }
    var mangaById by remember { mutableStateOf(emptyMap<Long, Manga>()) }
    LaunchedEffect(mangaIds) {
        mangaById = withIOContext {
            mangaIds
                .map { id -> async { runCatching { repository.getMangaById(id) }.getOrNull() } }
                .awaitAll()
                .filterNotNull()
                .associateBy { it.id }
        }
    }
    return mangaById
}

/**
 * 二级页篇目封面参数：读到的章节已带 url/version 等字段，直接建映射。
 * 与标记/好本子清单使用同一套版本算法，保证 Coil 缓存键一致。
 */
@Composable
private fun rememberChapterCovers(chapters: List<LocalReadReviewItem>): Map<Long, LocalChapterCover> {
    return remember(chapters) {
        chapters.associate { chapter ->
            chapter.chapterId to LocalChapterCover(
                chapterId = chapter.chapterId,
                chapterUrl = chapter.chapterUrl,
                version = chapter.version xor chapter.dateUpload xor chapter.lastModifiedAt,
            )
        }
    }
}

/**
 * 与标记/好本子清单同一套导出格式：表头 + 按漫画分组的缩进列表。
 */
@Composable
private fun exportTextOf(
    listTitle: String,
    groups: List<MangaGroup>,
    titleByChapterId: Map<Long, MyListChapterTitle?>,
): String {
    return buildMyListShareText(
        header = stringResource(
            MR.strings.my_lists_share_header,
            listTitle,
            groups.sumOf { it.chapters.size },
        ),
        groups = groups.map { group ->
            MyListShareGroup(
                mangaTitle = group.mangaTitle,
                // 导出的名字跟页面上看到的保持一致，拿到文件才知道指的是哪一篇。
                entries = group.chapters.map { chapter ->
                    titleByChapterId[chapter.chapterId]?.primary ?: chapter.chapterName
                },
            )
        },
    )
}

@Composable
private fun ConfirmDialog(
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.are_you_sure)) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

/**
 * 从清单跳回漫画详情。清单读的是数据库快照，漫画可能已经被移出本地库，
 * 跳之前先确认一次，避免点进去是个空壳页。
 */
private fun openMangaScreen(
    navigator: Navigator,
    scope: CoroutineScope,
    context: android.content.Context,
    mangaId: Long,
) {
    scope.launch {
        val repository = Injekt.get<MangaRepository>()
        val exists = runCatching { withIOContext { repository.getMangaById(mangaId) } }.isSuccess
        if (exists) {
            navigator.push(MangaScreen(mangaId))
        } else {
            context.toast(MR.strings.marks_list_manga_missing)
        }
    }
}

private suspend fun markUnread(updateChapter: UpdateChapter, chapterIds: List<Long>) {
    chapterIds.forEach { chapterId ->
        updateChapter.await(
            ChapterUpdate(
                id = chapterId,
                read = false,
                lastPageRead = 0,
                markedReadAt = 0,
            ),
        )
    }
}

/** 按漫画归好的一组已读篇目，组内已按读完时间倒序。 */
private data class MangaGroup(
    val mangaId: Long,
    val mangaTitle: String,
    val chapters: List<LocalReadReviewItem>,
)

private data class LocalReadReviewItem(
    val chapterId: Long,
    val mangaId: Long,
    val mangaTitle: String,
    val chapterUrl: String,
    val chapterName: String,
    val chapterTranslatedName: String?,
    val totalPages: Long,
    val dateUpload: Long,
    val lastModifiedAt: Long,
    val version: Long,
    val lastReadAt: Long,
)

private fun mapReadLocalChapter(
    chapter_id: Long,
    manga_id: Long,
    manga_title: String,
    chapter_url: String,
    chapter_name: String,
    chapter_translated_name: String?,
    total_pages: Long,
    date_upload: Long,
    last_modified_at: Long,
    version: Long,
    marked_read_at: Long,
) = LocalReadReviewItem(
    chapterId = chapter_id,
    mangaId = manga_id,
    mangaTitle = manga_title,
    chapterUrl = chapter_url,
    chapterName = chapter_name,
    chapterTranslatedName = chapter_translated_name,
    totalPages = total_pages,
    dateUpload = date_upload,
    lastModifiedAt = last_modified_at,
    version = version,
    lastReadAt = marked_read_at,
)
