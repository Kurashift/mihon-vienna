package eu.kanade.presentation.browse.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Place
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.components.RandomGestureFab
import eu.kanade.presentation.components.rememberAtListEnd
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceUiModel
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Floating button shown at the bottom-start of the browse list.
 *
 * With [onRandomManga] / [onRandomGoodDoujin] supplied it also carries the random
 * gestures: drag up opens a random good doujin, drag right opens a random entry.
 *
 * When the last-read entry is already on screen, tapping opens its details page;
 * otherwise the tap scrolls the list to it. Scrolling away switches the button
 * back to locate mode automatically.
 *
 * If the last-read entry is not in the loaded list — nothing was read yet, or it sits in
 * a page that has not been loaded or is filtered out — there is nothing to locate. Rather
 * than dropping the random gestures along with it, the button stays present as a dice
 * button whose tap opens a random entry. Callers that pass no random callbacks keep
 * the old behaviour of hiding the button entirely.
 */
@Composable
fun BrowseSourceLastReadFab(
    mangaList: LazyPagingItems<BrowseSourceUiModel>,
    lastReadMangaId: Long?,
    scrollState: ScrollableState,
    modifier: Modifier = Modifier,
    visibleItemsRange: Flow<IntRange?>,
    onScrollToIndex: (Int) -> Unit,
    onOpenManga: (Manga) -> Unit,
    onRandomManga: (() -> Unit)? = null,
    onRandomGoodDoujin: (() -> Unit)? = null,
) {
    // The last row is the one row the button can never stop covering, so the plate dims further
    // once the list has run out of scroll. See RandomGestureFab for the full reasoning.
    val atListEnd = rememberAtListEnd(scrollState)

    // Recompute whenever the presented snapshot changes so the button appears
    // as soon as the target manga is loaded, not only when itemCount changes.
    val snapshot = mangaList.itemSnapshotList
    val lastReadIndex = remember(lastReadMangaId, snapshot) {
        if (lastReadMangaId == null) {
            -1
        } else {
            snapshot.items.indexOfFirst {
                it is BrowseSourceUiModel.Item && it.manga.id == lastReadMangaId
            }
        }
    }
    val manga = remember(lastReadMangaId, lastReadIndex, snapshot) {
        if (lastReadIndex < 0) return@remember null
        (snapshot.items.getOrNull(lastReadIndex) as? BrowseSourceUiModel.Item)?.manga
    }

    if (manga == null) {
        // Nothing to locate: either nothing was read yet, or the last-read entry sits in a
        // page that is not loaded / is filtered out of the current listing. Either way the
        // button stays put with the dice rather than disappearing, as long as the random
        // gestures can be offered.
        if (onRandomManga == null && onRandomGoodDoujin == null) return
        RandomGestureFab(
            gesturesEnabled = true,
            atListEnd = atListEnd,
            idleIcon = Icons.Outlined.Casino,
            idleContentDescription = stringResource(MR.strings.action_open_random_manga),
            onTap = { onRandomManga?.invoke() },
            onRandomManga = { onRandomManga?.invoke() },
            onRandomGoodDoujin = { onRandomGoodDoujin?.invoke() },
            modifier = modifier,
        )
        return
    }

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

    RandomGestureFab(
        gesturesEnabled = onRandomManga != null || onRandomGoodDoujin != null,
        atListEnd = atListEnd,
        idleIcon = Icons.Outlined.Place,
        idleContentDescription = stringResource(MR.strings.label_last_read),
        onTap = {
            if (!isAtLastRead) {
                onScrollToIndex(lastReadIndex)
            } else {
                onOpenManga(manga)
            }
        },
        onRandomManga = { onRandomManga?.invoke() },
        onRandomGoodDoujin = { onRandomGoodDoujin?.invoke() },
        modifier = modifier,
    )
}
