package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 2.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(
            onClick = navigateUp,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(MR.strings.action_bar_up_description),
                modifier = Modifier.size(20.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 2.dp),
        ) {
            // Chapter name takes the lead; the manga name stays as a small second line.
            chapterTitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(
                        repeatDelayMillis = 2_000,
                    ),
                )
            }
            mangaTitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        AppBarActions(
            actions = buildList {
                // Keep manga details directly accessible without changing back navigation.
                onOpenManga?.let {
                    add(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_open_manga_details),
                            icon = Icons.AutoMirrored.Outlined.MenuBook,
                            onClick = it,
                        ),
                    )
                }
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
    }
}
