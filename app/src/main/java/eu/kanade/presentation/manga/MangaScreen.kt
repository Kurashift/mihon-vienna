package eu.kanade.presentation.manga

import android.content.Context
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
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
import androidx.compose.ui.layout.LayoutCoordinates
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
import androidx.compose.ui.zIndex
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.components.BottomNavFabLift
import eu.kanade.presentation.components.RandomGestureFab
import eu.kanade.presentation.components.rememberAtListEnd
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterGridDragState
import eu.kanade.presentation.manga.components.ChapterHeader
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.MangaActionRow
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterGridItem
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MangaInfoBox
import eu.kanade.presentation.manga.components.MangaTitleSelectionController
import eu.kanade.presentation.manga.components.MangaToolbar
import eu.kanade.presentation.manga.components.ReadRangeActions
import eu.kanade.presentation.manga.components.chapterGridDragSource
import eu.kanade.presentation.manga.components.chapterGridSlotBounds
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
    val context = LocalContext.current
    val chapterListState = rememberLazyListState()
    val atListEnd = rememberAtListEnd(chapterListState)

    val (chapters, listItem, isAnySelected) = remember(state) {
        Triple(
            first = state.processedChapters,
            second = state.chapterListItems,
            third = state.isAnySelected,
        )
    }

    // Same visible-list selection the grid cards gate their own long press on. Using the
    // unfiltered state.isAnySelected here would leave a dead zone: a selection hidden by the
    // active filter disables both the drag source and the card long press.
    val isAnyChapterSelected = chapters.fastAny { it.selected }

    // Both the bottom bar and the content padding need to know that a chapter is travelling, not
    // merely held: a long press that never moves must keep the bar where it is, or the bar would
    // duck out and bounce back on every plain long press. The grid reports it from its own drag
    // state, the list from the gesture observer in sharedChapterItems.
    var gridDragging by remember { mutableStateOf(false) }
    var chapterDragMoved by remember { mutableStateOf(false) }
    val isDraggingChapter = gridDragging || chapterDragMoved

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
                suppressed = isDraggingChapter,
                goodDoujinEnabled = state.manga.isLocal(),
                isLocalManga = state.manga.isLocal(),
                onMultiBookmarkClicked = onMultiBookmarkClicked,
                onMultiGoodDoujinClicked = onMultiGoodDoujinClicked,
                onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                chapterList = chapters,
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
        // The bottom bar hands its height back to the content padding when it steps aside for a
        // drag, which would slide the whole list up under the finger mid-gesture. Holding on to
        // the height the list already had keeps the rows still until the drag is over. The value
        // is captured on the frame the drag starts, before the bar has been re-measured away, so
        // nothing moves on the way in either.
        val liveBottomPadding = contentPadding.calculateBottomPadding()
        val dragStartBottomPadding = remember(isDraggingChapter) { liveBottomPadding }
        val bottomPadding = if (isDraggingChapter) {
            maxOf(liveBottomPadding, dragStartBottomPadding)
        } else {
            liveBottomPadding
        }
        val chapterContentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = bottomPadding,
        )
        val reorderScrollPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            top = topPadding,
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = bottomPadding,
        )
        val chapterItems = remember { chapters.toMutableStateList() }
        var pendingReorder by remember { mutableStateOf(false) }
        var reorderChanged by remember { mutableStateOf(false) }
        val commitReorder: () -> Boolean = {
            if (!reorderChanged) {
                false
            } else {
                reorderChanged = false
                pendingReorder = true
                onReorderChapters(chapterItems.fastMap { it.chapter.id })
                true
            }
        }
        val reorderableState = rememberReorderableLazyListState(chapterListState, reorderScrollPadding) { from, to ->
            val fromIndex = chapterItems.indexOfFirst { "chapter-${it.id}" == from.key }
            val toIndex = chapterItems.indexOfFirst { "chapter-${it.id}" == to.key }
            if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                val item = chapterItems.removeAt(fromIndex)
                chapterItems.add(toIndex, item)
                reorderChanged = true
            }
        }
        val gridDragState = remember(chapterListState) {
            ChapterGridDragState(
                listState = chapterListState,
                items = chapterItems,
                columns = LOCAL_CHAPTER_GRID_COLUMNS,
                onCommit = {
                    reorderChanged = true
                    commitReorder()
                },
            )
        }
        LaunchedEffect(gridDragState.isDragging) {
            gridDragging = gridDragState.isDragging
        }
        LaunchedEffect(
            chapters,
            state.manga.sorting,
            state.manga.sortDescending(),
            pendingReorder,
            gridDragState.isDragging,
        ) {
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
            // 网格拖拽时 reorderableState 是静止的，只看它会把拖到一半的顺序冲掉。
            if (!reorderableState.isAnyItemDragging && !gridDragState.isDragging) {
                syncChapterItems(chapterItems, chapters)
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
                            .onGloballyPositioned { gridDragState.onListPlaced(it) }
                            .chapterGridDragSource(
                                state = gridDragState,
                                enabled = chapterLayoutAvailable && chapterLayoutGridEnabled,
                                onLongPressInPlace = { itemId, onTitle ->
                                    onChapterGridLongPress(
                                        item = chapterItems.firstOrNull { it.id == itemId },
                                        onTitle = onTitle && !isAnyChapterSelected,
                                        context = context,
                                        onSelect = { item -> onChapterSelected(item, !item.selected, true) },
                                    )
                                },
                            )
                            .twoFingerScrollDuringReorder(reorderableState, chapterListState) {
                                gridDragState.isDragging
                            },
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
                            gridDragState = gridDragState,
                            onReorder = { commitReorder() },
                            isAnyChapterSelected = isAnyChapterSelected,
                            isDraggingChapter = isDraggingChapter,
                            onChapterDragMovedChanged = { chapterDragMoved = it },
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

    // Same visible-list selection the grid cards gate their own long press on. Using the
    // unfiltered state.isAnySelected here would leave a dead zone: a selection hidden by the
    // active filter disables both the drag source and the card long press.
    val isAnyChapterSelected = chapters.fastAny { it.selected }

    // See the small layout: the bottom bar and the content padding only react once a chapter is
    // actually travelling, so a long press released in place leaves the bar alone.
    var gridDragging by remember { mutableStateOf(false) }
    var chapterDragMoved by remember { mutableStateOf(false) }
    val isDraggingChapter = gridDragging || chapterDragMoved

    val context = LocalContext.current
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
                    suppressed = isDraggingChapter,
                    goodDoujinEnabled = state.manga.isLocal(),
                    isLocalManga = state.manga.isLocal(),
                    onMultiBookmarkClicked = onMultiBookmarkClicked,
                    onMultiGoodDoujinClicked = onMultiGoodDoujinClicked,
                    onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                    chapterList = chapters,
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
        // The bottom bar hands its height back to the content padding when it steps aside for a
        // drag, which would slide both panels up under the finger. Keeping the height they
        // already had holds them still until the drag is over. The value is captured on the
        // frame the drag starts, before the bar has been re-measured away, so nothing moves on
        // the way in either.
        val liveBottomPadding = contentPadding.calculateBottomPadding()
        val dragStartBottomPadding = remember(isDraggingChapter) { liveBottomPadding }
        val bottomPadding = if (isDraggingChapter) {
            maxOf(liveBottomPadding, dragStartBottomPadding)
        } else {
            liveBottomPadding
        }
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
                            .padding(bottom = bottomPadding),
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
                        bottom = bottomPadding,
                    )
                    val chapterItems = remember { chapters.toMutableStateList() }
                    var pendingReorder by remember { mutableStateOf(false) }
                    var reorderChanged by remember { mutableStateOf(false) }
                    val commitReorder: () -> Boolean = {
                        if (!reorderChanged) {
                            false
                        } else {
                            reorderChanged = false
                            pendingReorder = true
                            onReorderChapters(chapterItems.fastMap { it.chapter.id })
                            true
                        }
                    }
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
                    val gridDragState = remember(chapterListState) {
                        ChapterGridDragState(
                            listState = chapterListState,
                            items = chapterItems,
                            columns = LOCAL_CHAPTER_GRID_COLUMNS,
                            onCommit = {
                                reorderChanged = true
                                commitReorder()
                            },
                        )
                    }
                    LaunchedEffect(gridDragState.isDragging) {
                        gridDragging = gridDragState.isDragging
                    }
                    LaunchedEffect(
                        chapters,
                        state.manga.sorting,
                        state.manga.sortDescending(),
                        pendingReorder,
                        gridDragState.isDragging,
                    ) {
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
                        // 网格拖拽时 reorderableState 是静止的，只看它会把拖到一半的顺序冲掉。
                        if (!reorderableState.isAnyItemDragging && !gridDragState.isDragging) {
                            syncChapterItems(chapterItems, chapters)
                        }
                    }
                    VerticalFastScroller(
                        listState = chapterListState,
                        topContentPadding = contentPadding.calculateTopPadding(),
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxHeight()
                                .onGloballyPositioned { gridDragState.onListPlaced(it) }
                                .chapterGridDragSource(
                                    state = gridDragState,
                                    enabled = chapterLayoutAvailable && chapterLayoutGridEnabled,
                                    onLongPressInPlace = { itemId, onTitle ->
                                        onChapterGridLongPress(
                                            item = chapterItems.firstOrNull { it.id == itemId },
                                            onTitle = onTitle && !isAnyChapterSelected,
                                            context = context,
                                            onSelect = { item -> onChapterSelected(item, !item.selected, true) },
                                        )
                                    },
                                )
                                .twoFingerScrollDuringReorder(reorderableState, chapterListState) {
                                    gridDragState.isDragging
                                },
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
                                gridDragState = gridDragState,
                                onReorder = { commitReorder() },
                                isAnyChapterSelected = isAnyChapterSelected,
                                isDraggingChapter = isDraggingChapter,
                                onChapterDragMovedChanged = { chapterDragMoved = it },
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
    suppressed: Boolean,
    goodDoujinEnabled: Boolean,
    isLocalManga: Boolean,
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiGoodDoujinClicked: (List<Chapter>, marked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    chapterList: List<ChapterList.Item>,
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
    // The row keeps drawing the last real selection while it animates out. Recomputing the
    // actions from an empty list instead would make every "all selected chapters share this
    // state" check come out true — fastAll over nothing is vacuously true — which swaps the
    // row for a different set of buttons on the very frame the bar starts collapsing. That
    // is the flicker on leaving selection mode.
    val lastSelection = remember { SelectionMemory() }
    if (selected.isNotEmpty()) lastSelection.items = selected
    val actedOn = if (selected.isNotEmpty()) selected else lastSelection.items
    // The before/after entries follow the order the chapters are listed in, so they mean the
    // same thing in the grid and under every sort. They are a single-selection affordance: a
    // multi-selection gets none, which leaves the read slot a plain button instead of a menu.
    val ranges = remember(actedOn, chapterList) {
        if (actedOn.size == 1) readRangeActions(chapterList, actedOn[0]) else null
    }
    MangaBottomActionMenu(
        // A chapter is on the move: the bar would cover the row being dragged over.
        visible = selected.isNotEmpty() && !suppressed,
        modifier = modifier.fillMaxWidth(fillFraction),
        onBookmarkClicked = {
            onMultiBookmarkClicked.invoke(actedOn.fastMap { it.chapter }, true)
        }.takeIf { !goodDoujinEnabled && actedOn.fastAny { !it.chapter.bookmark } },
        onRemoveBookmarkClicked = {
            onMultiBookmarkClicked.invoke(actedOn.fastMap { it.chapter }, false)
        }.takeIf { !goodDoujinEnabled && actedOn.fastAll { it.chapter.bookmark } },
        onAddToGoodDoujinClicked = {
            onMultiGoodDoujinClicked.invoke(actedOn.fastMap { it.chapter }, true)
        }.takeIf { goodDoujinEnabled && actedOn.fastAny { it.chapter.id !in goodDoujinChapterIds } },
        onRemoveFromGoodDoujinClicked = {
            onMultiGoodDoujinClicked.invoke(actedOn.fastMap { it.chapter }, false)
        }.takeIf { goodDoujinEnabled && actedOn.fastAll { it.chapter.id in goodDoujinChapterIds } },
        onMarkAsReadClicked = {
            onMultiMarkAsReadClicked(actedOn.fastMap { it.chapter }, true)
        }.takeIf { actedOn.fastAny { !it.chapter.read } },
        onMarkAsUnreadClicked = {
            onMultiMarkAsReadClicked(actedOn.fastMap { it.chapter }, false)
        }.takeIf { actedOn.fastAll { it.chapter.read } },
        readRanges = ranges,
        onMarkRangeClicked = onMultiMarkAsReadClicked,
        onDownloadClicked = {
            onDownloadChapter!!(actedOn.toList(), ChapterDownloadAction.START)
        }.takeIf {
            onDownloadChapter != null && actedOn.fastAny { it.downloadState != Download.State.DOWNLOADED }
        },
        onDeleteClicked = {
            onMultiDeleteClicked(actedOn.fastMap { it.chapter })
        }.takeIf {
            // Local manga chapters are all reported as "downloaded", but they are not
            // managed by the download manager, so deleting them would do nothing. For local
            // manga the slot is taken over by the local file deletion instead.
            !isLocalManga && actedOn.fastAny { it.downloadState == Download.State.DOWNLOADED }
        },
        onDeleteLocalFilesClicked = {
            onDeleteLocalChaptersClicked(actedOn.fastMap { it.chapter })
        }.takeIf { isLocalManga },
        onMoveClicked = {
            onMoveChaptersClicked(actedOn.fastMap { it.chapter })
        }.takeIf { goodDoujinEnabled },
        onEditTranslatedTitleClicked = {
            onEditChapterTranslatedTitle(actedOn.single())
        }.takeIf { goodDoujinEnabled && actedOn.size == 1 },
        onToggleMarkClicked = onToggleMarkClicked?.let { toggle ->
            { toggle(actedOn.fastMap { it.chapter }) }
        }.takeIf { actedOn.isNotEmpty() },
        marksSelected = actedOn.isNotEmpty() && actedOn.fastAll { it.id in markedChapterIds },
        selectedCount = actedOn.size,
    )
}

/**
 * The chapters the before/after read entries would touch, taken in the order they are listed in.
 *
 * Both sides include the pointer chapter so the two directions stay symmetrical. A side whose only
 * member would be the pointer itself is dropped instead: selecting the first or the last chapter
 * leaves that direction to the selection entry, which already does the same thing. Each remaining
 * side is narrowed down with the same predicate SetReadStatus uses when it writes, so an entry that
 * would change nothing comes back empty and is dropped from the menu rather than sitting there and
 * doing nothing when tapped — and when all four are empty the read slot stays a plain button, since
 * there is nothing left for a menu to hold.
 */
private fun readRangeActions(
    chapterList: List<ChapterList.Item>,
    pointer: ChapterList.Item,
): ReadRangeActions? {
    val pointerPos = chapterList.indexOfFirst { it.id == pointer.id }
    if (pointerPos == -1) return null
    // A side that would hold nothing but the pointer has no range to speak of — selecting the first
    // or the last chapter leaves that direction to the selection entry, which already covers it.
    val before = if (pointerPos > 0) {
        chapterList.subList(0, pointerPos + 1).map { it.chapter }
    } else {
        emptyList()
    }
    val after = if (pointerPos < chapterList.lastIndex) {
        chapterList.subList(pointerPos, chapterList.size).map { it.chapter }
    } else {
        emptyList()
    }
    return ReadRangeActions(
        beforeToRead = before.filter { !it.read },
        beforeToUnread = before.filter { it.read || it.lastPageRead > 0 },
        afterToRead = after.filter { !it.read },
        afterToUnread = after.filter { it.read || it.lastPageRead > 0 },
    )
}

/**
 * Remembers the selection the bottom bar last acted on, across recompositions.
 */
private class SelectionMemory {
    var items: List<ChapterList.Item> = emptyList()
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

/**
 * Folds [source] into [target] without rebuilding it, so that a single chapter changing only
 * touches that one chapter.
 *
 * The chapter list is a fresh instance on every state emission — a download ticking over is enough
 * — and replacing the whole list makes every visible cell recompose. That is what a swipe used to
 * look like: mark one chapter read and the whole grid flashed. As long as the chapters are still in
 * the same order, the entries are compared by value and only the ones that really changed are
 * written, which also leaves whatever order the user dragged them into alone.
 *
 * A different size or a different order means the sort or the filter changed, and then there is no
 * saving it: the list is replaced outright.
 */
private fun syncChapterItems(
    target: SnapshotStateList<ChapterList.Item>,
    source: List<ChapterList.Item>,
) {
    val sameOrder = target.size == source.size && target.indices.all { target[it].id == source[it].id }
    if (!sameOrder) {
        target.clear()
        target.addAll(source)
        return
    }
    for (i in source.indices) {
        if (target[i] != source[i]) target[i] = source[i]
    }
}

private fun LazyListScope.sharedChapterItems(
    manga: Manga,
    chapters: SnapshotStateList<ChapterList.Item>,
    localChapterCoversEnabled: Boolean,
    localChapterCoverGridEnabled: Boolean,
    reorderableState: ReorderableLazyListState,
    gridDragState: ChapterGridDragState,
    onReorder: () -> Boolean,
    isAnyChapterSelected: Boolean,
    isDraggingChapter: Boolean,
    onChapterDragMovedChanged: (Boolean) -> Unit,
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
    if (showLocalChapterCovers && localChapterCoverGridEnabled) {
        sharedChapterGridItems(
            manga = manga,
            chapters = chapters,
            isAnyChapterSelected = isAnyChapterSelected,
            gridDragState = gridDragState,
            onChapterClicked = onChapterClicked,
            onChapterSelected = onChapterSelected,
            markedChapterIds = markedChapterIds,
            goodDoujinChapterIds = goodDoujinChapterIds,
            chapterSwipeStartAction = effectiveSwipeStart,
            chapterSwipeEndAction = effectiveSwipeEnd,
            onChapterSwipe = onChapterSwipe,
        )
        return
    }
    items(
        items = chapters,
        key = { item -> "chapter-${item.id}" },
        contentType = { MangaScreenItem.CHAPTER },
    ) { item ->
        val haptic = LocalHapticFeedback.current
        val context = LocalContext.current

        val isRead = item.chapter.read
        val chapterProgress = item.chapter.toChapterProgressUi()
        val displayTitle = resolveChapterDisplayTitle(
            chapter = item.chapter,
            displayMode = manga.displayMode,
            showOriginalTitle = true,
            chapterNumberLabel = if (manga.displayMode == Manga.CHAPTER_DISPLAY_NUMBER) {
                stringResource(
                    MR.strings.display_mode_chapter,
                    formatChapterNumber(item.chapter.chapterNumber),
                )
            } else {
                null
            },
        )
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
            // The arbiter stays in charge while a selection is open. Disabling it there used to
            // hand the long press back to the row, whose timer knows nothing about the swipe
            // gesture hanging off the same press: a slow swipe would spend 400 ms inside the
            // slop, land the long press and fire it, which is what buzzed the device.
            val dragDetectionEnabled = manga.isLocal()
            // Read through an updated state so the gesture callbacks below, which may outlive
            // the composition that created them, always see the current selection mode.
            val selectionActive by rememberUpdatedState(isAnyChapterSelected)
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
                                    // drag as a long-press. While a drag is under way the flag
                                    // is left alone, otherwise the second finger of a two-finger
                                    // scroll would bring the bottom bar back mid-gesture.
                                    if (!reorderableState.isAnyItemDragging) {
                                        onChapterDragMovedChanged(false)
                                    }
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
                                                // Past the long-press timeout the reorderable
                                                // handle owns the gesture, so this is a real drag
                                                // rather than a swipe or a scroll.
                                                if (change.uptimeMillis - down.uptimeMillis >=
                                                    viewConfiguration.longPressTimeoutMillis
                                                ) {
                                                    onChapterDragMovedChanged(true)
                                                }
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
                            onChapterDragMovedChanged(false)
                            if (selectionActive) {
                                // Selection mode: every long press means select or deselect,
                                // wherever the finger landed.
                                if (!onReorder() && !dragMoved) {
                                    onChapterSelected(item, !item.selected, true)
                                }
                            } else if (!onReorder() && !dragMoved) {
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
                title = displayTitle.title,
                subtitle = displayTitle.originalTitle,
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
                // A drag hides the selection tint on every row: with the dragged row and the
                // selected ones all washed in the same colour there is nothing to tell apart.
                selected = item.selected && !isDraggingChapter,
                downloadIndicatorEnabled = !isAnyChapterSelected && !manga.isLocal(),
                downloadStateProvider = { item.downloadState },
                downloadProgressProvider = { item.downloadProgress },
                chapterSwipeStartAction = effectiveSwipeStart,
                chapterSwipeEndAction = effectiveSwipeEnd,
                goodDoujinMarked = item.chapter.id in goodDoujinChapterIds,
                flagMarked = item.chapter.id in markedChapterIds,
                // 同网格：last_modified_at 会在写 custom_order 时被触发器带起来，不能进封面 key。
                cover = if (showLocalChapterCovers) {
                    LocalChapterCover(
                        chapterId = item.chapter.id,
                        chapterUrl = item.chapter.url,
                        version = item.chapter.version xor item.chapter.dateUpload,
                    )
                } else {
                    null
                },
                // Local chapters let the drag handle own the long press in every case; the row
                // must not run a second timer of its own or the two race for the same press.
                onLongClick = if (manga.isLocal()) {
                    null
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
                // Local chapters read the long-press target from the drag handle instead, so the
                // title never runs a competing long-press timer of its own. With a selection
                // open, copying is out for every source: the long press only selects.
                copyTitleOnLongPress = !manga.isLocal() && !isAnyChapterSelected,
                onTitleBoundsChanged = if (manga.isLocal()) {
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
    chapters: SnapshotStateList<ChapterList.Item>,
    isAnyChapterSelected: Boolean,
    gridDragState: ChapterGridDragState,
    onChapterClicked: (Chapter) -> Unit,
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    markedChapterIds: Set<Long>,
    goodDoujinChapterIds: Set<Long>,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,
) {
    // 与列表一致：只有本地条目才有手动顺序可言。多选态不再把它关掉——一关掉，长按就回到卡片
    // 自己的计时器上，而那个计时器和同一按下里的横向滑动互不知情，慢一点右滑必然先撞上
    // 400ms 的长按；震动和选中态翻转就是从这里来的。
    val dragEnabled = manga.isLocal()
    // 按行号发牌，不再每次重组把整份章节列表切成一行行的子列表。切分每次都要重跑一遍全表：
    // 三千话就是新建一千个子列表、搬三千个引用，全在主线程。快滑标记只改一章也要付一次，
    // 这是「顿一下」的一半来源。行里按 slot 直接取章节，改动因此只落在真正变了的那一格上。
    val rowCount = (chapters.size + LOCAL_CHAPTER_GRID_COLUMNS - 1) / LOCAL_CHAPTER_GRID_COLUMNS
    items(
        count = rowCount,
        // 按位置做 key，不按内容。排序只换格子里装的是哪一章，按内容做 key 的话换章就等于
        // 整格重建：卡片刚拿到的滑入动画和它自己的位置状态会一起被丢掉，表现就是「弹一下」。
        key = { rowIndex -> "chapter-grid-row-$rowIndex" },
        contentType = { "chapter_grid_row" },
    ) { rowIndex ->
        val rowStart = rowIndex * LOCAL_CHAPTER_GRID_COLUMNS
        // 被拖起的卡片要盖在整张网格上，而不只是盖在它自己那一行：它是靠位移飞出本格的，而
        // LazyColumn 按行依次绘制，排在后面的行天然压在前面的行上，卡片自带的 zIndex 只在
        // 行内生效，往下拖就会被下面的行盖住。只有拖起中的那一行才需要抬起来。
        val draggedId = gridDragState.draggingId
        val rowHoldsDraggedCard = draggedId != null &&
            (0 until LOCAL_CHAPTER_GRID_COLUMNS).any { columnIndex ->
                chapters.getOrNull(rowStart + columnIndex)?.id == draggedId
            }
        Row(
            modifier = Modifier
                .zIndex(if (rowHoldsDraggedCard) 1f else 0f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (columnIndex in 0 until LOCAL_CHAPTER_GRID_COLUMNS) {
                val slot = rowStart + columnIndex
                val item = chapters.getOrNull(slot)
                if (item == null) {
                    // 末行凑不满整排，空位照样占一份宽度，剩下的卡片才不会被拉宽。
                    Spacer(modifier = Modifier.weight(1f))
                    continue
                }
                ChapterGridCard(
                    modifier = Modifier.weight(1f),
                    manga = manga,
                    item = item,
                    slot = slot,
                    gridDragState = gridDragState,
                    isAnyChapterSelected = isAnyChapterSelected,
                    dragEnabled = dragEnabled,
                    flagMarked = item.chapter.id in markedChapterIds,
                    goodDoujinMarked = item.chapter.id in goodDoujinChapterIds,
                    chapterSwipeStartAction = chapterSwipeStartAction,
                    chapterSwipeEndAction = chapterSwipeEndAction,
                    onChapterClicked = onChapterClicked,
                    onChapterSelected = onChapterSelected,
                    onChapterSwipe = onChapterSwipe,
                )
            }
        }
    }
}

/**
 * 一格的完整内容。拆成一个可组合函数是为了让 Compose 能跳过没变的格子。
 *
 * 章节列表是快照列表，读它的地方在整表任一格被改写时都会失效，所以一章变完，屏幕上每一行的
 * Row 都要重组一次、把三格全部重新叫起来。真正决定代价的是叫起来之后能不能跳过：参数全都比得
 * 上的一格会被原地跳过，一帧就只剩一次便宜的参数比对；比不上就得整格重画，连同封面重走一遍
 * 加载。回调每次重组现造的话永远比不上，所以这里全按 [item] 缓存住，标记一章就只重画一格。
 */
@Composable
private fun ChapterGridCard(
    manga: Manga,
    item: ChapterList.Item,
    slot: Int,
    gridDragState: ChapterGridDragState,
    isAnyChapterSelected: Boolean,
    dragEnabled: Boolean,
    flagMarked: Boolean,
    goodDoujinMarked: Boolean,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    onChapterClicked: (Chapter) -> Unit,
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayTitle = resolveChapterDisplayTitle(
        chapter = item.chapter,
        displayMode = manga.displayMode,
        // 网格三列只放得下一行名字，这里让「译名与原名」暂时退化成「仅译名」。
        // 布局放不下才降级，用户选的设置值不动，切回列表模式原名副行就回来了。
        showOriginalTitle = false,
        chapterNumberLabel = if (manga.displayMode == Manga.CHAPTER_DISPLAY_NUMBER) {
            stringResource(
                MR.strings.display_mode_chapter,
                formatChapterNumber(item.chapter.chapterNumber),
            )
        } else {
            null
        },
    )
    val chapterProgress = item.chapter.toChapterProgressUi()
    // 每个回调都按 item 缓存：见上面的说明，实例一换同排另两格就再也跳不过去。
    //
    // 上层传进来的回调每次重组都是新实例，直接拿它们当缓存键等于没缓存。走 updated state 把
    // 它们摘出键之外：lambda 只在 item 或选中态变了才重建，回调换实例不影响，调用时读到的
    // 仍然是最新的那一个。
    val currentOnChapterClicked by rememberUpdatedState(onChapterClicked)
    val currentOnChapterSelected by rememberUpdatedState(onChapterSelected)
    val currentOnChapterSwipe by rememberUpdatedState(onChapterSwipe)
    val downloadStateProvider = remember(item) { { item.downloadState } }
    val onClick = remember(item, isAnyChapterSelected) {
        {
            onChapterItemClick(
                chapterItem = item,
                isAnyChapterSelected = isAnyChapterSelected,
                onToggleSelection = { currentOnChapterSelected(item, !item.selected, false) },
                onChapterClicked = currentOnChapterClicked,
            )
        }
    }
    val onSwipe = remember(item) {
        { action: LibraryPreferences.ChapterSwipeAction -> currentOnChapterSwipe(item, action) }
    }
    val haptic = LocalHapticFeedback.current
    // 拖拽可用时改由网格的拖拽控制器接管长按，卡片自己不再触发，
    // 否则同一次长按会既选中又排序。
    val onLongClick = remember<(() -> Unit)?>(item, dragEnabled) {
        if (dragEnabled) {
            null
        } else {
            {
                currentOnChapterSelected(item, !item.selected, true)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }
    val onTitlePlaced = remember<((LayoutCoordinates) -> Unit)?>(item, dragEnabled) {
        if (dragEnabled) {
            { coordinates -> gridDragState.onTitlePlaced(item.id, coordinates) }
        } else {
            null
        }
    }
    // 这两个每帧都在绘制层被调用，缓存住同样是为了让没变的格子能跳过。
    val dragOffset = remember(item, dragEnabled) {
        { if (dragEnabled) gridDragState.offsetFor(item.id) else Offset.Zero }
    }
    val settleOffset = remember(slot, dragEnabled) {
        { if (dragEnabled) gridDragState.settleFor(slot) else Offset.Zero }
    }
    MangaChapterGridItem(
        modifier = modifier.then(
            if (dragEnabled) {
                Modifier.chapterGridSlotBounds(gridDragState, slot)
            } else {
                Modifier
            },
        ),
        title = displayTitle.title,
        // 不要拿 last_modified_at 参与封面 key：拖拽排序写 custom_order 也会被
        // update_last_modified_at_chapters 触发器一起改掉，整屏封面的 key 全变，
        // Coil 重新解码的那一瞬就是用户看到的白闪。
        cover = LocalChapterCover(
            chapterId = item.chapter.id,
            chapterUrl = item.chapter.url,
            version = item.chapter.version xor item.chapter.dateUpload,
        ),
        readProgress = chapterProgress?.let { progress ->
            stringResource(
                MR.strings.chapter_progress_ratio,
                progress.readPages,
                progress.totalPages,
            )
        },
        read = item.chapter.read,
        // 拖拽期间统一还原成未选中外观：被拖走的卡片和其余选中项如果都是同一片
        // 选中色，移动时根本分不清谁是谁。同一个 isDragging 也只在真正开始移动
        // 之后才置位，所以「长按原地松手改选中」不会让整格闪一下。
        selected = item.selected && !gridDragState.isDragging,
        bookmark = item.chapter.bookmark && !manga.isLocal(),
        goodDoujinMarked = goodDoujinMarked,
        flagMarked = flagMarked,
        chapterSwipeStartAction = chapterSwipeStartAction,
        chapterSwipeEndAction = chapterSwipeEndAction,
        downloadStateProvider = downloadStateProvider,
        dragging = dragEnabled && gridDragState.draggingId == item.id,
        dragOffset = dragOffset,
        // 被挤开的卡片从它原来那格滑到新格子，而不是直接跳过去。
        settleOffset = settleOffset,
        onLongClick = onLongClick,
        onChapterSwipe = onSwipe,
        onTitlePlaced = onTitlePlaced,
        onClick = onClick,
    )
}

private fun Modifier.twoFingerScrollDuringReorder(
    reorderableState: ReorderableLazyListState,
    listState: LazyListState,
    extraDragging: () -> Boolean = { false },
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

            if (!reorderableState.isAnyItemDragging && !extraDragging()) {
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

/**
 * 网格里长按后手指没有移动的落点分支，和列表视图同一套语义：按在标题上复制标题，
 * 按在封面（格子其余部分）上选中并唤出底部操作栏。震动在长按成立那一刻就已经发出。
 */
private fun onChapterGridLongPress(
    item: ChapterList.Item?,
    onTitle: Boolean,
    context: Context,
    onSelect: (ChapterList.Item) -> Unit,
) {
    if (item == null) return
    if (onTitle) {
        context.copyToClipboard(item.chapter.name, item.chapter.name)
    } else {
        onSelect(item)
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
