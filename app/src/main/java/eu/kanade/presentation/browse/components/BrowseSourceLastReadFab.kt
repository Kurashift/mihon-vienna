package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceUiModel
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Floating button shown at the bottom-start of the browse list that locates the
 * manga read last.
 *
 * When the last-read entry is already on screen, tapping opens its details page;
 * otherwise the tap scrolls the list to it. Scrolling away switches the button
 * back to locate mode automatically.
 */
@Composable
fun BrowseSourceLastReadFab(
    mangaList: LazyPagingItems<BrowseSourceUiModel>,
    lastReadMangaId: Long?,
    modifier: Modifier = Modifier,
    visibleItemsRange: Flow<IntRange?>,
    onScrollToIndex: (Int) -> Unit,
    onOpenManga: (Manga) -> Unit,
) {
    if (lastReadMangaId == null) return

    // Recompute whenever the presented snapshot changes so the button appears
    // as soon as the target manga is loaded, not only when itemCount changes.
    val snapshot = mangaList.itemSnapshotList
    val lastReadIndex = remember(lastReadMangaId, snapshot) {
        snapshot.items.indexOfFirst {
            it is BrowseSourceUiModel.Item && it.manga.id == lastReadMangaId
        }.takeIf { it >= 0 }
    } ?: return

    val manga = remember(lastReadIndex) {
        (snapshot.items.getOrNull(lastReadIndex) as? BrowseSourceUiModel.Item)?.manga
    } ?: return

    // The button opens the manga directly when the last-read entry is already on
    // screen; otherwise the first tap locates it. Deriving this from the actual
    // visible range keeps it working after re-entering the screen or locating to
    // an entry near the bottom of the list, with no extra state to reset.
    var isAtLastRead by remember(lastReadMangaId, lastReadIndex) { mutableStateOf(false) }
    LaunchedEffect(visibleItemsRange, lastReadIndex) {
        visibleItemsRange.collect { range ->
            isAtLastRead = range?.contains(lastReadIndex) == true
        }
    }

    FloatingActionButton(
        onClick = {
            if (!isAtLastRead) {
                onScrollToIndex(lastReadIndex)
            } else {
                onOpenManga(manga)
            }
        },
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Icon(
            imageVector = Icons.Outlined.Place,
            contentDescription = stringResource(MR.strings.label_last_read),
        )
    }
}
