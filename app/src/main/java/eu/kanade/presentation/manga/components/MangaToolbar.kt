package eu.kanade.presentation.manga.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.R
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DownloadDropdownMenu
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.util.system.copyToClipboard
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active

@Composable
fun MangaToolbar(
    title: String,
    hasFilters: Boolean,
    navigateUp: () -> Unit,
    onClickHome: (() -> Unit)? = null,
    onClickRandom: (() -> Unit)? = null,
    onClickRandomGoodDoujin: (() -> Unit)? = null,
    onClickAudio: (() -> Unit)? = null,
    onClickFilter: () -> Unit,
    onClickShare: (() -> Unit)?,
    onClickDownload: ((DownloadAction) -> Unit)?,
    onClickEditCategory: (() -> Unit)?,
    onClickRefresh: () -> Unit,
    onClickMigrate: (() -> Unit)?,
    onClickClearHistory: () -> Unit,
    onClickEditNotes: () -> Unit,

    // For action mode
    actionModeCounter: Int,
    onCancelActionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,

    chapterLayoutAvailable: Boolean = false,
    chapterLayoutGridEnabled: Boolean = false,
    onChapterLayoutChanged: ((Boolean) -> Unit)? = null,

    titleAlphaProvider: () -> Float,
    backgroundAlphaProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    val isActionMode = actionModeCounter > 0
    val context = LocalContext.current
    var chapterLayoutExpanded by remember { mutableStateOf(false) }
    AppBar(
        titleContent = {
            if (isActionMode) {
                AppBarTitle(actionModeCounter.toString())
            } else {
                AppBarTitle(
                    title = title,
                    modifier = Modifier
                        .alpha(titleAlphaProvider())
                        .combinedClickable(
                            interactionSource = null,
                            indication = null,
                            onClick = {},
                            onLongClick = { context.copyToClipboard(title, title) },
                        ),
                )
            }
        },
        modifier = modifier,
        backgroundColor = MaterialTheme.colorScheme
            .surfaceColorAtElevation(3.dp)
            .copy(alpha = if (isActionMode) 1f else backgroundAlphaProvider()),
        navigateUp = navigateUp,
        navigationActions = {
            if (onClickHome != null) {
                IconButton(onClick = onClickHome) {
                    Icon(
                        imageVector = Icons.Outlined.Home,
                        contentDescription = stringResource(MR.strings.action_bar_home),
                    )
                }
            }
        },
        actions = {
            var downloadExpanded by remember { mutableStateOf(false) }
            if (onClickDownload != null) {
                val onDismissRequest = { downloadExpanded = false }
                DownloadDropdownMenu(
                    expanded = downloadExpanded,
                    onDismissRequest = onDismissRequest,
                    onDownloadClicked = onClickDownload,
                )
            }

            val filterTint = if (hasFilters) MaterialTheme.colorScheme.active else LocalContentColor.current
            AppBarActions(
                actions = buildList {
                    if (isActionMode) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_all),
                                icon = Icons.Outlined.SelectAll,
                                onClick = onSelectAll,
                            ),
                        )
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_inverse),
                                icon = Icons.Outlined.FlipToBack,
                                onClick = onInvertSelection,
                            ),
                        )
                        return@buildList
                    }
                    if (chapterLayoutAvailable && onChapterLayoutChanged != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.chapter_layout),
                                icon = if (chapterLayoutGridEnabled) {
                                    Icons.Filled.ViewModule
                                } else {
                                    Icons.AutoMirrored.Filled.ViewList
                                },
                                onClick = { chapterLayoutExpanded = true },
                            ),
                        )
                    }
                    if (onClickDownload != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.manga_download),
                                icon = Icons.Outlined.Download,
                                onClick = { downloadExpanded = !downloadExpanded },
                            ),
                        )
                    }
                    if (onClickRandomGoodDoujin != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_open_random_good_doujin),
                                icon = ImageVector.vectorResource(R.drawable.ic_dice_heart_24dp),
                                onClick = onClickRandomGoodDoujin,
                            ),
                        )
                    }
                    if (onClickRandom != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_open_random_manga),
                                icon = Icons.Outlined.Casino,
                                onClick = onClickRandom,
                            ),
                        )
                    }
                    if (onClickAudio != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.audio_title),
                                icon = Icons.Outlined.Headphones,
                                onClick = onClickAudio,
                            ),
                        )
                    }
                    add(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_filter),
                            icon = Icons.Outlined.FilterList,
                            iconTint = filterTint,
                            onClick = onClickFilter,
                        ),
                    )
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_webview_refresh),
                            onClick = onClickRefresh,
                        ),
                    )
                    if (onClickEditCategory != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_edit_categories),
                                onClick = onClickEditCategory,
                            ),
                        )
                    }
                    if (onClickMigrate != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_migrate),
                                onClick = onClickMigrate,
                            ),
                        )
                    }
                    if (onClickShare != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_share),
                                onClick = onClickShare,
                            ),
                        )
                    }
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_notes),
                            onClick = onClickEditNotes,
                        ),
                    )
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_clear_reading_history),
                            onClick = onClickClearHistory,
                        ),
                    )
                },
            )

            DropdownMenu(
                expanded = chapterLayoutExpanded,
                onDismissRequest = { chapterLayoutExpanded = false },
            ) {
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.chapter_layout_list)) },
                    isChecked = !chapterLayoutGridEnabled,
                ) {
                    chapterLayoutExpanded = false
                    onChapterLayoutChanged?.invoke(false)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.chapter_layout_grid)) },
                    isChecked = chapterLayoutGridEnabled,
                ) {
                    chapterLayoutExpanded = false
                    onChapterLayoutChanged?.invoke(true)
                }
            }
        },
        isActionMode = isActionMode,
        onCancelActionMode = onCancelActionMode,
    )
}
