package eu.kanade.presentation.browse.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import eu.kanade.tachiyomi.data.manga.MangaCoverUpdate
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceUiModel
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover

@Composable
internal fun BrowseSourceDateHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

internal fun LazyPagingItems<BrowseSourceUiModel>.mangaNumberAt(index: Int): Int {
    return absoluteMangaNumberAt(index) { peek(it) }
}

internal fun LazyPagingItems<BrowseSourceUiModel>.hasDateHeaders(): Boolean {
    return itemSnapshotList.items.any { it is BrowseSourceUiModel.Header }
}

/**
 * Which list the rows on screen belong to, or null while none are presented.
 *
 * Read from the presented rows rather than from the list that produced them: the screen uses it to
 * decide when to reset its scroll position and fade the list in, and both must happen on the same
 * frame as the new rows. Asking any earlier acts before the rows arrive, which reads as the list
 * jumping to its top and only then filling in.
 */
internal fun LazyPagingItems<BrowseSourceUiModel>.presentedListGeneration(): Long? {
    for (item in itemSnapshotList.items) {
        when (item) {
            is BrowseSourceUiModel.Item -> return item.listGeneration
            is BrowseSourceUiModel.Header -> return item.listGeneration
            null -> Unit
        }
    }
    return null
}

/**
 * Key for the row at [index].
 *
 * Rows are identified by their content while the list grows a page at a time, so a page arriving
 * leaves the reader where they are. That is wrong for a list that is replaced outright: the
 * layout remembers the key of the row under the reader and follows it into the replacement, and
 * the work they were looking at is still in there, just at another index - so a filter change
 * lands them at 99, and flipping the sort throws the list to the other end. A whole list is
 * therefore keyed by position, which cannot be followed anywhere.
 */
internal fun LazyPagingItems<BrowseSourceUiModel>.peekKey(index: Int, wholeList: Boolean = false): Any {
    if (wholeList) return index
    return when (val item = peek(index)) {
        is BrowseSourceUiModel.Header -> "date-header-${item.bucket}"
        is BrowseSourceUiModel.Item -> item.manga.id
        null -> -index - 1L
    }
}

internal fun Manga.asDisplayedCover(coverUpdates: Map<Long, MangaCoverUpdate>): MangaCover {
    val overlay = coverUpdates[id]
    return MangaCover(
        mangaId = id,
        sourceId = source,
        isMangaFavorite = favorite,
        url = overlay?.url ?: thumbnailUrl,
        lastModified = overlay?.lastModified ?: coverLastModified,
    )
}

@Composable
internal fun BrowseSourceHighlightBorder(
    mangaId: Long,
    highlightedMangaId: Long?,
    highlightAlpha: Animatable<Float, *>,
    content: @Composable () -> Unit,
) {
    val color = if (mangaId == highlightedMangaId) {
        MaterialTheme.colorScheme.primary.copy(alpha = highlightAlpha.value)
    } else {
        Color.Transparent
    }
    Box(
        modifier = Modifier.border(
            width = 3.dp,
            color = color,
            shape = RoundedCornerShape(8.dp),
        ),
    ) {
        content()
    }
}

internal fun absoluteMangaNumberAt(
    index: Int,
    itemAt: (Int) -> BrowseSourceUiModel?,
): Int {
    val headersBeforeOrAt = (0..index).count { itemAt(it) is BrowseSourceUiModel.Header }
    return index + 1 - headersBeforeOrAt
}
