package eu.kanade.presentation.manga

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastMap
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.components.BottomNavFabLift
import eu.kanade.presentation.components.RandomGestureFab
import eu.kanade.presentation.components.rememberAtListEnd
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterHeader
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.MangaActionRow
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterGridItem
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MangaInfoBox
import eu.kanade.presentation.manga.components.MangaTitleSelectionController
import eu.kanade.presentation.manga.components.MangaToolbar
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.manga.GoodDoujinStore
import eu.kanade.tachiyomi.data.manga.MangaMark
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.components.material.AutoDismissSnackbarHost
import tachiyomi.presentation.core.components.material.FabPosition
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.image.LocalChapterCover
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs
import kotlin.time.Instant
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

@Composable
fun MangaScreen(
    state: MangaViewModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    isTabletUi: Boolean,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onClickHome: (() -> Unit)? = null,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    // For tags menu
    onTagSearch: (String) -> Unit,

    onRandomClicked: (() -> Unit)? = null,
    onClickRandomGoodDoujin: (() -> Unit)? = null,
    onAudioClicked: (() -> Unit)? = null,
    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,
    onLocalSearch: (query: String) -> Unit,
    onOpenSource: () -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditFetchIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onChapterTitleTranslationsClicked: (() -> Unit)?,
    onImportLocalChaptersClicked: (() -> Unit)?,
    onClearHistoryClicked: () -> Unit,
    onEditNotesClicked: () -> Unit,
    onDeleteLocalMangaClicked: (() -> Unit)? = null,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiGoodDoujinClicked: (List<Chapter>, marked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMarkFollowingAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,
    onDeleteLocalChaptersClicked: (List<Chapter>) -> Unit,
    onMoveChaptersClicked: (List<Chapter>) -> Unit,

    // For chapter swipe
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,
    onReorderChapters: (List<Long>) -> Unit,
    onEditChapterTranslatedTitle: (ChapterList.Item) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onCopyTagToClipboard: (tag: String) -> Unit = {
        if (it.isNotEmpty()) {
            context.copyToClipboard(it, it)
        }
    }

    val markStore = remember { Injekt.get<MangaMarkStore>() }
    val marks by markStore.marks.collectAsState()
    val markedChapterIds = remember(marks) { marks.mapTo(mutableSetOf()) { it.chapterId } }
    val goodDoujinStore = remember { Injekt.get<GoodDoujinStore>() }
    val goodDoujinMarks by goodDoujinStore.marks.collectAsState()
    val goodDoujinChapterIds = remember(goodDoujinMarks) {
        goodDoujinMarks.mapTo(mutableSetOf()) { it.chapterId }
    }
    val basePreferences = remember { Injekt.get<BasePreferences>() }
    val chapterCoversEnabled by basePreferences.localChapterCoversEnabled.collectPreferenceAsState()
    val chapterCoverGridEnabled by basePreferences.localChapterCoverGridEnabled.collectPreferenceAsState()
    val chapterLayoutAvailable = state.manga.isLocal() && chapterCoversEnabled
    // Duplicate marks only make sense for the local library.
    val onToggleMarkClicked: ((List<Chapter>) -> Unit)? = if (state.manga.isLocal()) {
        { chapters ->
            // Batch semantics: the bottom bar shows "unmark" only when every selected chapter is
            // already marked, and "mark" otherwise. Follow that intent instead of toggling each
            // chapter individually, which would unmark the ones already marked and mark the rest,
            // making it impossible to keep several chapters flagged at once.
            val allMarked = chapters.all { it.id in markedChapterIds }
            val chapterMarks = chapters.map { chapter ->
                MangaMark(
                    mangaId = state.manga.id,
                    mangaTitle = state.manga.title,
                    chapterId = chapter.id,
                    chapterName = chapter.name,
                    markedAt = System.currentTimeMillis(),
                )
            }
            scope.launch {
                markStore.setAll(chapterMarks, marked = !allMarked)
            }
        }
    } else {
        null
    }
    if (!isTabletUi) {
        MangaScreenSmallImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            nextUpdate = nextUpdate,
            chapterSwipeStartAction = chapterSwipeStartAction,
            chapterSwipeEndAction = chapterSwipeEndAction,
            navigateUp = navigateUp,
            onClickHome = onClickHome,
            onChapterClicked = onChapterClicked,
            onDownloadChapter = onDownloadChapter,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTrackingClicked = onTrackingClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onRandomClicked = onRandomClicked,
            onClickRandomGoodDoujin = onClickRandomGoodDoujin,
            onAudioClicked = onAudioClicked,
            onFilterClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueReading = onContinueReading,
            onSearch = onSearch,
            onLocalSearch = onLocalSearch,
            onOpenSource = onOpenSource,
            onCoverClicked = onCoverClicked,
            onShareClicked = onShareClicked,
            onDownloadActionClicked = onDownloadActionClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditIntervalClicked = onEditFetchIntervalClicked,
            onMigrateClicked = onMigrateClicked,
            onChapterTitleTranslationsClicked = onChapterTitleTranslationsClicked,
            onImportLocalChaptersClicked = onImportLocalChaptersClicked,
            onClearHistoryClicked = onClearHistoryClicked,
            onEditNotesClicked = onEditNotesClicked,
            onDeleteLocalMangaClicked = onDeleteLocalMangaClicked,
            chapterLayoutAvailable = chapterLayoutAvailable,
            chapterLayoutGridEnabled = chapterCoverGridEnabled,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiGoodDoujinClicked = onMultiGoodDoujinClicked,
            onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
            onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
            onMarkFollowingAsReadClicked = onMarkFollowingAsReadClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
            onDeleteLocalChaptersClicked = onDeleteLocalChaptersClicked,
            onMoveChaptersClicked = onMoveChaptersClicked,
            onChapterSwipe = onChapterSwipe,
            onReorderChapters = onReorderChapters,
            onEditChapterTranslatedTitle = onEditChapterTranslatedTitle,
            onChapterSelected = onChapterSelected,
            onAllChapterSelected = onAllChapterSelected,
            onInvertSelection = onInvertSelection,
            markedChapterIds = markedChapterIds,
            goodDoujinChapterIds = goodDoujinChapterIds,
            onToggleMarkClicked = onToggleMarkClicked,
        )
    } else {
        MangaScreenLargeImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            chapterSwipeStartAction = chapterSwipeStartAction,
            chapterSwipeEndAction = chapterSwipeEndAction,
            nextUpdate = nextUpdate,
            navigateUp = navigateUp,
            onClickHome = onClickHome,
            onChapterClicked = onChapterClicked,
            onDownloadChapter = onDownloadChapter,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTrackingClicked = onTrackingClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onRandomClicked = onRandomClicked,
            onClickRandomGoodDoujin = onClickRandomGoodDoujin,
            onAudioClicked = onAudioClicked,
            onFilterButtonClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueReading = onContinueReading,
            onSearch = onSearch,
            onLocalSearch = onLocalSearch,
            onOpenSource = onOpenSource,
            onCoverClicked = onCoverClicked,
            onShareClicked = onShareClicked,
            onDownloadActionClicked = onDownloadActionClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditIntervalClicked = onEditFetchIntervalClicked,
            onMigrateClicked = onMigrateClicked,
            onChapterTitleTranslationsClicked = onChapterTitleTranslationsClicked,
            onImportLocalChaptersClicked = onImportLocalChaptersClicked,
            onClearHistoryClicked = onClearHistoryClicked,
            onEditNotesClicked = onEditNotesClicked,
            onDeleteLocalMangaClicked = onDeleteLocalMangaClicked,
            chapterLayoutAvailable = chapterLayoutAvailable,
            chapterLayoutGridEnabled = chapterCoverGridEnabled,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiGoodDoujinClicked = onMultiGoodDoujinClicked,
            onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
            onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
            onMarkFollowingAsReadClicked = onMarkFollowingAsReadClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
            onDeleteLocalChaptersClicked = onDeleteLocalChaptersClicked,
            onMoveChaptersClicked = onMoveChaptersClicked,
            onChapterSwipe = onChapterSwipe,
            onReorderChapters = onReorderChapters,
            onEditChapterTranslatedTitle = onEditChapterTranslatedTitle,
            onChapterSelected = onChapterSelected,
            onAllChapterSelected = onAllChapterSelected,
            onInvertSelection = onInvertSelection,
            markedChapterIds = markedChapterIds,
            goodDoujinChapterIds = goodDoujinChapterIds,
            onToggleMarkClicked = onToggleMarkClicked,
        )
    }
}

@Composable
private fun MangaScreenSmallImpl(
    state: MangaViewModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onClickHome: (() -> Unit)? = null,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    // For tags menu
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,

    onRandomClicked: (() -> Unit)? = null,
    onClickRandomGoodDoujin: (() -> Unit)? = null,
    onAudioClicked: (() -> Unit)? = null,
    onFilterClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,
    onLocalSearch: (query: String) -> Unit,
    onOpenSource: () -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onChapterTitleTranslationsClicked: (() -> Unit)?,
    onImportLocalChaptersClicked: (() -> Unit)?,
    onClearHistoryClicked: () -> Unit,
    onEditNotesClicked: () -> Unit,
    onDeleteLocalMangaClicked: (() -> Unit)? = null,

    // For chapter layout action
    chapterLayoutAvailable: Boolean,
    chapterLayoutGridEnabled: Boolean,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiGoodDoujinClicked: (List<Chapter>, marked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMarkFollowingAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,
    onDeleteLocalChaptersClicked: (List<Chapter>) -> Unit,
    onMoveChaptersClicked: (List<Chapter>) -> Unit,

    // For chapter swipe
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,
    onReorderChapters: (List<Long>) -> Unit,
    onEditChapterTranslatedTitle: (ChapterList.Item) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,

    // Duplicate marks (experimental)
    markedChapterIds: Set<Long>,
    goodDoujinChapterIds: Set<Long>,
    onToggleMarkClicked: ((List<Chapter>) -> Unit)?,
) {
    val chapterListState = rememberLazyListState()
    val atListEnd = rememberAtListEnd(chapterListState)

    val (chapters, listItem, isAnySelected) = remember(state) {
        Triple(
            first = state.processedChapters,
            second = state.chapterListItems,
            third = state.isAnySelected,
        )
    }

    BackHandler(enabled = isAnySelected) {
        onAllChapterSelected(false)
    }

    // 标题选区由原生 TextView 的 ActionMode 管理。原生 TextView 点击同窗口内其它非可聚焦
    // 区域时不会自动收掉选区，所以「点外部关闭」得自己补：返回键走 BackHandler，点空白处
    // 走下面 Scaffold 上的 pointerInput（只在选区激活时运行，点标题内让位、点标题外 clear）。
    val titleSelection = remember { MangaTitleSelectionController() }
    BackHandler(enabled = titleSelection.isActive) { titleSelection.clear() }

    Scaffold(
        modifier = Modifier.pointerInput(titleSelection.isActive) {
            if (!titleSelection.isActive) return@pointerInput
            awaitEachGesture {
                if (titleSelection.isOutsideTitle(awaitFirstDown(requireUnconsumed = false).position)) {
                    titleSelection.clear()
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Start,
        topBar = {
            val selectedChapterCount: Int = remember(chapters) {
                chapters.count { it.selected }
            }
            val isFirstItemVisible by remember {
                derivedStateOf { chapterListState.firstVisibleItemIndex == 0 }
            }
            val isFirstItemScrolled by remember {
                derivedStateOf { chapterListState.firstVisibleItemScrollOffset > 0 }
            }
            val titleAlpha by animateFloatAsState(
                if (!isFirstItemVisible) 1f else 0f,
                label = "Top Bar Title",
            )
            val backgroundAlpha by animateFloatAsState(
                if (!isFirstItemVisible || isFirstItemScrolled) 1f else 0f,
                label = "Top Bar Background",
            )
            MangaToolbar(
                title = state.manga.title,
                hasFilters = state.filterActive,
                navigateUp = navigateUp,
                onClickHome = onClickHome,
                onClickAudio = onAudioClicked,
                onClickFilter = onFilterClicked,
                onClickShare = onShareClicked,
                onClickDownload = onDownloadActionClicked,
                onClickEditCategory = onEditCategoryClicked,
                onClickRefresh = onRefresh,
                onClickMigrate = onMigrateClicked,
                onClickChapterTitleTranslations = onChapterTitleTranslationsClicked,
                onClickImportLocalChapters = onImportLocalChaptersClicked,
                onClickClearHistory = onClearHistoryClicked,
                onClickEditNotes = onEditNotesClicked,
                onClickDeleteLocalFiles = onDeleteLocalMangaClicked,
                actionModeCounter = selectedChapterCount,
                onCancelActionMode = { onAllChapterSelected(false) },
                onSelectAll = { onAllChapterSelected(true) },
                onInvertSelection = { onInvertSelection() },
                titleAlphaProvider = { titleAlpha },
                backgroundAlphaProvider = { backgroundAlpha },
            )
        },
        bottomBar = {
            val selectedChapters = remember(chapters) {
                chapters.filter { it.selected }
            }
            SharedMangaBottomActionMenu(
                selected = selectedChapters,
                goodDoujinEnabled = state.manga.isLocal(),
                isLocalManga = state.manga.isLocal(),
                onMultiBookmarkClicked = onMultiBookmarkClicked,
                onMultiGoodDoujinClicked = onMultiGoodDoujinClicked,
                onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
                onMarkFollowingAsReadClicked = onMarkFollowingAsReadClicked,
                onDownloadChapter = onDownloadChapter,
                onMultiDeleteClicked = onMultiDeleteClicked,
                onDeleteLocalChaptersClicked = onDeleteLocalChaptersClicked,
                onMoveChaptersClicked = onMoveChaptersClicked,
                markedChapterIds = markedChapterIds,
                goodDoujinChapterIds = goodDoujinChapterIds,
                onToggleMarkClicked = onToggleMarkClicked,
                onEditChapterTranslatedTitle = onEditChapterTranslatedTitle,
                fillFraction = 1f,
            )
        },
        snackbarHost = { AutoDismissSnackbarHost(hostState = snackbarHostState) },
        // Deliberately no floatingActionButton slot: the scaffold reserves a bottom band
        // for it and hands that band to the content padding, which would leave the chapter
        // list short of the bottom edge. The button is anchored over the list instead, with
        // the same formula as the one on the local source listing, so it rests at the same
        // spot on both screens.
    ) { contentPadding ->
        val topPadding = contentPadding.calculateTopPadding()
        val layoutDirection = LocalLayoutDirection.current
        val chapterContentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = contentPadding.calculateBottomPadding(),
        )
        val reorderScrollPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            top = topPadding,
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = contentPadding.calculateBottomPadding(),
        )
        val chapterItems = remember { chapters.toMutableStateList() }
        var pendingReorder by remember { mutableStateOf(false) }
        var reorderChanged by remember { mutableStateOf(false) }
        val reorderableState = rememberReorderableLazyListState(chapterListState, reorderScrollPadding) { from, to ->
            val fromIndex = chapterItems.indexOfFirst { "chapter-${it.id}" == from.key }
            val toIndex = chapterItems.indexOfFirst { "chapter-${it.id}" == to.key }
            if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                val item = chapterItems.removeAt(fromIndex)
                chapterItems.add(toIndex, item)
                reorderChanged = true
            }
        }
        LaunchedEffect(chapters, state.manga, pendingReorder) {
            if (pendingReorder) {
                if (state.manga.sorting == Manga.CHAPTER_SORTING_CUSTOM &&
                    chapterItems.matchesVisibleChapterOrder(chapters)
                ) {
                    chapterItems.clear()
                    chapterItems.addAll(chapters)
                    pendingReorder = false
                }
                return@LaunchedEffect
            }
            if (!reorderableState.isAnyItemDragging) {
                chapterItems.clear()
                chapterItems.addAll(chapters)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            PullRefresh(
                refreshing = state.isRefreshingData,
                onRefresh = onRefresh,
                enabled = !isAnySelected,
                indicatorPadding = PaddingValues(top = topPadding),
            ) {
                VerticalFastScroller(
                    listState = chapterListState,
                    topContentPadding = topPadding,
                    endContentPadding = contentPadding.calculateEndPadding(layoutDirection),
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxHeight()
                            .twoFingerScrollDuringReorder(reorderableState, chapterListState),
                        state = chapterListState,
                        contentPadding = chapterContentPadding,
                    ) {
                        item(
                            key = MangaScreenItem.INFO_BOX,
                            contentType = MangaScreenItem.INFO_BOX,
                        ) {
                            MangaInfoBox(
                                isTabletUi = false,
                                appBarPadding = topPadding,
                                manga = state.manga,
                                sourceName = remember { state.source.getNameForMangaInfo() },
                                isStubSource = remember { state.source is StubSource },
                                onCoverClick = onCoverClicked,
                                doSearch = onSearch,
                                searchLocal = onLocalSearch,
                                onOpenSource = onOpenSource,
                                titleSelection = titleSelection,
                            )
                        }

                        item(
                            key = MangaScreenItem.ACTION_ROW,
                            contentType = MangaScreenItem.ACTION_ROW,
                        ) {
                            MangaActionRow(
                                favorite = state.manga.favorite,
                                trackingCount = state.trackingCount,
                                nextUpdate = nextUpdate,
                                isUserIntervalMode = state.manga.fetchInterval < 0,
                                isLocalSource = state.manga.isLocal(),
                                onAddToLibraryClicked = onAddToLibraryClicked,
                                onWebViewClicked = onWebViewClicked,
                                onWebViewLongClicked = onWebViewLongClicked,
                                onTrackingClicked = onTrackingClicked,
                                onEditIntervalClicked = onEditIntervalClicked,
                                onEditCategory = onEditCategoryClicked,
                            )
                        }

                        item(
                            key = MangaScreenItem.DESCRIPTION_WITH_TAG,
                            contentType = MangaScreenItem.DESCRIPTION_WITH_TAG,
                        ) {
                            ExpandableMangaDescription(
                                defaultExpandState = state.isFromSource,
                                description = state.manga.description,
                                tagsProvider = { state.manga.genre },
                                notes = state.manga.notes,
                                isLocalSource = state.manga.isLocal(),
                                onTagSearch = onTagSearch,
                                onCopyTagToClipboard = onCopyTagToClipboard,
                                onEditNotes = onEditNotesClicked,
                            )
                        }

                        item(
                            key = MangaScreenItem.CHAPTER_HEADER,
                            contentType = MangaScreenItem.CHAPTER_HEADER,
                        ) {
                            Column {
                                // Separates the chapter list from everything above it. Local entries
                                // have no description to break up the page, so the line is what
                                // keeps the header from running straight into the action row.
                                //
                                // Inset to the content margin so the line, the count text under it
                                // and the chapter rows all share one left edge; a full-bleed line
                                // overshoots the text it belongs to and reads as detached.
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                                )
                                ChapterHeader(
                                    enabled = !isAnySelected,
                                    chapterCount = chapters.size,
                                    onClick = onFilterClicked,
                                )
                            }
                        }

                        sharedChapterItems(
                            manga = state.manga,
                            chapters = chapterItems,
                            localChapterCoversEnabled = chapterLayoutAvailable,
                            localChapterCoverGridEnabled = chapterLayoutGridEnabled,
                            reorderableState = reorderableState,
                            onReorder = {
                                if (!reorderChanged) return@sharedChapterItems false
                                reorderChanged = false
                                pendingReorder = true
                                onReorderChapters(chapterItems.fastMap { it.chapter.id })
                                true
                            },
                            isAnyChapterSelected = chapters.fastAny { it.selected },
                            chapterSwipeStartAction = chapterSwipeStartAction,
                            chapterSwipeEndAction = chapterSwipeEndAction,
                            markedChapterIds = markedChapterIds,
                            goodDoujinChapterIds = goodDoujinChapterIds,
                            onChapterClicked = onChapterClicked,
                            onDownloadChapter = onDownloadChapter,
                            onChapterSelected = onChapterSelected,
                            onChapterSwipe = onChapterSwipe,
                        )
                    }
                }
            }

            if (chapters.isNotEmpty() && !isAnySelected) {
                RandomGestureFab(
                    gesturesEnabled = onRandomClicked != null || onClickRandomGoodDoujin != null,
                    atListEnd = atListEnd,
                    idleIcon = Icons.Filled.PlayArrow,
                    onTap = onContinueReading,
                    onRandomManga = { onRandomClicked?.invoke() },
                    onRandomGoodDoujin = { onClickRandomGoodDoujin?.invoke() },
                    // Same anchor as the control on the local source listing: clear the
                    // system bar, then sit 16 dp above where the bottom navigation bar
                    // would be. That page has the bar to rest on, this one lifts by its
                    // height instead, so the button does not move between the two.
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .windowInsetsPadding(
                            WindowInsets.systemBars
                                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Start),
                        )
                        .padding(start = 16.dp, bottom = 16.dp + BottomNavFabLift),
                )
            }
        }
    }
}

@Composable
fun MangaScreenLargeImpl(
    state: MangaViewModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onClickHome: (() -> Unit)? = null,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    // For tags menu
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,

    onRandomClicked: (() -> Unit)? = null,
    onClickRandomGoodDoujin: (() -> Unit)? = null,
    onAudioClicked: (() -> Unit)? = null,
    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,
    onLocalSearch: (query: String) -> Unit,
    onOpenSource: () -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onChapterTitleTranslationsClicked: (() -> Unit)?,
    onImportLocalChaptersClicked: (() -> Unit)?,
    onClearHistoryClicked: () -> Unit,
    onEditNotesClicked: () -> Unit,
    onDeleteLocalMangaClicked: (() -> Unit)? = null,

    // For chapter layout action
    chapterLayoutAvailable: Boolean,
    chapterLayoutGridEnabled: Boolean,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiGoodDoujinClicked: (List<Chapter>, marked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMarkFollowingAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,
    onDeleteLocalChaptersClicked: (List<Chapter>) -> Unit,
    onMoveChaptersClicked: (List<Chapter>) -> Unit,

    // For swipe actions
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,
    onReorderChapters: (List<Long>) -> Unit,
    onEditChapterTranslatedTitle: (ChapterList.Item) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,

    // Duplicate marks (experimental)
    markedChapterIds: Set<Long>,
    goodDoujinChapterIds: Set<Long>,
    onToggleMarkClicked: ((List<Chapter>) -> Unit)?,
) {
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current

    val (chapters, listItem, isAnySelected) = remember(state) {
        Triple(
            first = state.processedChapters,
            second = state.chapterListItems,
            third = state.isAnySelected,
        )
    }

    val insetPadding = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).asPaddingValues()
    var topBarHeight by remember { mutableIntStateOf(0) }

    val chapterListState = rememberLazyListState()
    val atListEnd = rememberAtListEnd(chapterListState)

    BackHandler(enabled = isAnySelected) {
        onAllChapterSelected(false)
    }

    // 同 SmallImpl：返回键 + 点外部关闭各补一路，其余交给系统的 ActionMode。
    val titleSelection = remember { MangaTitleSelectionController() }
    BackHandler(enabled = titleSelection.isActive) { titleSelection.clear() }

    Scaffold(
        modifier = Modifier.pointerInput(titleSelection.isActive) {
            if (!titleSelection.isActive) return@pointerInput
            awaitEachGesture {
                if (titleSelection.isOutsideTitle(awaitFirstDown(requireUnconsumed = false).position)) {
                    titleSelection.clear()
                }
            }
        },
        // 与小屏一致放在左下角，随机手势才有一致的触发位置。
        floatingActionButtonPosition = FabPosition.Start,
        topBar = {
            val selectedChapterCount = remember(chapters) {
                chapters.count { it.selected }
            }
            MangaToolbar(
                modifier = Modifier.onSizeChanged { topBarHeight = it.height },
                title = state.manga.title,
                hasFilters = state.filterActive,
                navigateUp = navigateUp,
                onClickHome = onClickHome,
                onClickAudio = onAudioClicked,
                onClickFilter = onFilterButtonClicked,
                onClickShare = onShareClicked,
                onClickDownload = onDownloadActionClicked,
                onClickEditCategory = onEditCategoryClicked,
                onClickRefresh = onRefresh,
                onClickMigrate = onMigrateClicked,
                onClickChapterTitleTranslations = onChapterTitleTranslationsClicked,
                onClickImportLocalChapters = onImportLocalChaptersClicked,
                onClickClearHistory = onClearHistoryClicked,
                onClickEditNotes = onEditNotesClicked,
                onClickDeleteLocalFiles = onDeleteLocalMangaClicked,
                onCancelActionMode = { onAllChapterSelected(false) },
                actionModeCounter = selectedChapterCount,
                onSelectAll = { onAllChapterSelected(true) },
                onInvertSelection = { onInvertSelection() },
                titleAlphaProvider = { 1f },
                backgroundAlphaProvider = { 1f },
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomEnd,
            ) {
                val selectedChapters = remember(chapters) {
                    chapters.filter { it.selected }
                }
                SharedMangaBottomActionMenu(
                    selected = selectedChapters,
                    goodDoujinEnabled = state.manga.isLocal(),
                    isLocalManga = state.manga.isLocal(),
                    onMultiBookmarkClicked = onMultiBookmarkClicked,
                    onMultiGoodDoujinClicked = onMultiGoodDoujinClicked,
                    onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                    onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
                    onMarkFollowingAsReadClicked = onMarkFollowingAsReadClicked,
                    onDownloadChapter = onDownloadChapter,
                    onMultiDeleteClicked = onMultiDeleteClicked,
                    onDeleteLocalChaptersClicked = onDeleteLocalChaptersClicked,
                    onMoveChaptersClicked = onMoveChaptersClicked,
                    markedChapterIds = markedChapterIds,
                    goodDoujinChapterIds = goodDoujinChapterIds,
                    onToggleMarkClicked = onToggleMarkClicked,
                    onEditChapterTranslatedTitle = onEditChapterTranslatedTitle,
                    fillFraction = 0.5f,
                )
            }
        },
        snackbarHost = { AutoDismissSnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            val isFABVisible = remember(chapters) {
                chapters.isNotEmpty() && !isAnySelected
            }
            if (isFABVisible) {
                RandomGestureFab(
                    gesturesEnabled = onRandomClicked != null || onClickRandomGoodDoujin != null,
                    atListEnd = atListEnd,
                    idleIcon = Icons.Filled.PlayArrow,
                    onTap = onContinueReading,
                    onRandomManga = { onRandomClicked?.invoke() },
                    onRandomGoodDoujin = { onClickRandomGoodDoujin?.invoke() },
                    // No lift here: the scaffold already reserves a bottom slot for this
                    // control. Lifting it would leave that slot as dead space under the
                    // list while the button floats over the last chapters instead.
                )
            }
        },
    ) { contentPadding ->
        PullRefresh(
            refreshing = state.isRefreshingData,
            onRefresh = onRefresh,
            enabled = !isAnySelected,
            indicatorPadding = PaddingValues(
                start = insetPadding.calculateStartPadding(layoutDirection),
                top = with(density) { topBarHeight.toDp() },
                end = insetPadding.calculateEndPadding(layoutDirection),
            ),
        ) {
            TwoPanelBox(
                modifier = Modifier.padding(
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    end = contentPadding.calculateEndPadding(layoutDirection),
                ),
                startContent = {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = contentPadding.calculateBottomPadding()),
                    ) {
                        MangaInfoBox(
                            isTabletUi = true,
                            appBarPadding = contentPadding.calculateTopPadding(),
                            manga = state.manga,
                            sourceName = remember { state.source.getNameForMangaInfo() },
                            isStubSource = remember { state.source is StubSource },
                            onCoverClick = onCoverClicked,
                            doSearch = onSearch,
                            searchLocal = onLocalSearch,
                            onOpenSource = onOpenSource,
                            titleSelection = titleSelection,
                        )
                        MangaActionRow(
                            favorite = state.manga.favorite,
                            trackingCount = state.trackingCount,
                            nextUpdate = nextUpdate,
                            isUserIntervalMode = state.manga.fetchInterval < 0,
                            isLocalSource = state.manga.isLocal(),
                            onAddToLibraryClicked = onAddToLibraryClicked,
                            onWebViewClicked = onWebViewClicked,
                            onWebViewLongClicked = onWebViewLongClicked,
                            onTrackingClicked = onTrackingClicked,
                            onEditIntervalClicked = onEditIntervalClicked,
                            onEditCategory = onEditCategoryClicked,
                        )
                        ExpandableMangaDescription(
                            defaultExpandState = true,
                            description = state.manga.description,
                            tagsProvider = { state.manga.genre },
                            notes = state.manga.notes,
                            isLocalSource = state.manga.isLocal(),
                            onTagSearch = onTagSearch,
                            onCopyTagToClipboard = onCopyTagToClipboard,
                            onEditNotes = onEditNotesClicked,
                        )
                    }
                },
                endContent = {
                    val chapterContentPadding = PaddingValues(
                        top = contentPadding.calculateTopPadding(),
                        bottom = contentPadding.calculateBottomPadding(),
                    )
                    val chapterItems = remember { chapters.toMutableStateList() }
                    var pendingReorder by remember { mutableStateOf(false) }
                    var reorderChanged by remember { mutableStateOf(false) }
                    val reorderableState =
                        rememberReorderableLazyListState(chapterListState, chapterContentPadding) { from, to ->
                            val fromIndex = chapterItems.indexOfFirst { "chapter-${it.id}" == from.key }
                            val toIndex = chapterItems.indexOfFirst { "chapter-${it.id}" == to.key }
                            if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                                val item = chapterItems.removeAt(fromIndex)
                                chapterItems.add(toIndex, item)
                                reorderChanged = true
                            }
                        }
                    LaunchedEffect(chapters, state.manga, pendingReorder) {
                        if (pendingReorder) {
                            if (state.manga.sorting == Manga.CHAPTER_SORTING_CUSTOM &&
                                chapterItems.matchesVisibleChapterOrder(chapters)
                            ) {
                                chapterItems.clear()
                                chapterItems.addAll(chapters)
                                pendingReorder = false
                            }
                            return@LaunchedEffect
                        }
                        if (!reorderableState.isAnyItemDragging) {
                            chapterItems.clear()
                            chapterItems.addAll(chapters)
                        }
                    }
                    VerticalFastScroller(
                        listState = chapterListState,
                        topContentPadding = contentPadding.calculateTopPadding(),
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxHeight()
                                .twoFingerScrollDuringReorder(reorderableState, chapterListState),
                            state = chapterListState,
                            contentPadding = chapterContentPadding,
                        ) {
                            item(
                                key = MangaScreenItem.CHAPTER_HEADER,
                                contentType = MangaScreenItem.CHAPTER_HEADER,
                            ) {
                                ChapterHeader(
                                    enabled = !isAnySelected,
                                    chapterCount = chapters.size,
                                    onClick = onFilterButtonClicked,
                                )
                            }

                            sharedChapterItems(
                                manga = state.manga,
                                chapters = chapterItems,
                                localChapterCoversEnabled = chapterLayoutAvailable,
                                localChapterCoverGridEnabled = chapterLayoutGridEnabled,
                                reorderableState = reorderableState,
                                onReorder = {
                                    if (!reorderChanged) return@sharedChapterItems false
                                    reorderChanged = false
                                    pendingReorder = true
                                    onReorderChapters(chapterItems.fastMap { it.chapter.id })
                                    true
                                },
                                isAnyChapterSelected = chapters.fastAny { it.selected },
                                chapterSwipeStartAction = chapterSwipeStartAction,
                                chapterSwipeEndAction = chapterSwipeEndAction,
                                markedChapterIds = markedChapterIds,
                                goodDoujinChapterIds = goodDoujinChapterIds,
                                onChapterClicked = onChapterClicked,
                                onDownloadChapter = onDownloadChapter,
                                onChapterSelected = onChapterSelected,
                                onChapterSwipe = onChapterSwipe,
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SharedMangaBottomActionMenu(
    selected: List<ChapterList.Item>,
    goodDoujinEnabled: Boolean,
    isLocalManga: Boolean,
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiGoodDoujinClicked: (List<Chapter>, marked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMarkFollowingAsReadClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,
    onDeleteLocalChaptersClicked: (List<Chapter>) -> Unit,
    onMoveChaptersClicked: (List<Chapter>) -> Unit,
    markedChapterIds: Set<Long>,
    goodDoujinChapterIds: Set<Long>,
    onToggleMarkClicked: ((List<Chapter>) -> Unit)?,
    onEditChapterTranslatedTitle: (ChapterList.Item) -> Unit,
    fillFraction: Float,
    modifier: Modifier = Modifier,
) {
    MangaBottomActionMenu(
        visible = selected.isNotEmpty(),
        modifier = modifier.fillMaxWidth(fillFraction),
        onBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.chapter }, true)
        }.takeIf { !goodDoujinEnabled && selected.fastAny { !it.chapter.bookmark } },
        onRemoveBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.chapter }, false)
        }.takeIf { !goodDoujinEnabled && selected.fastAll { it.chapter.bookmark } },
        onAddToGoodDoujinClicked = {
            onMultiGoodDoujinClicked.invoke(selected.fastMap { it.chapter }, true)
        }.takeIf { goodDoujinEnabled && selected.fastAny { it.chapter.id !in goodDoujinChapterIds } },
        onRemoveFromGoodDoujinClicked = {
            onMultiGoodDoujinClicked.invoke(selected.fastMap { it.chapter }, false)
        }.takeIf { goodDoujinEnabled && selected.fastAll { it.chapter.id in goodDoujinChapterIds } },
        onMarkAsReadClicked = {
            onMultiMarkAsReadClicked(selected.fastMap { it.chapter }, true)
        }.takeIf { selected.fastAny { !it.chapter.read } },
        onMarkAsUnreadClicked = {
            onMultiMarkAsReadClicked(selected.fastMap { it.chapter }, false)
        }.takeIf { selected.fastAll { it.chapter.read } },
        onMarkPreviousAsReadClicked = {
            onMarkPreviousAsReadClicked(selected[0].chapter)
        }.takeIf { selected.size == 1 },
        onMarkFollowingAsReadClicked = {
            onMarkFollowingAsReadClicked(selected[0].chapter)
        }.takeIf { selected.size == 1 },
        onDownloadClicked = {
            onDownloadChapter!!(selected.toList(), ChapterDownloadAction.START)
        }.takeIf {
            onDownloadChapter != null && selected.fastAny { it.downloadState != Download.State.DOWNLOADED }
        },
        onDeleteClicked = {
            onMultiDeleteClicked(selected.fastMap { it.chapter })
        }.takeIf {
            // Local manga chapters are all reported as "downloaded", but they are not
            // managed by the download manager, so deleting them would do nothing. For local
            // manga the slot is taken over by the local file deletion instead.
            !isLocalManga && selected.fastAny { it.downloadState == Download.State.DOWNLOADED }
        },
        onDeleteLocalFilesClicked = {
            onDeleteLocalChaptersClicked(selected.fastMap { it.chapter })
        }.takeIf { isLocalManga },
        onMoveClicked = {
            onMoveChaptersClicked(selected.fastMap { it.chapter })
        }.takeIf { goodDoujinEnabled },
        onEditTranslatedTitleClicked = {
            onEditChapterTranslatedTitle(selected.single())
        }.takeIf { goodDoujinEnabled && selected.size == 1 },
        onToggleMarkClicked = onToggleMarkClicked?.let { toggle ->
            { toggle(selected.fastMap { it.chapter }) }
        }.takeIf { selected.isNotEmpty() },
        marksSelected = selected.isNotEmpty() && selected.fastAll { it.id in markedChapterIds },
        selectedCount = selected.size,
    )
}

private fun List<ChapterList.Item>.matchesVisibleChapterOrder(
    visibleChapters: List<ChapterList.Item>,
): Boolean {
    val visibleIds = visibleChapters.mapTo(hashSetOf()) { it.id }
    val retainedIds = asSequence()
        .map { it.id }
        .filter { it in visibleIds }
        .toList()
    return retainedIds.size == visibleChapters.size &&
        visibleChapters.indices.all { visibleChapters[it].id == retainedIds[it] }
}

private fun LazyListScope.sharedChapterItems(
    manga: Manga,
    chapters: List<ChapterList.Item>,
    localChapterCoversEnabled: Boolean,
    localChapterCoverGridEnabled: Boolean,
    reorderableState: ReorderableLazyListState,
    onReorder: () -> Boolean,
    isAnyChapterSelected: Boolean,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,
    markedChapterIds: Set<Long>,
    goodDoujinChapterIds: Set<Long>,
) {
    val localManga = manga.isLocal()
    val showLocalChapterCovers = localChapterCoversEnabled
    if (showLocalChapterCovers && localChapterCoverGridEnabled) {
        sharedChapterGridItems(
            manga = manga,
            chapters = chapters,
            isAnyChapterSelected = isAnyChapterSelected,
            onChapterClicked = onChapterClicked,
            onChapterSelected = onChapterSelected,
            markedChapterIds = markedChapterIds,
            goodDoujinChapterIds = goodDoujinChapterIds,
        )
        return
    }
    // The good-doujin swipe only applies to local books. Keep the legacy bookmark enum for
    // cloud sources, but for local chapters any bookmark-configured swipe acts on good doujin.
    val normalizeSwipeAction: (
        LibraryPreferences.ChapterSwipeAction,
    ) -> LibraryPreferences.ChapterSwipeAction = { action ->
        when {
            localManga && action == LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                LibraryPreferences.ChapterSwipeAction.AddToGoodDoujin
            }
            !localManga && action == LibraryPreferences.ChapterSwipeAction.AddToGoodDoujin -> {
                LibraryPreferences.ChapterSwipeAction.Disabled
            }
            else -> action
        }
    }
    val effectiveSwipeStart = normalizeSwipeAction(chapterSwipeStartAction)
    val effectiveSwipeEnd = normalizeSwipeAction(chapterSwipeEndAction)
    items(
        items = chapters,
        key = { item -> "chapter-${item.id}" },
        contentType = { MangaScreenItem.CHAPTER },
    ) { item ->
        val haptic = LocalHapticFeedback.current
        val context = LocalContext.current

        val isRead = item.chapter.read
        val chapterProgress = item.chapter.toChapterProgressUi()
        val chapterTitle = when (manga.displayMode) {
            Manga.CHAPTER_DISPLAY_NUMBER -> stringResource(
                MR.strings.display_mode_chapter,
                formatChapterNumber(item.chapter.chapterNumber),
            )
            Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL,
            Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY,
            -> item.chapter.translatedNameOrNull ?: item.chapter.name
            else -> item.chapter.name
        }
        val copyTitle = { context.copyToClipboard(item.chapter.name, item.chapter.name) }
        ReorderableItem(reorderableState, "chapter-${item.id}") {
            var dragStartIndex by remember { mutableStateOf(-1) }
            var dragMoved by remember { mutableStateOf(false) }
            var rowRootOffset by remember { mutableStateOf(Offset.Zero) }
            var titleBounds by remember { mutableStateOf<Rect?>(null) }
            var downPosition by remember { mutableStateOf<Offset?>(null) }
            val selectFromLongPress = {
                onChapterSelected(item, !item.selected, true)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            val dragDetectionEnabled = manga.isLocal() && !isAnyChapterSelected
            MangaChapterListItem(
                modifier = Modifier
                    .onGloballyPositioned { rowRootOffset = it.positionInRoot() }
                    .then(
                        if (dragDetectionEnabled) {
                            // Observe the pointer without consuming it: once the finger really
                            // moves, treat the gesture as a drag so it is never mistaken for a
                            // long-press copy afterwards.
                            Modifier.pointerInput(item.id) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial,
                                    )
                                    // Reset per gesture here rather than in onDragStarted:
                                    // the long-press callback may fire after the finger has
                                    // already moved, and resetting there would classify a real
                                    // drag as a long-press.
                                    dragMoved = false
                                    var moved = false
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            // Long-press in place: consume the up event so the
                                            // row's clickable handler does not mistake it for a
                                            // tap and open the reader.
                                            if (!moved &&
                                                change.uptimeMillis - down.uptimeMillis >=
                                                viewConfiguration.longPressTimeoutMillis
                                            ) {
                                                change.consume()
                                            }
                                            break
                                        }
                                        if (!moved) {
                                            val dx = change.position.x - down.position.x
                                            val dy = change.position.y - down.position.y
                                            if (maxOf(abs(dx), abs(dy)) > viewConfiguration.touchSlop) {
                                                moved = true
                                                dragMoved = true
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Modifier
                        },
                    )
                    .longPressDraggableHandle(
                        // Manual chapter order only makes sense for the local library; cloud
                        // series keep their source ordering and long-press selects instead.
                        enabled = dragDetectionEnabled,
                        onDragStarted = {
                            dragStartIndex = chapters.indexOf(item)
                            downPosition = it
                            // Vibrate as soon as the long-press registers so the user
                            // feels it immediately instead of waiting for the release.
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragStopped = {
                            if (!onReorder() && !dragMoved) {
                                // Long-press without moving: on the title it copies, anywhere
                                // else it selects and exposes the existing bottom action bar.
                                val down = downPosition
                                val bounds = titleBounds
                                if (down != null && bounds != null && bounds.contains(rowRootOffset + down)) {
                                    copyTitle()
                                } else {
                                    onChapterSelected(item, !item.selected, true)
                                }
                            }
                        },
                    ),
                title = chapterTitle,
                subtitle = null,
                readProgress = chapterProgress
                    ?.let { progress ->
                        stringResource(
                            MR.strings.chapter_progress_ratio,
                            progress.readPages,
                            progress.totalPages,
                        )
                    },
                scanlator = item.chapter.scanlator.takeIf { !it.isNullOrBlank() },
                read = isRead,
                // Local chapters have no bookmark concept: the swipe gesture and the bottom
                // bar both route bookmarking to the good doujin list, so hide the legacy
                // bookmark badge instead of showing two marks for the same intent.
                bookmark = item.chapter.bookmark && !localManga,
                selected = item.selected,
                downloadIndicatorEnabled = !isAnyChapterSelected && !manga.isLocal(),
                downloadStateProvider = { item.downloadState },
                downloadProgressProvider = { item.downloadProgress },
                chapterSwipeStartAction = effectiveSwipeStart,
                chapterSwipeEndAction = effectiveSwipeEnd,
                goodDoujinMarked = item.chapter.id in goodDoujinChapterIds,
                flagMarked = item.chapter.id in markedChapterIds,
                cover = if (showLocalChapterCovers) {
                    LocalChapterCover(
                        chapterId = item.chapter.id,
                        chapterUrl = item.chapter.url,
                        version = item.chapter.version xor item.chapter.dateUpload xor item.chapter.lastModifiedAt,
                    )
                } else {
                    null
                },
                readProgressFraction = chapterProgress?.fraction,
                onLongClick = if (manga.isLocal()) {
                    selectFromLongPress.takeIf { isAnyChapterSelected }
                } else {
                    selectFromLongPress
                },
                onClick = {
                    onChapterItemClick(
                        chapterItem = item,
                        isAnyChapterSelected = isAnyChapterSelected,
                        onToggleSelection = { onChapterSelected(item, !item.selected, false) },
                        onChapterClicked = onChapterClicked,
                    )
                },
                onDownloadClick = if (onDownloadChapter != null) {
                    { onDownloadChapter(listOf(item), it) }
                } else {
                    null
                },
                onChapterSwipe = {
                    onChapterSwipe(item, it)
                },
                onCopyTitle = copyTitle,
                copyTitleOnLongPress = !manga.isLocal() || isAnyChapterSelected,
                onTitleBoundsChanged = if (manga.isLocal() && !isAnyChapterSelected) {
                    { coordinates -> titleBounds = Rect(coordinates.positionInRoot(), coordinates.size.toSize()) }
                } else {
                    null
                },
            )
        }
    }
}

private fun LazyListScope.sharedChapterGridItems(
    manga: Manga,
    chapters: List<ChapterList.Item>,
    isAnyChapterSelected: Boolean,
    onChapterClicked: (Chapter) -> Unit,
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    markedChapterIds: Set<Long>,
    goodDoujinChapterIds: Set<Long>,
) {
    items(
        items = chapters.chunked(LOCAL_CHAPTER_GRID_COLUMNS),
        key = { row -> "chapter-grid-row-${row.first().id}" },
        contentType = { "chapter_grid_row" },
    ) { row ->
        val haptic = LocalHapticFeedback.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            row.forEach { item ->
                val chapterTitle = when (manga.displayMode) {
                    Manga.CHAPTER_DISPLAY_NUMBER -> stringResource(
                        MR.strings.display_mode_chapter,
                        formatChapterNumber(item.chapter.chapterNumber),
                    )
                    Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL,
                    Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY,
                    -> item.chapter.translatedNameOrNull ?: item.chapter.name
                    else -> item.chapter.name
                }
                val chapterProgress = item.chapter.toChapterProgressUi()
                MangaChapterGridItem(
                    modifier = Modifier.weight(1f),
                    title = chapterTitle,
                    subtitle = null,
                    cover = LocalChapterCover(
                        chapterId = item.chapter.id,
                        chapterUrl = item.chapter.url,
                        version = item.chapter.version xor item.chapter.dateUpload xor item.chapter.lastModifiedAt,
                    ),
                    readProgress = chapterProgress?.let { progress ->
                        stringResource(
                            MR.strings.chapter_progress_ratio,
                            progress.readPages,
                            progress.totalPages,
                        )
                    },
                    readProgressFraction = chapterProgress?.fraction,
                    read = item.chapter.read,
                    selected = item.selected,
                    bookmark = item.chapter.bookmark && !manga.isLocal(),
                    goodDoujinMarked = item.chapter.id in goodDoujinChapterIds,
                    flagMarked = item.chapter.id in markedChapterIds,
                    onLongClick = {
                        onChapterSelected(item, !item.selected, true)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onClick = {
                        onChapterItemClick(
                            chapterItem = item,
                            isAnyChapterSelected = isAnyChapterSelected,
                            onToggleSelection = { onChapterSelected(item, !item.selected, false) },
                            onChapterClicked = onChapterClicked,
                        )
                    },
                )
            }
            repeat(LOCAL_CHAPTER_GRID_COLUMNS - row.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun Modifier.twoFingerScrollDuringReorder(
    reorderableState: ReorderableLazyListState,
    listState: LazyListState,
): Modifier = pointerInput(reorderableState, listState) {
    awaitEachGesture {
        val dragPointerId = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        ).id
        var secondPointerId: PointerId? = null

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressedChanges = event.changes.filter { it.pressed }
            if (pressedChanges.isEmpty()) break

            if (!reorderableState.isAnyItemDragging) {
                secondPointerId = null
                continue
            }

            val second = secondPointerId
                ?.let { id -> pressedChanges.firstOrNull { it.id == id } }
                ?: pressedChanges.firstOrNull { it.id != dragPointerId }
                    ?.also { secondPointerId = it.id }

            if (second == null) {
                secondPointerId = null
                continue
            }

            val deltaY = second.position.y - second.previousPosition.y
            if (deltaY != 0f) {
                // Keep the original drag pointer owned by the reorderable item. The second
                // pointer only moves the list, synchronously, so scroll jobs cannot pile up.
                listState.dispatchRawDelta(-deltaY)
                second.consume()
            }
        }
    }
}

private fun onChapterItemClick(
    chapterItem: ChapterList.Item,
    isAnyChapterSelected: Boolean,
    onToggleSelection: (Boolean) -> Unit,
    onChapterClicked: (Chapter) -> Unit,
) {
    when {
        chapterItem.selected -> onToggleSelection(false)
        isAnyChapterSelected -> onToggleSelection(true)
        else -> onChapterClicked(chapterItem.chapter)
    }
}

private const val LOCAL_CHAPTER_GRID_COLUMNS = 3
