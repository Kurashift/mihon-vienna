package eu.kanade.presentation.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun ChapterSettingsDialog(
    onDismissRequest: () -> Unit,
    manga: Manga? = null,
    onDownloadFilterChanged: (TriState) -> Unit,
    onUnreadFilterChanged: (TriState) -> Unit,
    onBookmarkedFilterChanged: (TriState) -> Unit,
    scanlatorFilterActive: Boolean,
    onScanlatorFilterClicked: (() -> Unit),
    onSortModeChanged: (Long) -> Unit,
    onDisplayModeChanged: (Long) -> Unit,
    onSetAsDefault: (applyToExistingManga: Boolean) -> Unit,
    onResetToDefault: () -> Unit,
) {
    var showSetAsDefaultDialog by rememberSaveable { mutableStateOf(false) }
    if (showSetAsDefaultDialog) {
        SetAsDefaultDialog(
            onDismissRequest = { showSetAsDefaultDialog = false },
            onConfirmed = onSetAsDefault,
        )
    }

    val downloadedOnly = remember { Injekt.get<BasePreferences>().downloadedOnly.get() }
    val basePreferences = remember { Injekt.get<BasePreferences>() }
    val chapterCoversEnabled = remember { basePreferences.localChapterCoversEnabled.get() }
    val chapterLayoutAvailable = manga?.isLocal() == true && chapterCoversEnabled
    var chapterCoverGridEnabled by remember {
        mutableStateOf(basePreferences.localChapterCoverGridEnabled.get())
    }

    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = listOf(
            stringResource(MR.strings.action_display),
            stringResource(MR.strings.action_sort),
            stringResource(MR.strings.action_filter),
        ),
        tabOverflowMenuContent = { closeMenu ->
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.set_chapter_settings_as_default)) },
                onClick = {
                    showSetAsDefaultDialog = true
                    closeMenu()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.action_reset)) },
                onClick = {
                    onResetToDefault()
                    closeMenu()
                },
            )
        },
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> {
                    DisplayPage(
                        displayMode = manga?.displayMode ?: 0,
                        isLocal = manga?.isLocal() ?: false,
                        chapterLayoutAvailable = chapterLayoutAvailable,
                        chapterCoverGridEnabled = chapterCoverGridEnabled,
                        onChapterLayoutChanged = { grid ->
                            chapterCoverGridEnabled = grid
                            basePreferences.localChapterCoverGridEnabled.set(grid)
                        },
                        onItemSelected = onDisplayModeChanged,
                    )
                }
                1 -> {
                    SortPage(
                        sortingMode = manga?.sorting ?: 0,
                        sortDescending = manga?.sortDescending() ?: false,
                        isLocal = manga?.isLocal() ?: true,
                        onItemSelected = onSortModeChanged,
                    )
                }
                2 -> {
                    FilterPage(
                        isLocal = manga?.isLocal() ?: false,
                        downloadFilter = manga?.downloadedFilter ?: TriState.DISABLED,
                        onDownloadFilterChanged = onDownloadFilterChanged
                            .takeUnless { downloadedOnly },
                        unreadFilter = manga?.unreadFilter ?: TriState.DISABLED,
                        onUnreadFilterChanged = onUnreadFilterChanged,
                        bookmarkedFilter = manga?.bookmarkedFilter ?: TriState.DISABLED,
                        onBookmarkedFilterChanged = onBookmarkedFilterChanged,
                        scanlatorFilterActive = scanlatorFilterActive,
                        onScanlatorFilterClicked = onScanlatorFilterClicked,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.FilterPage(
    isLocal: Boolean,
    downloadFilter: TriState,
    onDownloadFilterChanged: ((TriState) -> Unit)?,
    unreadFilter: TriState,
    onUnreadFilterChanged: (TriState) -> Unit,
    bookmarkedFilter: TriState,
    onBookmarkedFilterChanged: (TriState) -> Unit,
    scanlatorFilterActive: Boolean,
    onScanlatorFilterClicked: (() -> Unit),
) {
    ListGroupHeader(text = stringResource(MR.strings.chapter_filter_status_section))
    if (!isLocal) {
        // Every local chapter counts as downloaded, so the filter cannot narrow anything.
        TriStateItem(
            label = stringResource(MR.strings.label_downloaded),
            state = downloadFilter,
            onClick = onDownloadFilterChanged,
        )
    }
    TriStateItem(
        label = stringResource(MR.strings.action_filter_unread),
        state = unreadFilter,
        onClick = onUnreadFilterChanged,
    )
    if (!isLocal) {
        // Local chapters cannot be bookmarked any more, so this filter has no source of truth.
        TriStateItem(
            label = stringResource(MR.strings.action_filter_bookmarked),
            state = bookmarkedFilter,
            onClick = onBookmarkedFilterChanged,
        )
    }
    if (!isLocal) {
        // Local chapters have no scanlator, so there is never anything to exclude here.
        ListGroupHeader(text = stringResource(MR.strings.chapter_filter_source_section))
        ScanlatorFilterItem(
            active = scanlatorFilterActive,
            onClick = onScanlatorFilterClicked,
        )
    }
}

@Composable
fun ScanlatorFilterItem(
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(horizontal = TabbedDialogPaddings.Horizontal, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.PeopleAlt,
            contentDescription = null,
            tint = if (active) {
                MaterialTheme.colorScheme.active
            } else {
                LocalContentColor.current
            },
        )
        Text(
            text = stringResource(MR.strings.scanlator),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ColumnScope.SortPage(
    sortingMode: Long,
    sortDescending: Boolean,
    isLocal: Boolean,
    onItemSelected: (Long) -> Unit,
) {
    ListGroupHeader(text = stringResource(MR.strings.chapter_sort_basis_section))
    listOf(
        MR.strings.sort_by_name to Manga.CHAPTER_SORTING_ALPHABET,
        MR.strings.sort_by_source to Manga.CHAPTER_SORTING_SOURCE,
        MR.strings.sort_by_number to Manga.CHAPTER_SORTING_NUMBER,
        MR.strings.sort_by_upload_date to Manga.CHAPTER_SORTING_UPLOAD_DATE,
    )
        .let { options ->
            // Custom manual order is only meaningful for the local library.
            if (isLocal) {
                options + listOf(
                    MR.strings.sort_by_custom to Manga.CHAPTER_SORTING_CUSTOM,
                )
            } else {
                options
            }
        }
        .map { (titleRes, mode) ->
            SortItem(
                label = stringResource(titleRes),
                sortDescending = sortDescending.takeIf { mode != Manga.CHAPTER_SORTING_CUSTOM },
                selected = sortingMode == mode,
                onClick = { onItemSelected(mode) },
            )
        }
}

@Composable
private fun ColumnScope.DisplayPage(
    displayMode: Long,
    isLocal: Boolean,
    chapterLayoutAvailable: Boolean,
    chapterCoverGridEnabled: Boolean,
    onChapterLayoutChanged: (Boolean) -> Unit,
    onItemSelected: (Long) -> Unit,
) {
    ListGroupHeader(text = stringResource(MR.strings.chapter_display_title_section))
    val displayOptions = if (isLocal) {
        listOf(
            MR.strings.chapter_display_original_only to Manga.CHAPTER_DISPLAY_NAME,
            MR.strings.chapter_display_translated_only to Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY,
            MR.strings.chapter_display_translated_and_original to Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL,
            MR.strings.show_chapter_number to Manga.CHAPTER_DISPLAY_NUMBER,
        )
    } else {
        listOf(
            MR.strings.show_title to Manga.CHAPTER_DISPLAY_NAME,
            MR.strings.show_chapter_number to Manga.CHAPTER_DISPLAY_NUMBER,
        )
    }
    displayOptions.map { (titleRes, mode) ->
        RadioItem(
            label = stringResource(titleRes),
            selected = displayMode == mode,
            onClick = { onItemSelected(mode) },
        )
    }
    if (chapterLayoutAvailable) {
        ListGroupHeader(text = stringResource(MR.strings.chapter_display_layout_section))
        RadioItem(
            label = stringResource(MR.strings.chapter_layout_list),
            selected = !chapterCoverGridEnabled,
            onClick = { onChapterLayoutChanged(false) },
        )
        RadioItem(
            label = stringResource(MR.strings.chapter_layout_grid),
            selected = chapterCoverGridEnabled,
            onClick = { onChapterLayoutChanged(true) },
        )
    }
}

@Composable
private fun SetAsDefaultDialog(
    onDismissRequest: () -> Unit,
    onConfirmed: (optionalChecked: Boolean) -> Unit,
) {
    var optionalChecked by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.chapter_settings)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = stringResource(MR.strings.confirm_set_chapter_settings))

                LabeledCheckbox(
                    label = stringResource(MR.strings.also_set_chapter_settings_for_library),
                    checked = optionalChecked,
                    onCheckedChange = { optionalChecked = it },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirmed(optionalChecked)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}
