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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.History
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
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.ClearHistoryDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
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
import eu.kanade.tachiyomi.ui.manga.MangaScreen
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
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.MangaProgress
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.AutoDismissSnackbarHost
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

data class BrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
    private val isRoot: Boolean = false,
    private val scrollToTopRequests: StateFlow<Long>? = null,
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
        val refreshProgress by viewModel.isRefreshingChapters.collectAsStateWithLifecycle()
        val localSourceChanged by viewModel.localSourceChanged.collectAsStateWithLifecycle()
        val transferStatus by remember(context) { LocalChapterTransferJob.statusFlow(context) }
            .collectAsStateWithLifecycle(initialValue = null)
        val activeTransferStatus = transferStatus
            ?.takeUnless { it.state.isFinished }
            ?.takeIf { viewModel.source is LocalSource }
        val scrollToTopRequest = scrollToTopRequests?.collectAsStateWithLifecycle()?.value ?: 0L

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

        if (viewModel.source is StubSource) {
            MissingSourceScreen(
                source = viewModel.source,
                navigateUp = navigateUpAction,
            )
            return
        }

        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }

        val onHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }

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
                                navigator.push(MangaScreen(randomId, true))
                                // Hold the guard through the transition.
                                delay(600)
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
                                navigator.push(MangaScreen(result.mangaId, true))
                                delay(600)
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
                        onFilterClick = viewModel::openFilterSheet,
                        onRefreshChapters = viewModel::refreshAllChapters.takeIf {
                            viewModel.source is LocalSource && activeTransferStatus == null
                        },
                        onImportLocalChapters = {
                            if (viewModel.source is LocalSource) navigator.push(LocalImportScreen())
                        }.takeIf { viewModel.source is LocalSource && activeTransferStatus == null },
                        onClearHistoryClick = { viewModel.setDialog(BrowseSourceViewModel.Dialog.ClearHistory) },
                        onSearch = viewModel::search,
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
                            displayedListing == Listing.Latest -> LocalBrowseMode.UPDATED
                            !displayedQuery.isNullOrBlank() -> LocalBrowseMode.CUSTOM
                            else -> when (markFilter) {
                                MarkFilter.NONE -> LocalBrowseMode.ALL
                                MarkFilter.FLAGGED -> LocalBrowseMode.FLAGGED
                                MarkFilter.GOOD_DOUJIN -> LocalBrowseMode.GOOD_DOUJIN
                            }
                        }
                        LocalSourceControlBar(
                            mangaCount = currentViewMangaCount,
                            readingFilter = readingFilter,
                            browseMode = localBrowseMode,
                            onReadingFilterSelected = viewModel::setReadingFilter,
                            onBrowseModeSelected = { mode ->
                                when (mode) {
                                    LocalBrowseMode.ALL -> viewModel.setLocalListFilter(MarkFilter.NONE)
                                    LocalBrowseMode.FLAGGED -> viewModel.setLocalListFilter(MarkFilter.FLAGGED)
                                    LocalBrowseMode.GOOD_DOUJIN -> viewModel.setLocalListFilter(MarkFilter.GOOD_DOUJIN)
                                    LocalBrowseMode.UPDATED -> viewModel.showLocalLatest()
                                    LocalBrowseMode.CUSTOM -> Unit
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
            snackbarHost = { AutoDismissSnackbarHost(hostState = snackbarHostState) },
        ) { paddingValues ->
            BrowseSourceContent(
                source = viewModel.source,
                mangaList = mangaList,
                columns = viewModel.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = viewModel.displayMode,
                lastReadMangaId = lastReadMangaId,
                locateMangaId = null,
                favoriteIds = favoriteIds,
                progressFor = { mangaId, url ->
                    val ctx = progressContext
                    ctx.progressByMangaId[mangaId] ?: run {
                        val totalChapters = ctx.fsChapterCounts[url] ?: 0L
                        if (totalChapters > 0) {
                            MangaProgress(totalChapters, 0, 0, 0)
                        } else {
                            MangaProgress.EMPTY
                        }
                    }
                },
                isLastRead = { mangaId ->
                    progressContext.lastReadMangaId == mangaId
                },
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = onWebViewClick,
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = onHelpClick,
                onMangaClick = {
                    navigator.push(
                        MangaScreen(
                            it.id,
                            true,
                            randomCandidates = mangaList.itemSnapshotList.items
                                .filterIsInstance<BrowseSourceUiModel.Item>()
                                .map { manga -> manga.manga.id },
                        ),
                    )
                },
                onRandomManga = onRandomManga,
                onRandomGoodDoujin = onRandomGoodDoujin,
                onMangaLongClick = { manga ->
                    scope.launchIO {
                        val duplicates = viewModel.getDuplicateLibraryManga(manga)
                        when {
                            manga.id in favoriteIds -> viewModel.setDialog(
                                BrowseSourceViewModel.Dialog.RemoveManga(manga),
                            )
                            duplicates.isNotEmpty() -> viewModel.setDialog(
                                BrowseSourceViewModel.Dialog.AddDuplicateManga(manga, duplicates),
                            )
                            else -> viewModel.addFavorite(manga)
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
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
            is BrowseSourceViewModel.Dialog.RemoveManga -> {
                RemoveMangaDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        viewModel.changeMangaFavorite(dialog.manga)
                    },
                    mangaToRemove = dialog.manga,
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

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> viewModel.searchGenre(it.txt)
                        is SearchType.Text -> viewModel.search(it.txt)
                    }
                }
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
    onReadingFilterSelected: (ReadingFilter) -> Unit,
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
            BrowseFilterMenuChip(
                value = readingFilter,
                options = ReadingFilter.entries,
                selected = true,
                imageVector = ReadingFilter::imageVector,
                label = ReadingFilter::label,
                onSelect = onReadingFilterSelected,
            )
            LocalBrowseModeButtons(
                value = browseMode,
                onSelect = onBrowseModeSelected,
            )
            Text(
                text = stringResource(MR.strings.label_manga_count, mangaCount),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LocalBrowseModeButtons(
    value: LocalBrowseMode,
    onSelect: (LocalBrowseMode) -> Unit,
) {
    val options = LocalBrowseMode.entries.filterNot { it == LocalBrowseMode.CUSTOM }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
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
                                MaterialTheme.colorScheme.surfaceContainerHighest
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
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val colors = FilterChipDefaults.filterChipColors()
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Box {
            Surface(
                shape = FilterChipDefaults.shape,
                color = if (selected) colors.selectedContainerColor else colors.containerColor,
                contentColor = if (selected) colors.selectedLabelColor else colors.labelColor,
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                ),
                modifier = Modifier
                    .widthIn(max = 96.dp)
                    .clickable { menuExpanded = true },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(FilterChipDefaults.Height)
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
                        trailingIcon = if (option == value) {
                            {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                )
                            }
                        } else {
                            null
                        },
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

private enum class LocalBrowseMode {
    ALL,
    FLAGGED,
    GOOD_DOUJIN,
    UPDATED,
    CUSTOM,
}

private val LocalBrowseMode.imageVector: ImageVector
    get() = when (this) {
        LocalBrowseMode.ALL -> Icons.Outlined.SelectAll
        LocalBrowseMode.FLAGGED -> Icons.Outlined.Flag
        LocalBrowseMode.GOOD_DOUJIN -> Icons.Outlined.Favorite
        LocalBrowseMode.UPDATED -> Icons.Outlined.NewReleases
        LocalBrowseMode.CUSTOM -> Icons.Outlined.SortByAlpha
    }

private val LocalBrowseMode.label: StringResource
    get() = when (this) {
        LocalBrowseMode.ALL -> MR.strings.action_filter_all
        LocalBrowseMode.FLAGGED -> MR.strings.action_filter_marks
        LocalBrowseMode.GOOD_DOUJIN -> MR.strings.action_filter_good_doujin
        LocalBrowseMode.UPDATED -> MR.strings.label_recent_updates
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
