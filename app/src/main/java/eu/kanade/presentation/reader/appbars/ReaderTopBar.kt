package eu.kanade.presentation.reader.appbars

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderTopBar(
    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    goodDoujinMarked: Boolean,
    onToggleGoodDoujin: (() -> Unit)?,
    onOpenManga: (() -> Unit)?,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    AppBar(
        modifier = modifier,
        backgroundColor = Color.Transparent,
        title = mangaTitle,
        subtitle = chapterTitle,
        navigateUp = navigateUp.takeIf { onOpenManga == null },
        navigationActions = {
            onOpenManga?.let {
                AppBarActions(
                    actions = listOf(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_open_manga_details),
                            icon = Icons.AutoMirrored.Outlined.MenuBook,
                            onClick = it,
                        ),
                    ),
                )
            }
        },
        actions = {
            AppBarActions(
                actions = buildList {
                    onToggleGoodDoujin?.let {
                        add(
                            AppBar.Action(
                                title = stringResource(
                                    if (goodDoujinMarked) {
                                        MR.strings.action_remove_from_good_doujin
                                    } else {
                                        MR.strings.action_add_to_good_doujin
                                    },
                                ),
                                icon = if (goodDoujinMarked) {
                                    Icons.Filled.Favorite
                                } else {
                                    Icons.Outlined.FavoriteBorder
                                },
                                onClick = it,
                            ),
                        )
                    }
                    onOpenInWebView?.let {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_open_in_web_view),
                                onClick = it,
                            ),
                        )
                    }
                    onOpenInBrowser?.let {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_open_in_browser),
                                onClick = it,
                            ),
                        )
                    }
                    onShare?.let {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_share),
                                onClick = it,
                            ),
                        )
                    }
                },
            )
        },
    )
}
