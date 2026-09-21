package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.ClearHistoryDialog
import eu.kanade.presentation.components.ConfirmDialog
import eu.kanade.presentation.components.DeleteLocalEntriesDialog
import eu.kanade.presentation.components.TransientNoticeHost
import eu.kanade.presentation.components.rememberTransientNoticeState
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.manga.LocalLibraryChapterTitleTranslationsHost
import eu.kanade.presentation.manga.components.LibraryBottomActionMenu
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.local.LocalChapterTransferJob
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.audio.AudioBrowseScreen
import eu.kanade.tachiyomi.ui.browse.OnlineSourceCenterScreen
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.ChapterRefreshProgress
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.Listing
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.MarkFilter
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.ReadingFilter
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.local.LocalImportScreen
import eu.kanade.tachiyomi.ui.manga.ChapterScope
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.manga.opensGoodDoujinJump
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.showSnackbarReplacing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.AutoDismissSnackbarHost
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

/**
 * Scroll-to-top requests for a source screen, provided by whoever owns the screen.
 *
 * A screen is serialized together with the navigator state, so a flow cannot travel as one of its
 * arguments: writing it breaks the whole save on the next stop.
 */
val LocalScrollToTopRequests = staticCompositionLocalOf<StateFlow<Long>?> { null }

/**
 * Identity of what a browse listing is showing, as opposed to which pages happen to be loaded.
 *
 * It answers "would this be a different list?", so it drives both the swap handling and the fast
 * scroller: a change opens the list at its top and re-anchors the thumb to the real position
 * instead of holding the one the previous listing left it at. Paging growth inside one listing
 * keeps the identity, so a landing page does not take the reader anywhere.
 */
private data class BrowseListKey(
    val listing: Listing,
    val readingFilter: ReadingFilter,
    val markFilter: MarkFilter,
    // Ordering rearranges the entries and re-derives the date headings, so it is part of what the
    // list shows: the same work sits at a different position, and the reader starts over.
    val sort: SourceModelFilter.Sort.Selection?,
)

data class BrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
    private val isRoot: Boolean = false,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val viewModel = viewModel<BrowseSourceViewModel>(
            factory = BrowseSourceViewModel.Factory,
            extras = CreationExtras {
                set(BrowseSourceViewModel.SOURCE_ID_KEY, sourceId)
                set(BrowseSourceViewModel.LISTING_QUERY_KEY, listingQuery)
            },
        )
        val context = LocalContext.current
        val state by viewModel.state.collectAsStateWithLifecycle()
        val progressContext by viewModel.progressContextState.collectAsStateWithLifecycle()
        val lastReadMangaId = progressContext.lastReadMangaId
        val currentViewMangaCount by viewModel.currentViewMangaCount.collectAsStateWithLifecycle()
        val readingFilter by viewModel.readingFilter.collectAsStateWithLifecycle()
        val markFilter by viewModel.markFilter.collectAsStateWithLifecycle()
        val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
        val selection by viewModel.selection.collectAsStateWithLifecycle()
        val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
        val coverUpdates by viewModel.mangaCoverUpdateStore.covers.collectAsStateWithLifecycle()
        val trailingSlotCount by viewModel.trailingSlotCount.collectAsStateWithLifecycle()
        val refreshProgress by viewModel.isRefreshingChapters.collectAsStateWithLifecycle()
        val sortUi by viewModel.sortUiState.collectAsStateWithLifecycle()
        val localSourceChanged by viewModel.localSourceChanged.collectAsStateWithLifecycle()
        val transferStatus by remember(context) { LocalChapterTransferJob.statusFlow(context) }
            .collectAsStateWithLifecycle(initialValue = null)
        val activeTransferStatus = transferStatus
            ?.takeUnless { it.state.isFinished }
            ?.takeIf { viewModel.source is LocalSource }
        val wholeListShown by viewModel.wholeListingShown.collectAsStateWithLifecycle()
        val scrollToTopRequest = LocalScrollToTopRequests.current
            ?.collectAsStateWithLifecycle()
            ?.value ?: 0L

        LaunchedEffect(viewModel) {
            viewModel.onScreenVisible()
        }
        DisposableEffect(viewModel) {
            onDispose(viewModel::onScreenHidden)
        }

        val navigator = LocalNavigator.currentOrThrow
        val navigateUpAction: () -> Unit = {
            if (state.toolbarQuery != null) {
                viewModel.exitSearch()
            } else if (!isRoot) {
                navigator.pop()
            }
        }
        val navigateUp = navigateUpAction.takeUnless { isRoot && state.toolbarQuery == null }

        BackHandler(enabled = state.toolbarQuery != null) {
            viewModel.exitSearch()
        }

        // Selection is the innermost state, so back leaves it before it leaves the screen.
        BackHandler(enabled = state.toolbarQuery == null && selectionMode) {
            viewModel.clearSelection()
        }

        if (viewModel.source is StubSource) {
            MissingSourceScreen(
                source = viewModel.source,
                navigateUp = navigateUpAction,
            )
            return
        }

        val scope = rememberCoroutineScope()
        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }
        val filterNotice = rememberTransientNoticeState()

        val onHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }

        var showChapterTitleTranslations by remember { mutableStateOf(false) }

        // Random entry points from the bottom-start button. A guard keeps a stray
        // double trigger from pushing two detail screens on top of each other.
        var randomInProgress by remember { mutableStateOf(false) }
        val onRandomManga: (() -> Unit)? = if (viewModel.source is LocalSource) {
            {
                if (!randomInProgress) {
                    randomInProgress = true
                    scope.launch {
                        try {
                            val randomId = viewModel.getRandomLocalMangaId()
                            if (randomId != null) {
                                // The random pick comes out of the filtered list, so it opens
                                // narrowed the same way a tap on that list does.
                                navigator.push(
                                    MangaScreen(
                                        mangaId = randomId,
                                        fromSource = true,
                                        chapterScope = markFilter.toChapterScope(),
                                    ),
                                )
                                // Hold the guard through the transition.
                                delay(150)
                            } else {
                                snackbarHostState.showSnackbarReplacing(
                                    context.stringResource(MR.strings.information_no_entries_found),
                                )
                            }
                        } finally {
                            randomInProgress = false
                        }
                    }
                }
            }
        } else {
            null
        }
        val onRandomGoodDoujin: (() -> Unit)? = if (viewModel.source is LocalSource) {
            {
                if (!randomInProgress) {
                    randomInProgress = true
                    scope.launch {
                        try {
                            val result = viewModel.getRandomGoodDoujinManga()
                            if (result.mangaId != null) {
                                // The pick comes from the good-doujin list, not from the list on
                                // screen, so the destination can only show a scope that list
                                // agrees with: good doujins when that is the mode in force, the
                                // whole work otherwise.
                                navigator.push(
                                    MangaScreen(
                                        mangaId = result.mangaId,
                                        fromSource = true,
                                        chapterScope = markFilter.toChapterScope().opensGoodDoujinJump(),
                                    ),
                                )
                                delay(150)
                            } else if (!result.hasEntries) {
                                snackbarHostState.showSnackbarReplacing(
                                    context.stringResource(MR.strings.good_doujin_list_empty),
                                )
                            } else {
                                snackbarHostState.showSnackbarReplacing(
                                    context.stringResource(MR.strings.good_doujin_list_no_others),
                                )
                            }
                        } finally {
                            randomInProgress = false
                        }
                    }
                }
            }
        } else {
            null
        }

        val onWebViewClick = f@{
            val source = viewModel.source as? HttpSource ?: return@f
            navigator.push(
                WebViewScreen(
                    url = source.getHomeUrl(),
                    initialTitle = source.name,
                    sourceId = source.id,
                ),
            )
        }

        LaunchedEffect(viewModel.source) {
            assistUrl = (viewModel.source as? HttpSource)?.getHomeUrl()
        }

        val mangaList = viewModel.mangaPagerFlowFlow.collectAsLazyPagingItems()
        val currentMangaList by rememberUpdatedState(mangaList)

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {},
                ) {
                    BrowseSourceToolbar(
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = viewModel::setToolbarQuery,
                        source = viewModel.source,
                        title = if (isRoot) stringResource(MR.strings.label_local_library) else null,
                        displayMode = viewModel.displayMode,
                        onDisplayModeChange = { viewModel.displayMode = it },
                        navigateUp = navigateUp,
                        onWebViewClick = onWebViewClick,
                        onSettingsClick = { navigator.push(SourcePreferencesScreen(sourceId)) },
                        onOpenAudio = { navigator.push(AudioBrowseScreen()) },
                        onOpenSources = if (isRoot) {
                            { navigator.push(OnlineSourceCenterScreen) }
                        } else {
                            null
                        },
                        onRefreshChapters = viewModel::refreshAllChapters.takeIf {
                            viewModel.source is LocalSource && activeTransferStatus == null
                        },
                        onImportLocalChapters = {
                            if (viewModel.source is LocalSource) navigator.push(LocalImportScreen())
                        }.takeIf { viewModel.source is LocalSource && activeTransferStatus == null },
                        onChapterTitleTranslations = { showChapterTitleTranslations = true }
                            .takeIf { viewModel.source is LocalSource },
                        onClearHistoryClick = { viewModel.setDialog(BrowseSourceViewModel.Dialog.ClearHistory) },
                        onSearch = viewModel::search,
                        selectedCount = selection.size,
                        onUnselectAll = viewModel::clearSelection,
                        // The loaded pages are the fallback universe: sources that can enumerate
                        // their whole listing (the local library) replace it inside the view model,
                        // so select-all also reaches entries no page has loaded yet.
                        onSelectAll = {
                            viewModel.selectAll(
                                mangaList.itemSnapshotList.items.mapNotNull {
                                    (it as? BrowseSourceUiModel.Item)?.manga
                                },
                            )
                        },
                        onInvertSelection = {
                            viewModel.invertSelection(
                                mangaList.itemSnapshotList.items.mapNotNull {
                                    (it as? BrowseSourceUiModel.Item)?.manga
                                },
                            )
                        },
                    )

                    if (viewModel.source is LocalSource) {
                        val displayedListing = (state.listing as? Listing.Search)
                            ?.takeIf { !it.query.isNullOrBlank() }
                            ?.previousListing
                            ?: state.listing
                        // 本地源在没有搜索词时（首次进入、清空搜索）搜索态等同于"全部"，
                        // 否则初始界面会落到 CUSTOM 而四个按钮一个都不亮。
                        val displayedQuery = (displayedListing as? Listing.Search)?.query
                        val localBrowseMode = when {
                            displayedListing == Listing.Latest -> LocalBrowseMode.CUSTOM
                            !displayedQuery.isNullOrBlank() -> LocalBrowseMode.CUSTOM
                            else -> when (markFilter) {
                                MarkFilter.NONE -> LocalBrowseMode.ALL
                                MarkFilter.FLAGGED -> LocalBrowseMode.FLAGGED
                                MarkFilter.GOOD_DOUJIN -> LocalBrowseMode.GOOD_DOUJIN
                                MarkFilter.NOT_IN_LIBRARY -> LocalBrowseMode.NOT_IN_LIBRARY
                            }
                        }
                        // The filter controls are icons only, so what a tap selected is easy to
                        // misread. A short notice names it and is gone again in a moment: it
                        // sizes itself to the text instead of stretching across the screen the
                        // way the snackbar host does, and it does not sit in the way of the list.
                        //
                        // The two controls are independent, so the notice reports the state the
                        // list is left in rather than the one that moved: clearing the reading
                        // status while a shelf filter is still on does not show everything.
                        //
                        // The notice replaces the one before it rather than queueing behind it,
                        // which matters when the buttons are tapped in quick succession.
                        //
                        // It says nothing about the date ordering. The ordering is the reader's own
                        // choice and a filter no longer moves it, so naming a date here would
                        // report something this tap did not do.
                        val announceFilters: (ReadingFilter, MarkFilter) -> Unit = { reading, mark ->
                            val active = buildList {
                                if (reading != ReadingFilter.ALL) {
                                    add(context.stringResource(reading.label))
                                }
                                mark.label?.let { add(context.stringResource(it)) }
                            }
                            filterNotice.show(
                                if (active.isEmpty()) {
                                    context.stringResource(MR.strings.filter_toast_cleared)
                                } else {
                                    context.stringResource(
                                        MR.strings.filter_toast_applied,
                                        active.joinToString(" · "),
                                    )
                                },
                            )
                        }
                        // The sort controls used to announce nothing at all, which left the one
                        // control whose meaning is not written on it silent. The notice names the
                        // key and the direction; for the date key it names the date too, since the
                        // chip has only two characters to give it.
                        val announceSort: (BrowseSourceViewModel.SortChange) -> Unit = { changed ->
                            val parts = buildList {
                                add(context.stringResource(sortChipLabel(changed.index)))
                                changed.dateAxis?.let { add(context.stringResource(it.shortName)) }
                                add(
                                    context.stringResource(
                                        if (changed.ascending) MR.strings.action_asc else MR.strings.action_desc,
                                    ),
                                )
                            }
                            filterNotice.show(
                                context.stringResource(
                                    MR.strings.sort_toast_applied,
                                    parts.joinToString(" · "),
                                ),
                            )
                        }
                        LocalSourceControlBar(
                            mangaCount = currentViewMangaCount,
                            readingFilter = readingFilter,
                            browseMode = localBrowseMode,
                            sort = sortUi.selection,
                            dateAxis = sortUi.dateAxis,
                            availableDateAxes = sortUi.availableDates,
                            onReadingFilterSelected = { filter ->
                                viewModel.setReadingFilter(filter)
                                announceFilters(filter, markFilter)
                            },
                            onSelectSortKey = { key ->
                                viewModel.setLocalSortKey(key)?.let(announceSort)
                            },
                            onSelectDateAxis = { axis ->
                                viewModel.setLocalDateAxis(axis)?.let(announceSort)
                            },
                            onToggleSortDirection = {
                                viewModel.toggleLocalSortDirection()?.let(announceSort)
                            },
                            onBrowseModeSelected = { mode ->
                                // Re-selecting the active mode clears it: with the whole cluster
                                // being one segmented control, a mode that cannot be turned off
                                // would leave no way back to the full list.
                                val next = when (mode) {
                                    LocalBrowseMode.ALL -> MarkFilter.NONE
                                    LocalBrowseMode.FLAGGED -> {
                                        MarkFilter.FLAGGED.takeUnless { localBrowseMode == LocalBrowseMode.FLAGGED }
                                            ?: MarkFilter.NONE
                                    }
                                    LocalBrowseMode.GOOD_DOUJIN -> {
                                        MarkFilter.GOOD_DOUJIN.takeUnless {
                                            localBrowseMode == LocalBrowseMode.GOOD_DOUJIN
                                        } ?: MarkFilter.NONE
                                    }
                                    LocalBrowseMode.NOT_IN_LIBRARY -> {
                                        MarkFilter.NOT_IN_LIBRARY.takeUnless {
                                            localBrowseMode == LocalBrowseMode.NOT_IN_LIBRARY
                                        } ?: MarkFilter.NONE
                                    }
                                    LocalBrowseMode.CUSTOM -> null
                                }
                                if (next != null) {
                                    viewModel.setLocalListFilter(next)
                                    announceFilters(readingFilter, next)
                                }
                            },
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = MaterialTheme.padding.small),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        ) {
                            FilterChip(
                                selected = state.listing == Listing.Popular,
                                onClick = {
                                    viewModel.resetFilters()
                                    viewModel.setListing(Listing.Popular)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Favorite,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.popular))
                                },
                            )
                            BrowseFilterMenuChip(
                                value = readingFilter,
                                options = ReadingFilter.entries,
                                selected = readingFilter != ReadingFilter.ALL,
                                imageVector = ReadingFilter::imageVector,
                                label = ReadingFilter::label,
                                onSelect = viewModel::setReadingFilter,
                            )
                            FilterChip(
                                selected = state.listing == Listing.Latest,
                                onClick = {
                                    viewModel.resetFilters()
                                    viewModel.setListing(Listing.Latest)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.NewReleases,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.label_recent_updates))
                                },
                            )
                            if (state.filters.isNotEmpty()) {
                                FilterChip(
                                    selected = state.listing is Listing.Search,
                                    onClick = viewModel::openFilterSheet,
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.FilterList,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(FilterChipDefaults.IconSize),
                                        )
                                    },
                                    label = {
                                        Text(text = stringResource(MR.strings.action_filter))
                                    },
                                )
                            }
                        }
                    }

                    val currentRefreshProgress = refreshProgress
                    if (currentRefreshProgress != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                Text(
                                    text = when (currentRefreshProgress) {
                                        is ChapterRefreshProgress.Checking -> {
                                            if (currentRefreshProgress.total > 0) {
                                                stringResource(
                                                    MR.strings.local_source_refresh_checking_progress,
                                                    currentRefreshProgress.completed,
                                                    currentRefreshProgress.total,
                                                )
                                            } else {
                                                stringResource(MR.strings.local_source_refresh_checking)
                                            }
                                        }
                                        is ChapterRefreshProgress.Updating -> {
                                            stringResource(
                                                MR.strings.refresh_all_chapters_in_progress,
                                                currentRefreshProgress.completed,
                                                currentRefreshProgress.total,
                                            )
                                        }
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                when (currentRefreshProgress) {
                                    is ChapterRefreshProgress.Checking -> LinearProgressIndicator(
                                        progress = {
                                            if (currentRefreshProgress.total == 0) {
                                                0f
                                            } else {
                                                currentRefreshProgress.completed.toFloat() /
                                                    currentRefreshProgress.total
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    is ChapterRefreshProgress.Updating -> LinearProgressIndicator(
                                        progress = {
                                            if (currentRefreshProgress.total == 0) {
                                                0f
                                            } else {
                                                currentRefreshProgress.completed.toFloat() /
                                                    currentRefreshProgress.total
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    } else if (activeTransferStatus != null) {
                        val transferProgress = when {
                            activeTransferStatus.totalBytes > 0L -> {
                                activeTransferStatus.copiedBytes.toFloat() / activeTransferStatus.totalBytes
                            }
                            activeTransferStatus.total > 0 -> {
                                activeTransferStatus.completed.toFloat() / activeTransferStatus.total
                            }
                            else -> null
                        }?.coerceIn(0f, 1f)
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                Text(
                                    text = if (activeTransferStatus.total > 0) {
                                        stringResource(
                                            MR.strings.local_transfer_in_progress,
                                            activeTransferStatus.completed,
                                            activeTransferStatus.total,
                                            activeTransferStatus.currentName,
                                        )
                                    } else {
                                        stringResource(MR.strings.local_transfer_preparing)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                if (transferProgress != null) {
                                    LinearProgressIndicator(
                                        progress = { transferProgress },
                                        modifier = Modifier.fillMaxWidth().height(2.dp),
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(2.dp),
                                    )
                                }
                            }
                        }
                    } else if (localSourceChanged) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = viewModel::refreshAllChapters),
                        ) {
                            Text(
                                text = stringResource(MR.strings.local_source_changed),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }

                    HorizontalDivider()
                }
            },
            // The notice shares the snackbar slot so it lands at the bottom edge, clear of the
            // list, and hands its height back when it goes. The column spans the width so both
            // stay centered on it whatever each one's own width turns out to be.
            snackbarHost = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TransientNoticeHost(state = filterNotice)
                    AutoDismissSnackbarHost(hostState = snackbarHostState)
                }
            },
            bottomBar = {
                if (viewModel.source is LocalSource) {
                    LibraryBottomActionMenu(
                        visible = selectionMode,
                        onChangeCategoryClicked = viewModel::openChangeCategoryDialogForSelection,
                        onMarkAsReadClicked = { viewModel.requestMarkSelectedRead(true) },
                        onMarkAsUnreadClicked = { viewModel.requestMarkSelectedRead(false) },
                        // Downloads and migration both address a source that owns the files;
                        // neither applies to a local library, so the row shows the shelf and
                        // deletion actions instead.
                        onDownloadClicked = null,
                        onDeleteClicked = viewModel::requestDeleteSelectedManga,
                        onMigrateClicked = null,
                        deleteTint = MaterialTheme.colorScheme.error,
                    )
                }
            },
        ) { paddingValues ->
            BrowseSourceContent(
                source = viewModel.source,
                mangaList = mangaList,
                columns = viewModel.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = viewModel.displayMode,
                lastReadMangaId = lastReadMangaId,
                locateMangaId = null,
                favoriteIds = favoriteIds,
                progressContext = progressContext,
                coverUpdates = coverUpdates,
                trailingSlotCount = trailingSlotCount,
                wholeList = wholeListShown,
                // Identity of what the list SHOWS, not of the pages in it: when it changes (a
                // filter, listing or sort swap), the list opens at its top, the fast scroller
                // re-anchors to the real position instead of holding the thumb where the previous
                // listing left it, and the old item keys no longer resolve. Paging growth inside
                // one listing keeps the key, so the sticky thumb still holds its ground.
                listKey = BrowseListKey(
                    listing = state.listing,
                    readingFilter = readingFilter,
                    markFilter = markFilter,
                    sort = sortUi.selection,
                ),
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = onWebViewClick,
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = onHelpClick,
                onMangaClick = { manga ->
                    if (selectionMode) {
                        viewModel.toggleSelection(manga.id)
                        return@BrowseSourceContent
                    }
                    // Hand over the whole filtered result set so random keeps walking what the
                    // list shows. Fall back to the loaded page only while the pool is still
                    // being resolved, so the button is never left without candidates.
                    val candidates = viewModel.filteredMangaIds.value
                        .ifEmpty {
                            mangaList.itemSnapshotList.items
                                .filterIsInstance<BrowseSourceUiModel.Item>()
                                .map { item -> item.manga.id }
                        }
                    navigator.push(
                        MangaScreen(
                            manga.id,
                            true,
                            randomCandidates = candidates,
                            // Under a mark filter the list is exactly the works carrying that
                            // mark; the work's own page opens showing those chapters.
                            chapterScope = markFilter.toChapterScope(),
                        ),
                    )
                },
                onRandomManga = onRandomManga,
                onRandomGoodDoujin = onRandomGoodDoujin,
                onMangaLongClick = { manga ->
                    // Picking several works at once is what putting them on a shelf needs, so a
                    // long press starts a selection instead of acting on the one entry. Adding a
                    // single work is still one tap away through the selection's own action.
                    val visible = mangaList.itemSnapshotList.items
                        .mapNotNull { (it as? BrowseSourceUiModel.Item)?.manga }
                    if (selectionMode) {
                        viewModel.toggleRangeSelection(manga.id, visible)
                    } else {
                        viewModel.toggleSelection(manga.id)
                    }
                },
                selectedIds = selection,
                // Everything in the local library is browsed to be read, not to be discovered
                // and shelved, so graying out the works already on a shelf just dims most of the
                // grid for no gain. The shelf badge still tells them apart.
                dimInLibraryCovers = viewModel.source !is LocalSource,
                onRefreshChapters = viewModel::refreshAllChapters,
                scrollToTopRequest = scrollToTopRequest,
            )
        }

        val onDismissRequest = { viewModel.setDialog(null) }
        when (val dialog = state.dialog) {
            BrowseSourceViewModel.Dialog.ClearHistory -> {
                ClearHistoryDialog(
                    message = context.stringResource(
                        if (viewModel.source is LocalSource) {
                            MR.strings.clear_current_list_history_confirmation
                        } else {
                            MR.strings.clear_source_history_confirmation
                        },
                    ),
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        viewModel.clearReadingHistory()
                        scope.launch {
                            snackbarHostState.showSnackbarReplacing(
                                context.stringResource(MR.strings.clear_reading_history_completed),
                            )
                        }
                    },
                )
            }
            is BrowseSourceViewModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = viewModel::resetFilters,
                    onFilter = { viewModel.search(filters = state.filters) },
                    onUpdate = viewModel::setFilters,
                )
            }
            is BrowseSourceViewModel.Dialog.AddDuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { viewModel.setDialog(BrowseSourceViewModel.Dialog.Migrate(dialog.manga, it)) },
                )
            }

            is BrowseSourceViewModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            is BrowseSourceViewModel.Dialog.ChangeMangaCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        viewModel.changeMangaFavorite(dialog.manga)
                        viewModel.moveMangaToCategories(dialog.manga, include)
                    },
                )
            }

            is BrowseSourceViewModel.Dialog.ChangeSelectionCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, exclude ->
                        viewModel.setSelectedMangaCategories(include, exclude)
                    },
                    // The local library's picker offers the default shelf, which the other
                    // callers hide because they only ever move between named categories.
                    includeDefaultCategory = true,
                )
            }

            is BrowseSourceViewModel.Dialog.DeleteSelection -> {
                DeleteLocalEntriesDialog(
                    title = stringResource(MR.strings.local_delete_collection_title),
                    entryNames = dialog.titles,
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        viewModel.deleteSelectedLocalManga()
                        onDismissRequest()
                    },
                )
            }

            is BrowseSourceViewModel.Dialog.MarkSelectionRead -> {
                ConfirmDialog(
                    text = stringResource(
                        if (dialog.read) {
                            MR.strings.mark_selection_read_confirmation
                        } else {
                            MR.strings.mark_selection_unread_confirmation
                        },
                        dialog.count,
                    ),
                    confirmText = stringResource(
                        if (dialog.read) MR.strings.action_mark_as_read else MR.strings.action_mark_as_unread,
                    ),
                    onConfirm = {
                        viewModel.markSelectedRead(dialog.read)
                        onDismissRequest()
                    },
                    onDismiss = onDismissRequest,
                )
            }
            else -> {}
        }

        LaunchedEffect(viewModel) {
            viewModel.events.receiveAsFlow().collect {
                snackbarHostState.showSnackbarReplacing(context.stringResource(MR.strings.manga_added_library))
            }
        }

        LaunchedEffect(viewModel) {
            viewModel.chapterRefreshEvents.receiveAsFlow().collect { result ->
                // The local index is already rebuilt at this point. Refresh the currently
                // collected paging generation so deleted cards disappear without navigating
                // away and back; this does not trigger another filesystem scan.
                if (!result.storageUnavailable) {
                    currentMangaList.refresh()
                }
                val message = when {
                    result.storageUnavailable -> {
                        context.stringResource(MR.strings.local_source_refresh_unavailable)
                    }
                    result.changedManga == 0 -> {
                        context.stringResource(MR.strings.local_source_refresh_no_changes)
                    }
                    result.newChapters == 0 -> {
                        context.stringResource(
                            MR.strings.local_source_refresh_completed,
                            result.changedManga,
                        )
                    }
                    else -> {
                        context.stringResource(
                            MR.strings.local_source_refresh_completed_with_new,
                            result.changedManga,
                            result.newChapters,
                        )
                    }
                }
                snackbarHostState.showSnackbarReplacing(
                    message,
                )
            }
        }

        LaunchedEffect(viewModel) {
            viewModel.deleteCompleted.collect { result ->
                // The rows are gone from disk, so the served pages still hold them; refresh so the
                // deleted cards disappear without leaving and re-entering the tab.
                currentMangaList.refresh()
                val message = when {
                    result.deleted == 0 -> context.stringResource(MR.strings.local_delete_failed)
                    result.failed.isNotEmpty() -> context.stringResource(
                        MR.strings.local_delete_partial,
                        result.deleted,
                        result.failed.size,
                    )
                    else -> context.stringResource(MR.strings.local_delete_success, result.deleted)
                }
                snackbarHostState.showSnackbarReplacing(message)
            }
        }

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> viewModel.searchGenre(it.txt)
                        is SearchType.Text -> viewModel.search(it.txt)
                    }
                }
        }

        // The settings screen offers the same import/export. Only the local source has a local
        // library to translate, so the host is skipped entirely for online sources.
        if (viewModel.source is LocalSource) {
            LocalLibraryChapterTitleTranslationsHost(
                visible = showChapterTitleTranslations,
                onDismissRequest = { showChapterTitleTranslations = false },
            )
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}

@Composable
private fun LocalSourceControlBar(
    mangaCount: Int,
    readingFilter: ReadingFilter,
    browseMode: LocalBrowseMode,
    sort: SourceModelFilter.Sort.Selection?,
    dateAxis: LocalDateAxis?,
    availableDateAxes: List<LocalDateAxis>,
    onReadingFilterSelected: (ReadingFilter) -> Unit,
    onSelectSortKey: (Int) -> Unit,
    onSelectDateAxis: (LocalDateAxis) -> Unit,
    onToggleSortDirection: () -> Unit,
    onBrowseModeSelected: (LocalBrowseMode) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The whole cluster outgrows a narrow phone once the sort chip is in, so it takes the
            // slack the count leaves and scrolls there instead of pushing the count off screen.
            // With room to spare the cluster still sits at the start and the gap stays on the right.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrowseFilterMenuChip(
                    value = readingFilter,
                    options = ReadingFilter.entries,
                    // 当前筛选项已由标签文案直接表达（全部/剩余/在看/看完），chip 保持中性外观，不再常亮。
                    selected = false,
                    imageVector = ReadingFilter::imageVector,
                    label = ReadingFilter::label,
                    onSelect = onReadingFilterSelected,
                    // 与右侧筛选栏的 36dp 等高、同灰色常态，避免“全部”筛选按钮显得突兀。
                    height = 36.dp,
                    neutralPill = true,
                )
                LocalBrowseModeButtons(
                    value = browseMode,
                    onSelect = onBrowseModeSelected,
                )
                sort?.let {
                    LocalSortControl(
                        sort = it,
                        dateAxis = dateAxis,
                        availableDateAxes = availableDateAxes,
                        onSelectKey = onSelectSortKey,
                        onSelectDateAxis = onSelectDateAxis,
                        onToggleDirection = onToggleSortDirection,
                    )
                }
            }
            Text(
                text = stringResource(MR.strings.label_manga_count, mangaCount),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun LocalBrowseModeButtons(
    value: LocalBrowseMode,
    onSelect: (LocalBrowseMode) -> Unit,
) {
    // "All" is the state where none of the three is on, so it is not a button of its own: the
    // reading-status chip beside this group already spells "all" out for its own dimension, and
    // two identically named controls next to each other read as a duplicate.
    val options = LocalBrowseMode.entries.filterNot {
        it == LocalBrowseMode.ALL || it == LocalBrowseMode.CUSTOM
    }
    // 左右两枚胶囊（阅读筛选、排序）都用 surfaceContainerHighest；这一组四个按钮是同一
    // 个分段控件的内页，往回退一级到 surfaceContainer，既不跟隔壁胶囊撞成同一种灰，
    // 又仍比整条筛选栏（surfaceContainerLow）深一点，分段轮廓不会糊掉。
    val groupBackground = MaterialTheme.colorScheme.surfaceContainer
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            color = groupBackground,
            shape = RoundedCornerShape(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                options.forEach { option ->
                    val selected = option == value
                    TooltipBox(
                        positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = {
                            PlainTooltip {
                                Text(stringResource(option.label))
                            }
                        },
                        state = rememberTooltipState(),
                    ) {
                        Surface(
                            color = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                groupBackground
                            },
                            contentColor = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            IconButton(
                                onClick = { onSelect(option) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = option.imageVector,
                                    contentDescription = stringResource(option.label),
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> BrowseFilterMenuChip(
    value: T,
    options: List<T>,
    selected: Boolean,
    imageVector: (T) -> ImageVector,
    label: (T) -> StringResource,
    onSelect: (T) -> Unit,
    height: Dp = FilterChipDefaults.Height,
    neutralPill: Boolean = false,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val colors = FilterChipDefaults.filterChipColors()
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Box {
            Surface(
                shape = if (neutralPill) RoundedCornerShape(7.dp) else FilterChipDefaults.shape,
                color = when {
                    neutralPill -> MaterialTheme.colorScheme.surfaceContainerHighest
                    selected -> colors.selectedContainerColor
                    else -> colors.containerColor
                },
                contentColor = when {
                    neutralPill -> MaterialTheme.colorScheme.onSurfaceVariant
                    selected -> colors.selectedLabelColor
                    else -> colors.labelColor
                },
                border = if (neutralPill) {
                    null
                } else {
                    FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                    )
                },
                modifier = Modifier
                    .widthIn(max = 96.dp)
                    .clickable { menuExpanded = true },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(height)
                        .padding(FilterChipDefaults.ContentPadding),
                ) {
                    Icon(
                        imageVector = imageVector(value),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                    Spacer(modifier = Modifier.width(FilterChipDefaults.HorizontalSpacing))
                    Text(
                        text = stringResource(label(value)),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(text = stringResource(label(option))) },
                        leadingIcon = {
                            Icon(
                                imageVector = imageVector(option),
                                contentDescription = null,
                            )
                        },
                        modifier = selectedRowModifier(option == value),
                        onClick = {
                            menuExpanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

/** Sort keys of the local library, in the order they are offered in the menu. */
private val LOCAL_SORT_KEYS = listOf(
    LocalSource.ORDER_BY_TITLE,
    LocalSource.ORDER_BY_CHAPTER_COUNT,
    LocalSource.ORDER_BY_DATE,
)

/**
 * The name of a sort key, with the date spelled out when the key is the date.
 *
 * Used where there is room for the whole thing: the menu, and the chip's long-press tooltip. The
 * chip's own face is shorter - see [sortChipLabel] - because it sits in a row with two other
 * controls and a manga count.
 */
@Composable
private fun sortLabel(index: Int, dateAxis: LocalDateAxis?): String = when (index) {
    LocalSource.ORDER_BY_DATE -> dateAxis?.let { stringResource(it.fullName) } ?: stringResource(MR.strings.date)
    LocalSource.ORDER_BY_CHAPTER_COUNT -> stringResource(MR.strings.local_filter_order_by_count)
    else -> stringResource(MR.strings.title)
}

/**
 * The name of a sort key on the chip itself.
 *
 * Two characters whatever the key is, so the chip keeps one width: 标题 and 篇数 are already two,
 * and the dates are named to match (导入 / 在看 / 看完 / 标记) rather than spelled out. A longer name
 * would run past the label's cap and into the row's horizontal scroll, which is exactly what
 * happened when the date was written out in full.
 *
 * A resource rather than a resolved string so the sort notice can name the key too, from a place
 * that is not a composition.
 */
private fun sortChipLabel(index: Int): StringResource = when (index) {
    LocalSource.ORDER_BY_DATE -> MR.strings.date
    LocalSource.ORDER_BY_CHAPTER_COUNT -> MR.strings.local_filter_order_by_count
    else -> MR.strings.title
}

/** Two-character name of the date, for the chip and for notices. */
private val LocalDateAxis.shortName: StringResource
    get() = when (this) {
        LocalDateAxis.Imported -> MR.strings.date_axis_short_imported
        LocalDateAxis.Opened -> MR.strings.date_axis_short_last_read
        LocalDateAxis.Finished -> MR.strings.date_axis_short_finished
        LocalDateAxis.Flagged -> MR.strings.date_axis_short_flagged
        LocalDateAxis.GoodDoujin -> MR.strings.date_axis_short_good_doujin
    }

/** Spelled-out name of the date, for the menu and the chip's tooltip. */
private val LocalDateAxis.fullName: StringResource
    get() = when (this) {
        LocalDateAxis.Imported -> MR.strings.date_axis_full_imported
        LocalDateAxis.Opened -> MR.strings.date_axis_full_last_read
        LocalDateAxis.Finished -> MR.strings.date_axis_full_finished
        LocalDateAxis.Flagged -> MR.strings.date_axis_full_flagged
        LocalDateAxis.GoodDoujin -> MR.strings.date_axis_full_good_doujin
    }

/**
 * A calendar with [badge] tucked into its bottom-right corner, or a plain calendar when null.
 *
 * The badge sits on a disc painted in [discColor] - the colour of whatever surface the icon is on -
 * which hides the calendar's own lines from behind it. That is what makes it read as a punched-out
 * corner rather than two icons stacked, and it needs no blending because every surface this is used
 * on is a flat colour.
 */
@Composable
private fun CalendarWithBadge(
    badge: ImageVector?,
    discColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Icon(
            imageVector = Icons.Outlined.Event,
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
        )
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(10.dp)
                    .background(discColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = badge,
                    contentDescription = null,
                    modifier = Modifier.size(8.dp),
                )
            }
        }
    }
}

/**
 * The icon for a date: a calendar badged with the event that date tracks.
 *
 * The badges deliberately reuse the icons the filter controls use (History for 在看, Done for 看完,
 * Flag for 标记), so one concept keeps one symbol wherever it appears.
 */
@Composable
private fun DateAxisIcon(axis: LocalDateAxis, discColor: Color, modifier: Modifier = Modifier) {
    CalendarWithBadge(
        badge = when (axis) {
            LocalDateAxis.Imported -> null
            LocalDateAxis.Opened -> Icons.Outlined.History
            LocalDateAxis.Finished -> Icons.Outlined.Done
            LocalDateAxis.Flagged -> Icons.Outlined.Flag
            LocalDateAxis.GoodDoujin -> Icons.Outlined.Favorite
        },
        discColor = discColor,
        modifier = modifier,
    )
}

/**
 * Row background for the menu entry that is the one in force, or none when it is not.
 *
 * The chosen entry is marked by tinting the row, not by tinting its text or adding a trailing
 * tick: the tint reads as a highlighted band, and a tick would spend a column of width on every row
 * whether or not it is the chosen one.
 *
 * Edge to edge, with no inner frame. The tint covers the whole row, so the label and icon keep the
 * same left edge as every other row; an inset band shifted the chosen row's content sideways and
 * made it look misaligned against its neighbours.
 *
 * [MaterialTheme.colorScheme.secondaryContainer] is the app's own "chosen" colour - the segmented
 * controls use it for the active segment - so a menu selection looks like every other selection.
 */
@Composable
private fun selectedRowModifier(selected: Boolean): Modifier = if (selected) {
    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
} else {
    Modifier
}

/**
 * Ordering control for the local library: the sort key and the direction, one pill cut in two.
 *
 * Two segments rather than two chips because they are two halves of one thing - what to order by,
 * and which way - and the seam is what says so. The interaction is cut the same way: the left half
 * opens the menu, the right half flips the direction on the spot. The direction used to live inside
 * the menu as a re-tap of the active row, which cost two taps and a popup for the half that changes
 * most often.
 *
 * The menu lists the keys, and the date key opens a second level listing the dates the current
 * filters actually offer - see [availableDateAxes]. A second level rather than six flat rows keeps
 * the menu the size it has always been. There is no way back up from the second level on purpose:
 * the menu is one tap away again, and a back row spends the top of a short menu on navigation.
 */
@Composable
private fun LocalSortControl(
    sort: SourceModelFilter.Sort.Selection,
    dateAxis: LocalDateAxis?,
    availableDateAxes: List<LocalDateAxis>,
    onSelectKey: (Int) -> Unit,
    onSelectDateAxis: (LocalDateAxis) -> Unit,
    onToggleDirection: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // Which level of the menu is showing: the keys, or the dates the date key offers. Reset when
    // the menu opens rather than when it closes, so the level stays put while the menu fades out
    // instead of snapping back to the keys under the reader's finger.
    var pickingDate by remember { mutableStateOf(false) }
    val fullLabel = sortLabel(sort.index, dateAxis)
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TooltipBox(
                    positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = {
                        PlainTooltip {
                            Text(fullLabel)
                        }
                    },
                    state = rememberTooltipState(),
                ) {
                    Box(
                        modifier = Modifier
                            .clickable {
                                pickingDate = false
                                menuExpanded = true
                            }
                            .padding(start = 9.dp, end = 7.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(36.dp),
                        ) {
                            if (sort.index == LocalSource.ORDER_BY_DATE) {
                                DateAxisIcon(
                                    axis = dateAxis ?: LocalDateAxis.Imported,
                                    discColor = containerColor,
                                    modifier = Modifier.size(19.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = localSortIcon(sort.index),
                                    contentDescription = fullLabel,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (sort.index == LocalSource.ORDER_BY_DATE) {
                                    stringResource((dateAxis ?: LocalDateAxis.Imported).shortName)
                                } else {
                                    stringResource(sortChipLabel(sort.index))
                                },
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 64.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            if (pickingDate) {
                                val menuColor = MenuDefaults.containerColor
                                availableDateAxes.forEach { axis ->
                                    DropdownMenuItem(
                                        text = { Text(text = stringResource(axis.fullName)) },
                                        leadingIcon = {
                                            DateAxisIcon(
                                                axis = axis,
                                                discColor = menuColor,
                                                modifier = Modifier.size(19.dp),
                                            )
                                        },
                                        modifier = selectedRowModifier(axis == dateAxis),
                                        onClick = {
                                            menuExpanded = false
                                            onSelectDateAxis(axis)
                                        },
                                    )
                                }
                            } else {
                                LOCAL_SORT_KEYS.forEach { key ->
                                    DropdownMenuItem(
                                        text = { Text(text = stringResource(sortChipLabel(key))) },
                                        leadingIcon = {
                                            // The date key opens a level of its own, so its icon
                                            // carries a "more" dot where the others carry nothing:
                                            // the row looks like an entry into something.
                                            if (key == LocalSource.ORDER_BY_DATE) {
                                                CalendarWithBadge(
                                                    badge = Icons.Outlined.MoreHoriz,
                                                    discColor = MenuDefaults.containerColor,
                                                    modifier = Modifier.size(19.dp),
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = localSortIcon(key),
                                                    contentDescription = null,
                                                )
                                            }
                                        },
                                        modifier = selectedRowModifier(key == sort.index),
                                        onClick = {
                                            // The date key opens its own level rather than
                                            // selecting anything: which date to use is the choice.
                                            if (key == LocalSource.ORDER_BY_DATE) {
                                                pickingDate = true
                                            } else {
                                                menuExpanded = false
                                                onSelectKey(key)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                VerticalDivider(
                    modifier = Modifier.height(20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                TooltipBox(
                    positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = {
                        PlainTooltip {
                            Text(
                                stringResource(
                                    if (sort.ascending) MR.strings.action_asc else MR.strings.action_desc,
                                ),
                            )
                        }
                    },
                    state = rememberTooltipState(),
                ) {
                    Box(
                        modifier = Modifier
                            .clickable(onClick = onToggleDirection)
                            .size(36.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (sort.ascending) {
                                Icons.Outlined.ArrowUpward
                            } else {
                                Icons.Outlined.ArrowDownward
                            },
                            contentDescription = stringResource(
                                if (sort.ascending) MR.strings.action_asc else MR.strings.action_desc,
                            ),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun localSortIcon(index: Int): ImageVector = when (index) {
    LocalSource.ORDER_BY_DATE -> Icons.Outlined.Event
    LocalSource.ORDER_BY_CHAPTER_COUNT -> Icons.Outlined.FormatListNumbered
    else -> Icons.Outlined.SortByAlpha
}

private enum class LocalBrowseMode {
    ALL,
    GOOD_DOUJIN,
    FLAGGED,
    NOT_IN_LIBRARY,
    CUSTOM,
}

private val LocalBrowseMode.imageVector: ImageVector
    get() = when (this) {
        LocalBrowseMode.ALL -> Icons.Outlined.SelectAll
        LocalBrowseMode.FLAGGED -> Icons.Outlined.Flag
        LocalBrowseMode.GOOD_DOUJIN -> Icons.Outlined.Favorite
        LocalBrowseMode.NOT_IN_LIBRARY -> Icons.Outlined.BookmarkRemove
        LocalBrowseMode.CUSTOM -> Icons.Outlined.SortByAlpha
    }

private val LocalBrowseMode.label: StringResource
    get() = when (this) {
        LocalBrowseMode.ALL -> MR.strings.action_filter_all
        LocalBrowseMode.FLAGGED -> MR.strings.action_filter_marks
        LocalBrowseMode.GOOD_DOUJIN -> MR.strings.action_filter_good_doujin
        LocalBrowseMode.NOT_IN_LIBRARY -> MR.strings.action_filter_not_in_library
        LocalBrowseMode.CUSTOM -> MR.strings.action_sort
    }

private val ReadingFilter.imageVector: ImageVector
    get() = when (this) {
        ReadingFilter.ALL -> Icons.Outlined.SelectAll
        ReadingFilter.UNREAD -> Icons.Outlined.RemoveDone
        ReadingFilter.IN_PROGRESS -> Icons.Outlined.History
        ReadingFilter.FINISHED -> Icons.Outlined.Done
    }

private val ReadingFilter.label: StringResource
    get() = when (this) {
        ReadingFilter.ALL -> MR.strings.action_filter_all
        ReadingFilter.UNREAD -> MR.strings.action_filter_not_finished
        ReadingFilter.IN_PROGRESS -> MR.strings.action_filter_in_progress
        ReadingFilter.FINISHED -> MR.strings.action_filter_finished
    }

/** Name of the mark filter a button stands for, null for "no filter" (the full list). */
private val MarkFilter.label: StringResource?
    get() = when (this) {
        MarkFilter.NONE -> null
        MarkFilter.FLAGGED -> MR.strings.action_filter_marks
        MarkFilter.GOOD_DOUJIN -> MR.strings.action_filter_good_doujin
        MarkFilter.NOT_IN_LIBRARY -> MR.strings.action_filter_not_in_library
    }
