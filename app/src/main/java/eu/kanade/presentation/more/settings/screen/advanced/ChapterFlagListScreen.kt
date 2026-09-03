package eu.kanade.presentation.more.settings.screen.advanced

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.PlaylistRemove
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DeleteLocalEntriesDialog
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.mylists.MY_LIST_COVER_ASPECT_RATIO
import eu.kanade.presentation.mylists.MyListChapterTitle
import eu.kanade.presentation.mylists.MyListCover
import eu.kanade.presentation.mylists.MyListEmptyState
import eu.kanade.presentation.mylists.MyListGridHorizontalSpacing
import eu.kanade.presentation.mylists.MyListHorizontalPadding
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
import eu.kanade.tachiyomi.data.local.LocalEntryDeletionService
import eu.kanade.tachiyomi.data.manga.ChapterFlagStore
import eu.kanade.tachiyomi.data.manga.GoodDoujinStore
import eu.kanade.tachiyomi.data.manga.MangaMark
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.withLocalChapterDisplayMode
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalChapterCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Which per-chapter flag list a screen belongs to. Duplicates and good doujins
 * share the exact same two-level layout, only the labels differ.
 */
enum class ChapterFlagListType(
    val titleRes: StringResource,
    val emptyRes: StringResource,
    val clearConfirmRes: StringResource,
    val chapterCountRes: StringResource,
) {
    DUPLICATES(
        MR.strings.marks_list_title,
        MR.strings.marks_list_empty,
        MR.strings.marks_list_clear_confirm,
        MR.strings.marks_list_chapter_count,
    ),
    GOOD_DOUJINS(
        MR.strings.good_doujin_list_title,
        MR.strings.good_doujin_list_empty,
        MR.strings.good_doujin_list_clear_confirm,
        MR.strings.good_doujin_list_chapter_count,
    ),
}

/**
 * Level one of a chapter flag list: one row per manga. Tap a row to see its
 * chapters, or use the row menu to jump straight to the manga page.
 */
class ChapterFlagListScreen(
    private val type: ChapterFlagListType,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val store = rememberStore(type)
        val marks by store.marks.collectAsState()
        val grouped = remember(marks) {
            marks.groupBy { it.mangaId }
                .map { (_, mangaMarks) ->
                    MangaGroup(
                        mangaId = mangaMarks.first().mangaId,
                        mangaTitle = mangaMarks.first().mangaTitle,
                        marks = mangaMarks.sortedByDescending { it.markedAt },
                    )
                }
                .sortedByDescending { it.marks.first().markedAt }
        }
        // 一次把这页要用的漫画与篇目封面参数全取回来，替代原先每行各自查一次的写法。
        val visualByChapterId = rememberChapterVisuals(grouped)
        var query by remember { mutableStateOf<String?>(null) }
        // 搜索命中的是「篇目」而非「漫画」：漫画名命中时整组保留，篇目的原名或译名命中时
        // 只留下命中的那一张卡。译名与原名一视同仁——页面上显示译名时也得能按原名搜到。
        val visibleGroups = remember(grouped, query) {
            val q = myListSearchQuery(query)
            if (q.isEmpty()) {
                grouped
            } else {
                grouped.mapNotNull { group ->
                    val matched = group.marks.filter { mark ->
                        myListEntryMatchesQuery(
                            query = q,
                            mangaTitle = group.mangaTitle,
                            chapterName = mark.chapterName,
                            translatedName = visualByChapterId[mark.chapterId]?.translatedName,
                        )
                    }
                    if (matched.isEmpty()) null else group.copy(marks = matched)
                }
            }
        }
        val localDisplayMode = rememberLocalChapterDisplayMode()
        // 显示模式按「漫画」取：标记清单里可能夹着非本地条目，它们有自己的详情页设置，
        // 不该被本地库那份统一设置覆盖。缺的（正常就是本地）回退到统一设置。
        val mangaIds = remember(grouped) { grouped.map { it.mangaId } }
        val flagsByMangaId = rememberMangaFlagsByMangaId(mangaIds, localDisplayMode)
        val isLocalByMangaId = remember(flagsByMangaId) {
            flagsByMangaId.entries.associate { (mangaId, flags) -> mangaId to flags.isLocal }
        }
        val titleByChapterId = remember(visibleGroups, flagsByMangaId, visualByChapterId) {
            visibleGroups
                .flatMap { it.marks }
                .associate { mark ->
                    mark.chapterId to myListChapterTitle(
                        chapterName = mark.chapterName,
                        translatedName = visualByChapterId[mark.chapterId]?.translatedName,
                        displayMode = flagsByMangaId[mark.mangaId]?.displayMode ?: localDisplayMode,
                    )
                }
        }
        var showClearConfirm by remember { mutableStateOf(false) }
        // 多选粒度是「篇目」：长按卡片勾选单个，长按分组头一键勾选该组下全部本地篇目。
        // 非本地条目没有磁盘文件可删，置灰且不可勾选。
        var selectedChapterIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
        var pendingRemovalChapterIds by remember { mutableStateOf<Set<Long>?>(null) }
        val selectedMarks = remember(visibleGroups, selectedChapterIds) {
            visibleGroups.flatMap { it.marks }.filter { it.chapterId in selectedChapterIds }
        }
        val deletableMarks = remember(selectedMarks, isLocalByMangaId) {
            selectedMarks.filter { isLocalByMangaId[it.mangaId] == true }
        }
        var pendingDeletion by remember { mutableStateOf<Set<Long>?>(null) }
        var deletionInProgress by remember { mutableStateOf(false) }
        val deletionService = remember { Injekt.get<LocalEntryDeletionService>() }
        val listTitle = stringResource(type.titleRes)
        // 没选就导出整张清单，选了就只导出选中的那些，省得导出完还得自己删。
        val exportGroups = remember(visibleGroups, selectedChapterIds) {
            if (selectedChapterIds.isEmpty()) {
                visibleGroups
            } else {
                visibleGroups.mapNotNull { group ->
                    val picked = group.marks.filter { it.chapterId in selectedChapterIds }
                    if (picked.isEmpty()) null else group.copy(marks = picked)
                }
            }
        }
        val exportText = exportTextOf(listTitle, exportGroups, titleByChapterId)
        val exportList = rememberMyListExportLauncher(
            filename = when (type) {
                ChapterFlagListType.DUPLICATES -> "mihon_marks_list.txt"
                ChapterFlagListType.GOOD_DOUJINS -> "mihon_good_doujins.txt"
            },
            text = exportText,
        )
        val gridState = rememberLazyGridState()

        LaunchedEffect(visibleGroups) {
            val availableIds = visibleGroups.flatMapTo(mutableSetOf()) { group ->
                group.marks.map { it.chapterId }
            }
            selectedChapterIds = selectedChapterIds.intersect(availableIds)
        }
        BackHandler(enabled = selectedChapterIds.isNotEmpty()) {
            selectedChapterIds = emptySet()
        }

        Scaffold(
            topBar = { scrollBehavior ->
                SearchToolbar(
                    searchQuery = query,
                    onChangeSearchQuery = { query = it },
                    placeholderText = stringResource(MR.strings.marks_list_search_hint),
                    titleContent = {
                        AppBarTitle(
                            title = listTitle,
                            subtitle = stringResource(type.chapterCountRes, marks.size),
                        )
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            listOfNotNull(
                                AppBar.Action(
                                    title = stringResource(MR.strings.export),
                                    icon = Icons.Outlined.FileUpload,
                                    onClick = exportList,
                                    enabled = exportText.isNotEmpty(),
                                ),
                                // 图标用扫帚而不是垃圾桶：它只清列表，不碰磁盘文件。
                                // 多选态下 SearchToolbar 会整片换成批量操作，这里不会渲染，
                                // 不必再判断选中数。
                                AppBar.Action(
                                    title = stringResource(MR.strings.marks_list_clear),
                                    icon = Icons.Outlined.DeleteSweep,
                                    onClick = { showClearConfirm = true },
                                ),
                            ),
                        )
                    },
                    actionModeCounter = selectedChapterIds.size,
                    onCancelActionMode = { selectedChapterIds = emptySet() },
                    actionModeActions = {
                        AppBarActions(
                            buildList {
                                // 导出在多选时导选中项、不选时导全表，所以两种态都得在。
                                add(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.export),
                                        icon = Icons.Outlined.FileUpload,
                                        onClick = exportList,
                                        enabled = exportText.isNotEmpty(),
                                    ),
                                )
                                add(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.marks_list_remove),
                                        icon = Icons.Outlined.PlaylistRemove,
                                        onClick = { pendingRemovalChapterIds = selectedChapterIds },
                                    ),
                                )
                                // 删除本地文件只给标记清单：好本子清单的条目都在库里管着，
                                // 误删代价更高，暂不开放。
                                //
                                // 放在最右角落：它是这排按钮里唯一不可逆的，夹在中间容易被
                                // 顺手点到，挪到末端与「仅移除列表项」拉开距离。
                                if (type == ChapterFlagListType.DUPLICATES) {
                                    add(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_delete_local_files),
                                            icon = Icons.Outlined.Delete,
                                            iconTint = MaterialTheme.colorScheme.error,
                                            // 只跟选中数挂钩。「是否本地」要查库才知道，若拿它
                                            // 当开关，数据没回来前按钮一直是灰的，看着像没做。
                                            // 到底哪些能删，交给确认弹窗按最新数据过滤。
                                            enabled = selectedChapterIds.isNotEmpty(),
                                            onClick = { pendingDeletion = selectedChapterIds },
                                        ),
                                    )
                                }
                            },
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            if (marks.isEmpty()) {
                MyListEmptyState(stringRes = type.emptyRes, contentPadding = contentPadding)
            } else if (visibleGroups.isEmpty()) {
                MyListEmptyState(stringRes = MR.strings.no_results_found, contentPadding = contentPadding)
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    FastScrollLazyVerticalGrid(
                        columns = GridCells.Adaptive(96.dp),
                        modifier = Modifier.fillMaxSize(),
                        state = gridState,
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(MyListGridHorizontalSpacing),
                    ) {
                        visibleGroups.forEach { group ->
                            item(
                                key = "manga-${group.mangaId}",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                ChapterFlagGroupHeader(
                                    title = group.mangaTitle,
                                    count = group.marks.size,
                                    onClick = { openManga(navigator, scope, context, group.mangaId) },
                                    onLongClick = {
                                        // 一键勾选该组下所有本地篇目：非本地条目没有文件可删。
                                        val localIds = group.marks
                                            .filter { isLocalByMangaId[it.mangaId] == true }
                                            .mapTo(mutableSetOf()) { it.chapterId }
                                        if (localIds.isNotEmpty()) {
                                            // 多选是在全部条目上操作，继续保留搜索过滤会让人
                                            // 误以为选中的就是搜索结果里的那些。
                                            query = null
                                            selectedChapterIds = selectedChapterIds + localIds
                                        }
                                    },
                                )
                            }
                            group.marks.forEach { mark ->
                                item(key = mark.chapterId) {
                                    val selectable = isLocalByMangaId[mark.mangaId] != false
                                    ChapterFlagGridCard(
                                        mark = mark,
                                        title = titleByChapterId[mark.chapterId],
                                        coverModel = visualByChapterId[mark.chapterId],
                                        selectionMode = selectedChapterIds.isNotEmpty(),
                                        selected = mark.chapterId in selectedChapterIds,
                                        selectable = selectable,
                                        onClick = { openChapterReader(context, scope, mark.mangaId, mark.chapterId) },
                                        onToggleSelection = {
                                            // 从搜索结果里长按进入多选时一并退出搜索，避免
                                            // 选中的只是过滤后的子集却以为是全清单。
                                            if (selectedChapterIds.isEmpty()) query = null
                                            selectedChapterIds = selectedChapterIds.toMutableSet().apply {
                                                if (!add(mark.chapterId)) remove(mark.chapterId)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // 网格顶部悬浮一条「当前分组」头：Compose 的 LazyVerticalGrid 没有 stickyHeader，
                    // 这里用 firstVisibleItemIndex 派生当前漫画，等效出吸顶头。
                    StickyGroupHeaderOverlay(
                        groups = visibleGroups,
                        gridState = gridState,
                        onClick = { group -> openManga(navigator, scope, context, group.mangaId) },
                    )
                }
            }
        }

        if (showClearConfirm) {
            ConfirmDialog(
                text = stringResource(type.clearConfirmRes),
                confirmText = stringResource(MR.strings.marks_list_clear),
                onConfirm = {
                    scope.launch {
                        store.clear()
                        showClearConfirm = false
                    }
                },
                onDismiss = { showClearConfirm = false },
            )
        }

        pendingRemovalChapterIds?.let { ids ->
            ConfirmDialog(
                text = stringResource(MR.strings.marks_list_remove_selected_entries_confirm, ids.size),
                confirmText = stringResource(MR.strings.marks_list_remove),
                onConfirm = {
                    scope.launch {
                        val marks = grouped.flatMap { it.marks }.filter { it.chapterId in ids }
                        store.setAll(marks, false)
                        selectedChapterIds = selectedChapterIds - ids
                        pendingRemovalChapterIds = null
                    }
                },
                onDismiss = { pendingRemovalChapterIds = null },
            )
        }

        pendingDeletion?.let { ids ->
            // 过滤放在弹窗里做：按钮只管有没有选中，「是否本地」在用户确认的这一刻
            // 才按最新数据判定，避免异步加载导致的误判。全是非本地时这里为空，
            // 弹窗的确认按钮会自动禁用，顺便把这种情况摆到用户面前。
            val marks = selectedMarks.filter { mark ->
                mark.chapterId in ids && isLocalByMangaId[mark.mangaId] == true
            }
            // 同一部漫画下可能同时有本地与非本地条目，删掉本地的后分组还在。
            val affectedGroups = marks.groupBy { it.mangaId }
            val groupRemains = affectedGroups.any { (mangaId, groupMarks) ->
                groupMarks.size < (grouped.firstOrNull { it.mangaId == mangaId }?.marks?.size ?: 0)
            }
            val extraWarning = buildString {
                appendLine(stringResource(MR.strings.local_delete_unmarked_safe))
                if (groupRemains) {
                    append(stringResource(MR.strings.local_delete_group_remains))
                }
            }.trim()
            DeleteLocalEntriesDialog(
                title = stringResource(
                    MR.strings.local_delete_marked_chapters_title,
                    affectedGroups.keys
                        .mapNotNull { mangaId -> grouped.firstOrNull { it.mangaId == mangaId }?.mangaTitle }
                        .joinToString("、")
                        .ifBlank { listTitle },
                    marks.size,
                ),
                entryNames = marks.map { it.chapterName },
                extraWarning = extraWarning,
                inProgress = deletionInProgress,
                onDismissRequest = { if (!deletionInProgress) pendingDeletion = null },
                onConfirm = {
                    deletionInProgress = true
                    scope.launch {
                        val entries = marks.map { mark ->
                            LocalEntryDeletionService.ChapterTarget(
                                id = mark.chapterId,
                                mangaId = mark.mangaId,
                                mangaTitle = mark.mangaTitle,
                                name = mark.chapterName,
                            )
                        }
                        val result = deletionService.deleteChapters(entries)
                        deletionInProgress = false
                        pendingDeletion = null
                        // 删掉的篇目其标记已一并清除，marks 流更新后 LaunchedEffect 会自动
                        // 把失效 id 从选中集合里剔除，这里只需退出多选态。
                        selectedChapterIds = emptySet()
                        context.toast(
                            when {
                                result.deleted == 0 -> context.stringResource(MR.strings.local_delete_failed)
                                result.failed.isNotEmpty() -> context.stringResource(
                                    MR.strings.local_delete_partial,
                                    result.deleted,
                                    result.failed.size,
                                )
                                else -> context.stringResource(MR.strings.local_delete_success, result.deleted)
                            },
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun rememberStore(type: ChapterFlagListType): ChapterFlagStore {
    return remember(type) {
        when (type) {
            ChapterFlagListType.DUPLICATES -> Injekt.get<MangaMarkStore>()
            ChapterFlagListType.GOOD_DOUJINS -> Injekt.get<GoodDoujinStore>()
        }
    }
}

/** 按漫画归好的一组标记，组内已按标记时间倒序。 */
private data class MangaGroup(
    val mangaId: Long,
    val mangaTitle: String,
    val marks: List<MangaMark>,
)

/**
 * 篇目封面所需的参数（url 与版本）与译名都不在 [MangaMark] 里，按页面上出现的全部漫画整批取
 * 一次，返回 chapterId → [ChapterVisual]。查不到的篇目缺席，卡片上会显示占位封面并回退到原名。
 * 与已读复查页使用同一套版本算法，保证 Coil 缓存键一致。
 */
@Composable
private fun rememberChapterVisuals(groups: List<MangaGroup>): Map<Long, ChapterVisual> {
    val repository = remember { Injekt.get<ChapterRepository>() }
    var visualByChapterId by remember { mutableStateOf(emptyMap<Long, ChapterVisual>()) }
    val mangaIds = remember(groups) { groups.map { it.mangaId } }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(mangaIds, reload) {
        val visuals = withIOContext {
            mangaIds
                .map { id -> async { runCatching { repository.getChapterByMangaId(id) }.getOrDefault(emptyList()) } }
                .awaitAll()
                .flatten()
                .associate { chapter ->
                    chapter.id to ChapterVisual(
                        cover = LocalChapterCover(
                            chapterId = chapter.id,
                            chapterUrl = chapter.url,
                            version = chapter.version xor chapter.dateUpload xor chapter.lastModifiedAt,
                        ),
                        translatedName = chapter.translatedName,
                    )
                }
        }
        visualByChapterId = visuals
    }
    return visualByChapterId
}

/** 一张卡片要用的篇目信息：封面参数 + 译名（没有则为 null）。 */
private data class ChapterVisual(
    val cover: LocalChapterCover,
    val translatedName: String?,
)

/** 一部漫画在本页需要的两项信息：标题显示模式，以及它是否来自本地源。 */
private data class MangaFlags(
    val displayMode: Long,
    val isLocal: Boolean,
)

/**
 * 每部漫画自己的篇目标题显示模式，外加「是否本地」。
 *
 * 详情页改的是这部漫画的 `displayMode`；本地漫画那一份由库统一设置驱动，
 * [Manga.withLocalChapterDisplayMode] 在这里把统一设置套上去，与阅读器、详情页同源。
 *
 * 是否本地顺带一起取：只有本地篇目有磁盘文件可删，标记清单里夹着的非本地条目在多选时
 * 要置灰且不可勾选。合并进同一次批量查询，避免多打一轮数据库。
 */
@Composable
private fun rememberMangaFlagsByMangaId(
    mangaIds: List<Long>,
    localDisplayMode: Long,
): Map<Long, MangaFlags> {
    val repository = remember { Injekt.get<MangaRepository>() }
    var flagsByMangaId by remember { mutableStateOf(emptyMap<Long, MangaFlags>()) }
    // 设置一变就重算，清单不用重进才跟着换显示。
    LaunchedEffect(mangaIds, localDisplayMode) {
        flagsByMangaId = withIOContext {
            mangaIds
                .map { id -> async { runCatching { repository.getMangaById(id) }.getOrNull() } }
                .awaitAll()
                .filterNotNull()
                .associate { manga ->
                    manga.id to MangaFlags(
                        displayMode = manga.withLocalChapterDisplayMode(localDisplayMode).displayMode,
                        isLocal = manga.source == LocalSource.ID,
                    )
                }
        }
    }
    return flagsByMangaId
}

/**
 * 网格里的分组头：占满一整行，点它打开漫画页，长按一键勾选该组下全部本地篇目。
 */
@Composable
private fun ChapterFlagGroupHeader(
    title: String,
    count: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = MyListHorizontalPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
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
}

/**
 * 网格里的篇目卡片：点开阅读器，长按进入多选。
 *
 * [selectable] 为 false 的是非本地条目：进入多选后置灰且不响应勾选，因为它们没有磁盘文件可删。
 * 未进入多选时照常可点开阅读。
 */
@Composable
private fun ChapterFlagGridCard(
    mark: MangaMark,
    title: MyListChapterTitle?,
    coverModel: ChapterVisual?,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    selectable: Boolean = true,
) {
    val shape = MaterialTheme.shapes.extraSmall
    val primaryTitle = title?.primary ?: mark.chapterName
    val dimmed = selectionMode && !selectable
    Column(
        modifier = Modifier
            .padding(horizontal = 3.dp, vertical = 5.dp)
            .then(if (dimmed) Modifier.alpha(0.38f) else Modifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(
                    onClick = {
                        when {
                            dimmed -> Unit
                            selectionMode -> onToggleSelection()
                            else -> onClick()
                        }
                    },
                    onLongClick = { if (selectable) onToggleSelection() },
                ),
        ) {
            MyListCover(
                model = coverModel?.cover,
                contentDescription = primaryTitle,
                modifier = Modifier.fillMaxWidth(),
                aspectRatio = MY_LIST_COVER_ASPECT_RATIO,
            )
            if (selectionMode) {
                // 封面本身亮暗不定，勾选圈垫一层半透明底，避免看不清。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(shape)
                        .background(Color.Black.copy(alpha = 0.68f)),
                ) {
                    MyListSelectionIndicator(
                        selected = selected,
                        tint = Color.White,
                        modifier = Modifier.padding(6.dp),
                    )
                }
            }
        }
        Text(
            text = primaryTitle,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, top = 4.dp, end = 2.dp),
        )
        // 「译名与原名」时补一行原名，卡片窄，只给一行。
        title?.secondary?.let { original ->
            Text(
                text = original,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp, end = 2.dp),
            )
        }
    }
}

/**
 * 悬浮在网格上方的「当前分组」头，等效吸顶头。Compose 的 LazyVerticalGrid 没有 stickyHeader，
 * 这里用 firstVisibleItemIndex 反查当前漫画，滚动中实时更新，点击仍打开漫画页。
 */
@Composable
private fun StickyGroupHeaderOverlay(
    groups: List<MangaGroup>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onClick: (MangaGroup) -> Unit,
) {
    val currentGroup = remember(groups, gridState.firstVisibleItemIndex) {
        // 网格 item 的 index 是「分组头 + 其下所有卡片」线性铺开的，
        // 二分法找到第一个「分组头 index」不晚于当前可见首项的分组。
        val index = gridState.firstVisibleItemIndex
        // 每个分组占 1 + marks.size 个 item；算出各分组头的起始 index。
        val headerStart = IntArray(groups.size)
        var acc = 0
        for (i in groups.indices) {
            headerStart[i] = acc
            acc += 1 + groups[i].marks.size
        }
        var lo = 0
        var hi = groups.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (headerStart[mid] <= index) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found >= 0) groups[found] else null
    }

    val group = currentGroup ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick(group) }
            .padding(horizontal = MyListHorizontalPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.mangaTitle,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = group.marks.size.toString(),
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
}

/**
 * 两个清单共用的二次确认弹窗。危险操作一律先确认。
 */
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
 * 按漫画分组的导出文本。在组合期求值，因为相对时间与表头文案都要走 stringResource；
 * 这一段只在 marks / 标题变化引起的重组时重算，滚动不会触发。
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
            groups.sumOf { it.marks.size },
        ),
        groups = groups.map { group ->
            MyListShareGroup(
                mangaTitle = group.mangaTitle,
                // 导出跟着页面上的显示走，拿到文件的人才知道指的是哪一篇。
                entries = group.marks.map { mark ->
                    val name = titleByChapterId[mark.chapterId]?.primary ?: mark.chapterName
                    "$name  (${formatListTime(mark.markedAt)})"
                },
            )
        },
    )
}

private fun openManga(
    navigator: cafe.adriel.voyager.navigator.Navigator,
    scope: kotlinx.coroutines.CoroutineScope,
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

private fun openChapterReader(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    mangaId: Long,
    chapterId: Long,
) {
    scope.launch {
        val mangaRepository = Injekt.get<MangaRepository>()
        val chapterRepository = Injekt.get<ChapterRepository>()
        val mangaExists = runCatching { withIOContext { mangaRepository.getMangaById(mangaId) } }.isSuccess
        val chapter = runCatching { withIOContext { chapterRepository.getChapterById(chapterId) } }.getOrNull()
        when {
            !mangaExists -> context.toast(MR.strings.marks_list_manga_missing)
            // The reader silently falls back to the first chapter when the requested one is gone,
            // and a stale mark whose ids no longer resolve to the same manga would land on the
            // wrong comic. Only launch the reader for a chapter that still exists and belongs to
            // the manga the mark was recorded against.
            chapter == null || chapter.mangaId != mangaId -> context.toast(MR.strings.chapter_not_found)
            else -> context.startActivity(ReaderActivity.newIntent(context, mangaId, chapterId))
        }
    }
}
