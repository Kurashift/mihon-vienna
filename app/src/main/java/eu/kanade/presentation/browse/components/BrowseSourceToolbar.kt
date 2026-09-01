package eu.kanade.presentation.browse.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenuItem
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
    onOpenAudio: () -> Unit,
    onOpenSources: (() -> Unit)? = null,
    onFilterClick: (() -> Unit)? = null,
    onRefreshChapters: (() -> Unit)? = null,
    onImportLocalChapters: (() -> Unit)? = null,
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
                    add(
                        AppBar.MenuAction(
                            title = stringResource(MR.strings.label_more),
                            icon = Icons.Outlined.MoreVert,
                            content = { dismiss ->
                                val actions = buildList {
                                    if (isLocalSource) {
                                        onImportLocalChapters?.let {
                                            add(stringResource(MR.strings.action_import_local_chapters) to it)
                                        }
                                        onFilterClick?.let { add(stringResource(MR.strings.action_sort) to it) }
                                        onRefreshChapters?.let {
                                            add(stringResource(MR.strings.action_refresh_all_chapters) to it)
                                        }
                                    } else {
                                        add(stringResource(MR.strings.action_open_in_web_view) to onWebViewClick)
                                    }
                                    add(
                                        stringResource(
                                            if (isLocalSource) {
                                                MR.strings.action_clear_current_list_history
                                            } else {
                                                MR.strings.action_clear_reading_history
                                            },
                                        ) to onClearHistoryClick,
                                    )
                                    if (isConfigurableSource) {
                                        add(stringResource(MR.strings.action_settings) to onSettingsClick)
                                    }
                                }
                                actions.forEach { (label, action) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            dismiss()
                                            action()
                                        },
                                    )
                                }
                            },
                        ),
                    )
                },
            )
        },
        scrollBehavior = scrollBehavior,
    )
}
