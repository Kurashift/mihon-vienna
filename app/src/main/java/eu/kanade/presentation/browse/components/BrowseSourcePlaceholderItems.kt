package eu.kanade.presentation.browse.components

import androidx.compose.runtime.Composable
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import tachiyomi.domain.manga.model.MangaCover

@Composable
internal fun BrowseSourceListItemPlaceholder() {
    MangaListItem(
        title = "",
        coverData = placeholderCoverData(),
        badge = {},
        loadCover = false,
        onClick = {},
        onLongClick = {},
    )
}

@Composable
internal fun BrowseSourceComfortableGridItemPlaceholder() {
    MangaComfortableGridItem(
        title = "",
        coverData = placeholderCoverData(),
        loadCover = false,
        onClick = {},
        onLongClick = {},
    )
}

@Composable
internal fun BrowseSourceCompactGridItemPlaceholder() {
    MangaCompactGridItem(
        coverData = placeholderCoverData(),
        loadCover = false,
        onClick = {},
        onLongClick = {},
    )
}

private fun placeholderCoverData() = MangaCover(
    mangaId = 0L,
    sourceId = 0L,
    isMangaFavorite = false,
    url = null,
    lastModified = 0L,
)
