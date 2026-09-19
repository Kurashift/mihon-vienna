package eu.kanade.presentation.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.history.components.HistoryBottomActionMenu
import eu.kanade.presentation.history.components.HistoryItem
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.data.search.SearchHistoryScope
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import kotlinx.datetime.LocalDate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.AutoDismissSnackbarHost
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun HistoryScreen(
    state: HistoryViewModel.State,
    snackbarHostState: SnackbarHostState,
    onSearchQueryChange: (String?) -> Unit,
    onClickCover: (mangaId: Long) -> Unit,
    onClickResume: (chapterId: Long) -> Unit,
    onClickDeleteSwipe: (historyId: Long) -> Unit,
    onToggleSelection: (historyId: Long, selected: Boolean) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onAddToLibrary: () -> Unit,
    onDialogChange: (HistoryViewModel.Dialog?) -> Unit,
) {
    BackHandler(enabled = state.selection.isNotEmpty(), onBack = onClearSelection)
    Scaffold(
        topBar = { scrollBehavior ->
            SearchToolbar(
                titleContent = { AppBarTitle(stringResource(MR.strings.history)) },
                searchQuery = state.searchQuery,
                onChangeSearchQuery = onSearchQueryChange,
                historyKey = SearchHistoryScope.READING_HISTORY,
                actions = {
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.pref_clear_history),
                                icon = Icons.Outlined.DeleteSweep,
                                onClick = {
                                    onDialogChange(HistoryViewModel.Dialog.DeleteAll)
                                },
                            ),
                        ),
                    )
                },
                actionModeCounter = state.selection.size,
                onCancelActionMode = onClearSelection,
                actionModeActions = {
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_all),
                                icon = Icons.Outlined.SelectAll,
                                onClick = onSelectAll,
                            ),
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_inverse),
                                icon = Icons.Outlined.FlipToBack,
                                onClick = onInvertSelection,
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            HistoryBottomActionMenu(
                visible = state.selection.isNotEmpty(),
                onAddToLibraryClicked = onAddToLibrary,
                onDeleteClicked = onDeleteSelection,
            )
        },
        snackbarHost = { AutoDismissSnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        state.list.let {
            if (it == null) {
                LoadingScreen(Modifier.padding(contentPadding))
            } else if (it.isEmpty()) {
                val msg = if (!state.searchQuery.isNullOrEmpty()) {
                    MR.strings.no_results_found
                } else {
                    MR.strings.information_no_recent_manga
                }
                EmptyScreen(
                    stringRes = msg,
                    modifier = Modifier.padding(contentPadding),
                )
            } else {
                HistoryScreenContent(
                    history = it,
                    selection = state.selection,
                    contentPadding = contentPadding,
                    onClickCover = { history -> onClickCover(history.mangaId) },
                    onClickResume = { history -> onClickResume(history.chapterId) },
                    onClickDeleteSwipe = onClickDeleteSwipe,
                    onToggleSelection = onToggleSelection,
                )
            }
        }
    }
}

@Composable
private fun HistoryScreenContent(
    history: List<HistoryUiModel>,
    selection: Set<Long>,
    contentPadding: PaddingValues,
    onClickCover: (HistoryWithRelations) -> Unit,
    onClickResume: (HistoryWithRelations) -> Unit,
    onClickDeleteSwipe: (historyId: Long) -> Unit,
    onToggleSelection: (historyId: Long, selected: Boolean) -> Unit,
) {
    FastScrollLazyColumn(
        contentPadding = contentPadding,
    ) {
        items(
            items = history,
            key = {
                when (it) {
                    is HistoryUiModel.Header -> "history-header-${it.date}"
                    is HistoryUiModel.Item -> "history-item-${it.item.chapterId}"
                }
            },
            contentType = {
                when (it) {
                    is HistoryUiModel.Header -> "header"
                    is HistoryUiModel.Item -> "item"
                }
            },
        ) { item ->
            when (item) {
                is HistoryUiModel.Header -> {
                    ListGroupHeader(
                        text = relativeDateText(item.date),
                    )
                }
                is HistoryUiModel.Item -> {
                    val value = item.item
                    HistoryItem(
                        history = value,
                        selected = value.id in selection,
                        selectionMode = selection.isNotEmpty(),
                        onClickCover = { onClickCover(value) },
                        onClickResume = { onClickResume(value) },
                        onClickDeleteSwipe = { onClickDeleteSwipe(value.id) },
                        onClickToggleSelection = { onToggleSelection(value.id, value.id !in selection) },
                    )
                }
            }
        }
    }
}

sealed interface HistoryUiModel {
    data class Header(val date: LocalDate) : HistoryUiModel
    data class Item(val item: HistoryWithRelations) : HistoryUiModel
}

@PreviewLightDark
@Composable
internal fun HistoryScreenPreviews(
    @PreviewParameter(HistoryviewModelStateProvider::class)
    historyState: HistoryViewModel.State,
) {
    TachiyomiPreviewTheme {
        HistoryScreen(
            state = historyState,
            snackbarHostState = SnackbarHostState(),
            onSearchQueryChange = {},
            onClickCover = {},
            onClickResume = { run {} },
            onClickDeleteSwipe = {},
            onToggleSelection = { _, _ -> },
            onClearSelection = {},
            onSelectAll = {},
            onInvertSelection = {},
            onDeleteSelection = {},
            onAddToLibrary = {},
            onDialogChange = {},
        )
    }
}
