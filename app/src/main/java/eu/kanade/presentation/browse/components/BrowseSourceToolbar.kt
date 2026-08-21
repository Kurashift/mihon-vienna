package eu.kanade.presentation.browse.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.data.search.SearchHistoryScope
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    source: Source?,
    title: String? = null,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: (() -> Unit)?,
    onWebViewClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onOpenRandomManga: () -> Unit,
    onOpenRandomGoodDoujin: (() -> Unit)? = null,
    onOpenAudio: () -> Unit,
    onOpenSources: (() -> Unit)? = null,
    onFilterClick: (() -> Unit)? = null,
    onRefreshChapters: (() -> Unit)? = null,
    onClearHistoryClick: () -> Unit,
    onSearch: (String) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    // Avoid capturing unstable source in actions lambda
    val toolbarTitle = title ?: source?.name
    val isLocalSource = source is LocalSource
    val isConfigurableSource = source is ConfigurableSource

    SearchToolbar(
        navigateUp = navigateUp,
        titleContent = { AppBarTitle(toolbarTitle) },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        historyKey = source?.let { SearchHistoryScope.source(it.id) },
        onSearch = onSearch,
        onClickCloseSearch = navigateUp ?: { onSearchQueryChange(null) },
        actions = {
            AppBarActions(
                actions = buildList {
                    if (onOpenSources != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.browse),
                                icon = Icons.Outlined.Explore,
                                onClick = onOpenSources,
                            ),
                        )
                    }
                    if (isLocalSource) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.audio_title),
                                icon = Icons.Outlined.Headphones,
                                onClick = onOpenAudio,
                            ),
                        )
                        if (onFilterClick != null) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_sort),
                                    onClick = onFilterClick,
                                ),
                            )
                        }
                        if (onRefreshChapters != null) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_refresh_all_chapters),
                                    onClick = onRefreshChapters,
                                ),
                            )
                        }
                    }
                    add(
                        AppBar.MenuAction(
                            title = stringResource(MR.strings.action_display_mode),
                            icon = if (displayMode == LibraryDisplayMode.List) {
                                Icons.AutoMirrored.Filled.ViewList
                            } else {
                                Icons.Filled.ViewModule
                            },
                            content = { dismiss ->
                                RadioMenuItem(
                                    text = {
                                        Text(text = stringResource(MR.strings.action_display_comfortable_grid))
                                    },
                                    isChecked = displayMode == LibraryDisplayMode.ComfortableGrid,
                                ) {
                                    dismiss()
                                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                                }
                                RadioMenuItem(
                                    text = { Text(text = stringResource(MR.strings.action_display_grid)) },
                                    isChecked = displayMode == LibraryDisplayMode.CompactGrid,
                                ) {
                                    dismiss()
                                    onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                                }
                                RadioMenuItem(
                                    text = { Text(text = stringResource(MR.strings.action_display_list)) },
                                    isChecked = displayMode == LibraryDisplayMode.List,
                                ) {
                                    dismiss()
                                    onDisplayModeChange(LibraryDisplayMode.List)
                                }
                            },
                        ),
                    )
                    if (!isLocalSource) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_open_in_web_view),
                                onClick = onWebViewClick,
                            ),
                        )
                    }
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_open_random_manga),
                            onClick = onOpenRandomManga,
                        ),
                    )
                    if (onOpenRandomGoodDoujin != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_open_random_good_doujin),
                                onClick = onOpenRandomGoodDoujin,
                            ),
                        )
                    }
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(
                                if (isLocalSource) {
                                    MR.strings.action_clear_current_list_history
                                } else {
                                    MR.strings.action_clear_reading_history
                                },
                            ),
                            onClick = onClearHistoryClick,
                        ),
                    )
                    if (isConfigurableSource) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_settings),
                                onClick = onSettingsClick,
                            ),
                        )
                    }
                },
            )
        },
        scrollBehavior = scrollBehavior,
    )
}
