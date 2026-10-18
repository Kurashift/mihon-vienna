package eu.kanade.presentation.audio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.audio.components.AudioBottomAction
import eu.kanade.presentation.audio.components.AudioBottomActionMenu
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.data.audio.AudioBackend
import eu.kanade.tachiyomi.data.audio.AudioQualityMode
import eu.kanade.tachiyomi.data.audio.TagRef
import eu.kanade.tachiyomi.data.audio.Work
import eu.kanade.tachiyomi.data.search.SearchHistoryScope
import eu.kanade.tachiyomi.ui.audio.AudioAuthState
import eu.kanade.tachiyomi.ui.audio.AudioBackendState
import eu.kanade.tachiyomi.ui.audio.AudioBrowseState
import eu.kanade.tachiyomi.ui.audio.AudioBrowseTab
import eu.kanade.tachiyomi.ui.audio.AudioSort
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun AudioBrowseContent(
    state: AudioBrowseState,
    title: String,
    /** Sort of the tab on screen, which the sort sheet edits. */
    sort: AudioSort,
    /** Sort of the work-list tab, which is what that tab is named after. */
    workListSort: AudioSort,
    auth: AudioAuthState,
    backend: AudioBackendState,
    audioQuality: AudioQualityMode,
    showTabs: Boolean,
    bottomBar: @Composable () -> Unit,
    onClickWork: (Work) -> Unit,
    onClickHistory: () -> Unit,
    onClickCategories: () -> Unit,
    /** Opens one tag's works. Tapping a tag leaves the row it sits on, so it is not a work tap. */
    onClickTag: (String) -> Unit,
    navigateUp: () -> Unit,
    onSearch: (String) -> Unit,
    onExitSearch: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSortChange: (AudioSort) -> Unit,
    /** Turns the subtitle filter on or off; the state is [AudioBrowseState.subtitlesOnly]. */
    onSetSubtitles: (Boolean) -> Unit,
    onSelectTab: (AudioBrowseTab) -> Unit,
    onLogin: (String, String) -> Unit,
    /** Creates an account and signs into it; the backend answers with a token either way. */
    onRegister: (String, String) -> Unit,
    onClearAuthError: () -> Unit,
    /** Opens the account page, where the password is changed, the email is managed and the
     * session is ended. */
    onOpenAccount: () -> Unit,
    /** Switches the section to another mirror of the audio backend. */
    onSelectBackend: (String) -> Unit,
    onMeasureBackends: () -> Unit,
    onCycleAudioQuality: () -> Unit,
    /** Drops the selected works from the account's collection, one row per work. */
    onUncollectSelected: (Set<Long>) -> Unit,
) {
    var searchQuery by remember { mutableStateOf<String?>(null) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var showBackendSheet by remember { mutableStateOf(false) }
    // Only the favourites tab is made of rows the account owns, so it is the only one that can be
    // collected or dropped in bulk. Selection lives here rather than in the view model: it is
    // screen state, and the one action it produces is the only thing that needs the account.
    //
    // Mode and selection are tracked separately for the same reason as the playlist: unticking the
    // last work must not drop the user out of the mode they are still working in.
    var favoriteSelectionMode by remember { mutableStateOf(false) }
    var selectedWorkIds by remember { mutableStateOf(emptySet<Long>()) }
    var showUncollectDialog by remember { mutableStateOf(false) }
    val favoritesSelectable = state.tab == AudioBrowseTab.FAVORITES && auth.username != null
    val favoriteIds = remember(state.works) { state.works.mapTo(mutableSetOf()) { it.id } }

    fun exitFavoriteSelection() {
        favoriteSelectionMode = false
        selectedWorkIds = emptySet()
    }

    fun toggleWorkSelection(id: Long) {
        favoriteSelectionMode = true
        selectedWorkIds = if (id in selectedWorkIds) selectedWorkIds - id else selectedWorkIds + id
    }

    // Leaving the tab, signing out or clearing the list all end a selection that can no longer act
    // on anything. An empty selection is a normal state while selecting, so only a non-empty one
    // that no longer matches a row is dropped.
    LaunchedEffect(favoritesSelectable) {
        if (!favoritesSelectable) exitFavoriteSelection()
    }
    LaunchedEffect(state.works) {
        if (favoriteSelectionMode && selectedWorkIds.isNotEmpty() && selectedWorkIds.none { it in favoriteIds }) {
            selectedWorkIds = emptySet()
        }
    }
    BackHandler(enabled = favoriteSelectionMode) {
        exitFavoriteSelection()
    }

    val audioQualityAction = if (showTabs) {
        AppBar.OverflowAction(
            title = stringResource(
                if (audioQuality == AudioQualityMode.FLUENT_FIRST) {
                    MR.strings.audio_quality_fluent
                } else {
                    MR.strings.audio_quality_high
                },
            ),
            onClick = onCycleAudioQuality,
        )
    } else {
        null
    }

    BackHandler(enabled = searchQuery != null) {
        searchQuery = null
        onExitSearch()
    }

    LaunchedEffect(auth.username, showLoginDialog) {
        if (showLoginDialog && auth.username != null) showLoginDialog = false
    }

    // Hosts are timed the first time the sheet opens and the numbers are kept for the session;
    // the sheet's own remeasure button is how they are refreshed. Measuring on every visit to the
    // page would spend four round trips on a choice most users make once.
    LaunchedEffect(showBackendSheet) {
        if (showBackendSheet && backend.latency.isEmpty()) onMeasureBackends()
    }

    Scaffold(
        topBar = { scrollBehavior ->
            Column {
                SearchToolbar(
                    searchQuery = searchQuery,
                    onChangeSearchQuery = { query ->
                        searchQuery = query
                        if (query == null) onExitSearch()
                    },
                    onSearch = onSearch,
                    historyKey = SearchHistoryScope.AUDIO,
                    // Without a tab row the list is filtered to one dictionary entry, and a
                    // keyword typed here narrows it further rather than replacing it — so the
                    // field names the entry it is searching inside, instead of the whole library.
                    placeholderText = if (showTabs) {
                        stringResource(MR.strings.audio_search_hint)
                    } else {
                        stringResource(MR.strings.audio_search_hint_in_category, title)
                    },
                    titleContent = { AppBarTitle(title) },
                    navigateUp = navigateUp,
                    // While collecting in bulk the search field steps aside for the counter, and the
                    // selection actions live in the bar at the bottom.
                    actionModeCounter = selectedWorkIds.size,
                    actionMode = favoriteSelectionMode,
                    onCancelActionMode = ::exitFavoriteSelection,
                    // Select-all and invert live in the top bar, rightmost, as they do in every
                    // other selection screen; only the removal action sits at the bottom.
                    actionModeActions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_select_all),
                                    icon = Icons.Outlined.SelectAll,
                                    onClick = { selectedWorkIds = favoriteIds.toSet() },
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_select_inverse),
                                    icon = Icons.Outlined.FlipToBack,
                                    onClick = { selectedWorkIds = favoriteIds - selectedWorkIds },
                                ),
                            ),
                        )
                    },
                    actions = {
                        if (favoriteSelectionMode) return@SearchToolbar
                        IconButton(onClick = onClickHistory) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = stringResource(MR.strings.audio_history),
                            )
                        }
                        AppBarActions(
                            listOfNotNull(
                                // With tabs on screen, sorting lives on the tabs themselves and
                                // this entry is redundant. Screens without a tab row (a circle,
                                // VA or tag result) would otherwise have no way to sort at all.
                                if (!showTabs) {
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.audio_sort),
                                        onClick = { showSortSheet = true },
                                    )
                                } else {
                                    null
                                },
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.audio_categories),
                                    onClick = onClickCategories,
                                ),
                                // A filter, not a sort. The entry reads as a state — the value
                                // after the colon is what the list is doing right now — and the
                                // tap switches it, which is the same shape as the quality entry
                                // above. No check mark: the menu is as wide as its longest entry,
                                // and a marker pinned to the right edge of a short one just frames
                                // the empty space next to it.
                                //
                                // Left out of the favourites tab: that list is held locally, so
                                // there is no request the flag could narrow and the entry would sit
                                // there doing nothing when tapped.
                                if (state.tab != AudioBrowseTab.FAVORITES) {
                                    AppBar.OverflowAction(
                                        title = stringResource(
                                            if (state.subtitlesOnly) {
                                                MR.strings.audio_filter_subtitles
                                            } else {
                                                MR.strings.audio_filter_all
                                            },
                                        ),
                                        onClick = { onSetSubtitles(!state.subtitlesOnly) },
                                    )
                                } else {
                                    null
                                },
                                audioQualityAction,
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.audio_backend),
                                    onClick = { showBackendSheet = true },
                                ),
                                // The account entry is the same slot either way: signing in while
                                // signed out, the account page while signed in. The toolbar keeps
                                // one icon (history) and the menu carries everything else, so a
                                // narrow screen does not lose the title to a row of icons.
                                if (auth.username == null) {
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.audio_login),
                                        onClick = { showLoginDialog = true },
                                    )
                                } else {
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.audio_account),
                                        onClick = onOpenAccount,
                                    )
                                },
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
                if (showTabs) {
                    val availableTabs = AudioBrowseTab.entries
                    PrimaryTabRow(
                        selectedTabIndex = availableTabs.indexOf(state.tab).coerceAtLeast(0),
                    ) {
                        availableTabs.forEachIndexed { index, tab ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // The work-list tab carries its own sort as its name instead of a
                                // fixed one. It is the sort of that tab, not of the one on screen:
                                // the row has to keep naming it while the favorites tab is open.
                                val label = if (tab == AudioBrowseTab.LATEST) {
                                    workListSort.tabLabel
                                } else {
                                    tab.label
                                }
                                Tab(
                                    selected = state.tab == tab,
                                    onClick = {
                                        // Re-tapping the active tab is the shortcut to the sort
                                        // sheet, which is why the toolbar has no filter icon.
                                        if (state.tab == tab && tab.sortable) {
                                            showSortSheet = true
                                        } else {
                                            onSelectTab(tab)
                                        }
                                    },
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(stringResource(label))
                                            // Re-tapping to sort is invisible otherwise. The caret
                                            // also doubles as a hint for which tabs have a sort at
                                            // all: the backend feeds do not.
                                            if (tab.sortable) {
                                                Icon(
                                                    imageVector = Icons.Outlined.ArrowDropDown,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                if (index < availableTabs.lastIndex) {
                                    AudioTabDivider()
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (favoriteSelectionMode) {
                AudioBottomActionMenu(
                    visible = true,
                    actions = listOf(
                        AudioBottomAction(
                            title = stringResource(MR.strings.audio_favorite_remove),
                            icon = Icons.Outlined.Delete,
                            enabled = selectedWorkIds.isNotEmpty(),
                            onClick = {
                                if (selectedWorkIds.size > 1) {
                                    showUncollectDialog = true
                                } else {
                                    onUncollectSelected(selectedWorkIds)
                                    exitFavoriteSelection()
                                }
                            },
                        ),
                    ),
                )
            } else {
                bottomBar()
            }
        },
    ) { contentPadding ->
        // One scroll position per tab and keyword, kept above the branches below. Searching
        // reloads the list, which composes the loading screen (and the empty screen, while the
        // keyword still matches nothing) in place of the grid; a state created inside that grid
        // went with it and every keyword came back at the top. Keying by keyword also keeps the
        // list the user was browsing untouched by the results scrolling in its own slot.
        //
        // The keyword is [AudioBrowseState.listQuery], the one the rows were built for, not the one
        // in the search field: switching slots the instant the field changes would hand the old
        // rows to a state that does not describe them, and the browsing list's offset would be
        // clamped to the length of the result set it was briefly attached to.
        //
        // Saveable rather than remembered for the same reason: opening a work pushes a details
        // page on top of this one, which disposes this composition.
        val gridStates = rememberSaveable(saver = gridStatesSaver) {
            mutableMapOf<WorkListSlot, LazyGridState>()
        }
        when {
            state.loading -> LoadingScreen(Modifier.padding(contentPadding))
            state.error -> {
                val message = state.errorMessage?.let { "${stringResource(MR.strings.audio_load_failed)}: $it" }
                    ?: stringResource(MR.strings.audio_load_failed)
                EmptyScreen(
                    message = message,
                    modifier = Modifier.padding(contentPadding),
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.audio_retry,
                            icon = Icons.Outlined.Refresh,
                            onClick = onRefresh,
                        ),
                    ),
                )
            }
            state.works.isEmpty() -> EmptyScreen(
                stringRes = MR.strings.audio_empty,
                modifier = Modifier.padding(contentPadding),
            )
            else -> {
                val gridState = gridStates.getOrPut(WorkListSlot(state.tab, state.listQuery.orEmpty())) {
                    LazyGridState()
                }
                // Favourites open at the top only when a work actually lands there — the one just
                // collected, which is the first row. Coming back from a work that was merely
                // played must keep the position the user left, so the first row is compared
                // against the one seen last instead of the tab alone triggering the jump. While a
                // keyword is up the rows are a filtered subset, so the comparison is left alone:
                // otherwise clearing the search would read as a new first row and jump to the top.
                var favoritesFirstId by rememberSaveable { mutableStateOf(FAVORITES_NO_FIRST_ROW) }
                LaunchedEffect(state.tab, state.query, state.works.firstOrNull()?.id) {
                    if (state.tab != AudioBrowseTab.FAVORITES || state.query != null) return@LaunchedEffect
                    val firstId = state.works.firstOrNull()?.id ?: return@LaunchedEffect
                    if (favoritesFirstId != FAVORITES_NO_FIRST_ROW && firstId != favoritesFirstId) {
                        gridState.scrollToItem(0)
                    }
                    favoritesFirstId = firstId
                }
                // Read through rememberUpdatedState so appending a page no longer tears down and
                // restarts the scroll observer — the flow only needs the latest count, not to be
                // resubscribed whenever it changes.
                val worksCount by rememberUpdatedState(state.works.size)
                LaunchedEffect(gridState) {
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                        .distinctUntilChanged()
                        .collect { lastIndex ->
                            if (lastIndex != null && lastIndex >= worksCount - LOAD_MORE_THRESHOLD) {
                                onLoadMore()
                            }
                        }
                }
                PullRefresh(
                    refreshing = state.refreshing,
                    enabled = !state.loadingMore,
                    onRefresh = onRefresh,
                    indicatorPadding = contentPadding,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(1),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        // Cards touch the hairline below rather than float in a wide gap: the
                        // rule is what separates two works, so the gap only has to keep them
                        // off it. spacing is zero and the rule carries its own padding, so the
                        // two read as one rhythm instead of gap-then-gap.
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        itemsIndexed(state.works, key = { _, work -> work.id }) { index, work ->
                            Column {
                                if (index > 0) WorkSeparator()
                                WorkGridItem(
                                    work = work,
                                    onClick = {
                                        if (favoriteSelectionMode) {
                                            toggleWorkSelection(work.id)
                                        } else {
                                            onClickWork(work)
                                        }
                                    },
                                    onClickTag = onClickTag,
                                    selectionMode = favoriteSelectionMode,
                                    selected = work.id in selectedWorkIds,
                                    onLongClick = if (favoritesSelectable) {
                                        { toggleWorkSelection(work.id) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                        if (state.loadingMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                )
                            }
                        } else if (state.loadMoreError) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                TextButton(
                                    onClick = onLoadMore,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                ) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(MR.strings.audio_retry))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSortSheet) {
        AudioSortSheet(
            current = sort,
            // Collection order only means something over the account's own list.
            options = AudioSort.entries.filter {
                !it.favoritesOnly || state.tab == AudioBrowseTab.FAVORITES
            },
            onDismiss = { showSortSheet = false },
            onSelect = {
                onSortChange(it)
                showSortSheet = false
            },
        )
    }

    // Only for more than one work: a lone card was picked deliberately, and asking again would be
    // a second confirmation for one decision.
    if (showUncollectDialog) {
        AlertDialog(
            onDismissRequest = { showUncollectDialog = false },
            title = { Text(stringResource(MR.strings.audio_favorite_remove)) },
            text = {
                Text(stringResource(MR.strings.audio_favorite_remove_selected_confirm, selectedWorkIds.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUncollectDialog = false
                        onUncollectSelected(selectedWorkIds)
                        exitFavoriteSelection()
                    },
                ) {
                    Text(stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUncollectDialog = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    if (showBackendSheet) {
        AudioBackendSheet(
            state = backend,
            onSelect = { apiBaseUrl ->
                onSelectBackend(apiBaseUrl)
                showBackendSheet = false
            },
            onRemeasure = onMeasureBackends,
            onDismiss = { showBackendSheet = false },
        )
    }

    if (showLoginDialog) {
        LoginDialog(
            auth = auth,
            onDismiss = { showLoginDialog = false },
            onLogin = onLogin,
            onRegister = onRegister,
            onClearAuthError = onClearAuthError,
        )
    }
}

@Composable
internal fun WorkGridItem(
    work: Work,
    onClick: () -> Unit,
    onClickTag: (String) -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        // The card is its own tap target rather than the `onClick` overload, so a long press can
        // start a selection the way it does on every row in the app.
        modifier = Modifier
            .fillMaxWidth()
            .height(WORK_CARD_HEIGHT)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        // Fixed total height, so no card ever sits taller or shorter than its neighbours.
        // The tag row is a strip along the bottom edge — full card width, under the cover as
        // well as the text — which is both where it reads as its own band and where it has the
        // most room to show tags. The strip is reserved even when a work carries no tags:
        // dropping it grew the cover into the freed space, which made those rows read as a
        // different, larger card than every other one in the list.
        shape = RoundedCornerShape(WORK_CARD_CORNER),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Takes whatever the tag strip does not, which is the same on every card.
                    // The cover follows this row's height rather than setting it.
                    .weight(1f),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        // Square off the row's height, so every cover is the same size and the
                        // row's own height is the only thing that sets it.
                        .aspectRatio(1f, matchHeightConstraintsFirst = true)
                        // The bottom corner is left square: the tag strip below means the cover's
                        // bottom edge always lands mid-card, where a rounded corner would punch
                        // a notch out of the card's fill.
                        .clip(RoundedCornerShape(topStart = WORK_CARD_CORNER)),
                ) {
                    AsyncImage(
                        model = work.mainCoverUrl ?: work.thumbnailCoverUrl ?: work.samCoverUrl,
                        contentDescription = work.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    // A card has no leading slot to put a checkbox in, so it sits on the cover —
                    // the same corner the eye already starts from on every row.
                    if (selectionMode) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(2.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 10.dp, vertical = WORK_CARD_PADDING),
                ) {
                    Text(
                        text = work.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = WORK_TITLE_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Consumes whatever is left so the stats stay pinned to the bottom of this
                    // band, directly above the tag strip.
                    Spacer(Modifier.weight(1f))
                    WorkStats(work)
                }
            }
            // Always rendered, empty or not: the band is what keeps every card the same shape.
            WorkTagRow(work.tags, onClickTag)
        }
    }
}

/**
 * One scrollable line of tags along the bottom of a card, spanning its full width.
 *
 * Kept in the tree when a work has no tags — an empty band instead of no band at all — so the
 * cover above it cannot grow into the space and tower over the other cards.
 */
@Composable
private fun WorkTagRow(tags: List<TagRef>, onClickTag: (String) -> Unit) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(WORK_TAG_ROW_HEIGHT),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tags.size) { index ->
            WorkTag(tags[index].name, onClick = { onClickTag(tags[index].name) })
        }
    }
}

@Composable
private fun WorkStats(work: Work) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(3.dp))
        Text(
            text = buildString {
                append(work.rateAverage2dp ?: "-")
                if (work.rateCount > 0) append(" (").append(formatCompactCount(work.rateCount)).append(')')
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = Icons.Outlined.Download,
            contentDescription = stringResource(MR.strings.audio_sales),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(3.dp))
        Text(
            text = formatCompactCount(work.dlCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * One tag, which opens that tag's works instead of the work it is printed on.
 *
 * The border stays neutral while the label takes the primary colour: the strip can carry five or
 * six of these on every card in the list, and filling them all with `primaryContainer` the way
 * the single detail header does would turn each card into a block of colour. The label is what
 * reads as a link, and the ripple confirms the tap.
 */
@Composable
private fun WorkTag(name: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = name,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

internal fun formatCompactCount(value: Int): String {
    if (value < 1_000) return value.toString()
    val unit = if (value >= 1_000_000) 1_000_000 else 1_000
    val suffix = if (unit == 1_000_000) "M" else "K"
    val whole = value / unit
    val decimal = value % unit / (unit / 10)
    return if (decimal == 0 || whole >= 100) "$whole$suffix" else "$whole.$decimal$suffix"
}

@Composable
private fun AudioSortSheet(
    current: AudioSort,
    options: List<AudioSort>,
    onDismiss: () -> Unit,
    onSelect: (AudioSort) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(MR.strings.audio_sort),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 12.dp),
            )
            // Grouped so accessibility reports the entries as one radio set rather than five
            // unrelated clickable rows.
            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { option ->
                    SortOptionRow(
                        option = option,
                        selected = option == current,
                        onSelect = { onSelect(option) },
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/**
 * One entry of the sort sheet: a full-width pill that fills with secondaryContainer when active.
 *
 * The pill is what carries the selection — the radio only confirms it — so a tap anywhere in the
 * row lands on the same target the eye is already reading.
 */
@Composable
private fun SortOptionRow(
    option: AudioSort,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SORT_OPTION_HEIGHT)
            .clip(CircleShape)
            .background(containerColor)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        Text(
            text = stringResource(option.label),
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * Hairline between two work cards.
 *
 * Cards sit on surfaceContainerLow against a surface list, so the fill alone only hints at an
 * edge. The rule finishes the separation without claiming a divider's worth of attention: it is
 * inset from both screen edges, alpha-dimmed, and given a few dp of air on each side so the two
 * cards read as neighbours rather than as rows in a table.
 */
@Composable
private fun WorkSeparator() {
    Column {
        Spacer(Modifier.height(WORK_SEPARATOR_GAP))
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = WORK_SEPARATOR_INSET),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = WORK_SEPARATOR_ALPHA),
        )
        Spacer(Modifier.height(WORK_SEPARATOR_GAP))
    }
}

/**
 * The account dialog: one form, two states.
 *
 * Registration lives inside the sign-in panel rather than behind its own entry, because the
 * backend exposes both through the same credential pair and answers either with a token — the
 * only difference is which endpoint it posts to. Switching is a text button in the button row, so
 * the panel keeps exactly two fields and never grows a second screen.
 *
 * That button is named after the state it leads to — 注册 while signing in, 登录 while registering
 * — rather than phrasing the choice as a question ("没有账号？注册"). The dialog already says which
 * half it is in its title, so the question mark only added words to read before the tap.
 */
@Composable
internal fun LoginDialog(
    auth: AudioAuthState,
    onDismiss: () -> Unit,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String) -> Unit,
    onClearAuthError: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    val canSubmit = !auth.loading &&
        name.trim().length >= MIN_AUTH_FIELD_LENGTH &&
        password.length >= MIN_AUTH_FIELD_LENGTH

    fun submit() {
        if (!canSubmit) return
        if (register) onRegister(name.trim(), password) else onLogin(name.trim(), password)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (register) MR.strings.audio_register_title else MR.strings.audio_login_title,
                ),
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(MR.strings.audio_username)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(MR.strings.audio_password)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    // Registration signs the account in on the spot, so a typo here is not a
                    // failed attempt to retype later: it is a password nobody knows, on an account
                    // with no reset flow. One tap to read it back is cheaper than a second field.
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                                contentDescription = null,
                            )
                        }
                    },
                )
                if (register) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(MR.strings.audio_register_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (auth.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // The backend's own sentence when it refused the credentials — "用户 xxx
                        // 已存在." says where to go next, which no generic line can. A transport
                        // failure has no such sentence and falls back to the localised one.
                        text = if (auth.errorFromServer && !auth.error.isNullOrBlank()) {
                            auth.error
                        } else {
                            stringResource(
                                if (register) MR.strings.audio_register_failed else MR.strings.audio_login_failed,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = ::submit,
                enabled = canSubmit,
            ) {
                if (auth.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        stringResource(
                            if (register) MR.strings.audio_register else MR.strings.audio_login,
                        ),
                    )
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        register = !register
                        onClearAuthError()
                    },
                ) {
                    // Named after the state it switches to, not the state the user is in.
                    Text(
                        stringResource(
                            if (register) MR.strings.audio_login else MR.strings.audio_register,
                        ),
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            }
        },
    )
}

private const val LOAD_MORE_THRESHOLD = 8

/**
 * The host picker: one row per site the publisher lists, cheapest first.
 *
 * Latency is the only thing that separates these hosts — same catalogue, same accounts, same
 * reviews, and a token minted on one is accepted by the others — so the sheet is a list of round
 * trips rather than a list of features. A host that did not answer is named as unreachable instead
 * of showing no number at all: "unreachable" and "not measured yet" call for different reactions.
 */
@Composable
internal fun AudioBackendSheet(
    state: AudioBackendState,
    onSelect: (String) -> Unit,
    onRemeasure: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.strings.audio_backend),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRemeasure, enabled = !state.measuring) {
                    Text(stringResource(MR.strings.audio_backend_remeasure))
                }
            }
            AudioBackend.sortedByLatency(state.latency).forEach { backend ->
                val selected = backend.apiBaseUrl.equals(state.current, ignoreCase = true)
                val latency = state.latency[backend.apiBaseUrl]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, onClick = { onSelect(backend.apiBaseUrl) })
                        .height(SORT_OPTION_HEIGHT)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = backend.siteHost,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = when {
                            latency != null -> stringResource(MR.strings.audio_backend_latency, latency)
                            state.measuring -> stringResource(MR.strings.audio_backend_measuring)
                            // Nothing measured yet: an empty cell rather than a wrong verdict.
                            state.latency.isEmpty() -> ""
                            else -> stringResource(MR.strings.audio_backend_unreachable)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Shortest username and password the backend accepts. Shared with the account page's password
 * dialog so the two cannot drift into disagreeing about what is long enough.
 */
internal const val MIN_AUTH_FIELD_LENGTH = 5

/** Minimum row height of a sort-sheet entry: the M3 single-line list tile height. */
private val SORT_OPTION_HEIGHT = 56.dp

/** Corner radius of the card, and of the cover where it meets one. */
private val WORK_CARD_CORNER = 6.dp

/** Height of every work card: the cover band plus the tag strip. */
private val WORK_CARD_HEIGHT = 152.dp

/**
 * Height of the tag strip along the bottom edge. Chip is 20dp (labelSmall plus its own padding);
 * the rest is air above and below it, which is what separates the strip from the stats above.
 */
private val WORK_TAG_ROW_HEIGHT = 28.dp

/** Vertical padding of the text column beside the cover. */
private val WORK_CARD_PADDING = 7.dp

/**
 * Title lines a card can show. Three fit in the cover band; the [Spacer] below the title absorbs
 * the leftover on a short title rather than stretching the card.
 */
private const val WORK_TITLE_MAX_LINES = 3

/** Air above and below the hairline between two work cards. */
private val WORK_SEPARATOR_GAP = 3.dp

/** How far the hairline stops short of the screen edge, so it reads as a rule and not a border. */
private val WORK_SEPARATOR_INSET = 4.dp

/** The rule is a hint, not a divider: dimmed well past outlineVariant's own weight. */
private const val WORK_SEPARATOR_ALPHA = 0.45f

/** Gap left above a tab separator so it does not butt against the toolbar edge. */
private val DIVIDER_TOP_INSET = 10.dp

/** Gap left below a tab separator so it clears the selected-tab indicator. */
private val DIVIDER_BOTTOM_INSET = 6.dp

/**
 * Vertical rule between category tabs, shared by the browse and category screens so both tab rows
 * read the same way.
 *
 * Spans most of the row height so it reads as a column rule rather than a stub floating beside the
 * label. The insets are deliberately uneven: it hangs lower than it sits high, so it reads as a
 * stem landing on the row's own bottom divider instead of a bar centred in the row. The bottom
 * inset has to clear the selected-tab indicator, which is drawn at the very bottom edge, so it
 * cannot go to zero. Both use outlineVariant, so the corner matches.
 */
@Composable
internal fun AudioTabDivider() {
    Box(
        Modifier
            .fillMaxHeight()
            .padding(
                top = DIVIDER_TOP_INSET,
                bottom = DIVIDER_BOTTOM_INSET,
            )
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * One list the work grid can show: a tab, and the keyword it is showing (empty while browsing).
 *
 * The two dimensions are separate slots rather than one, so a keyword's results scroll on their
 * own and the list the user was browsing keeps the position they left it at.
 */
private data class WorkListSlot(val tab: AudioBrowseTab, val query: String)

/**
 * Saved form of the work list's scroll positions: one (tab, keyword, index, offset) row each,
 * rebuilt into [LazyGridState]s on restore.
 *
 * The states themselves belong to a composition that can be disposed two ways — a details page
 * pushed on top of the list, or the grid replaced by the loading screen while a keyword loads —
 * so only their primitives travel through the saved state.
 */
private val gridStatesSaver = listSaver<MutableMap<WorkListSlot, LazyGridState>, Any>(
    save = { states ->
        states.flatMap { (slot, state) ->
            listOf(
                slot.tab.name,
                slot.query,
                state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset,
            )
        }
    },
    restore = { saved ->
        mutableMapOf<WorkListSlot, LazyGridState>().apply {
            saved.chunked(GRID_STATE_SAVED_FIELDS).forEach { fields ->
                val tab = AudioBrowseTab.entries.firstOrNull { it.name == fields[0] } ?: return@forEach
                put(
                    WorkListSlot(tab, fields[1] as String),
                    LazyGridState(fields[2] as Int, fields[3] as Int),
                )
            }
        }
    },
)

/** Fields one slot occupies in [gridStatesSaver]: tab, keyword, first visible index and offset. */
private const val GRID_STATE_SAVED_FIELDS = 4

/** Stands in for "no first row seen yet". Work ids are positive, so no row can carry it. */
private const val FAVORITES_NO_FIRST_ROW = -1L
