package eu.kanade.tachiyomi.ui.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.library.components.MangaCompactGridItem
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover

/**
 * Picks one of the existing local collections.
 *
 * This is a dialog instead of a pushed screen on purpose: the navigator writes the whole screen
 * stack into the activity's saved state, so a screen keeping this callback in a field makes the
 * stack fail to serialize on the next stop (BadParcelableException / NotSerializableException).
 * A dialog keeps the callback inside the caller's composition, which is never serialized.
 */
@Composable
fun LocalMangaPickerDialog(
    mangas: List<Manga>,
    selectedMangaId: Long,
    onSelected: (Long) -> Unit,
    onDismissRequest: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                AppBar(
                    title = "选择已有合集",
                    navigateUp = onDismissRequest,
                )
            },
        ) { padding ->
            if (mangas.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无可选合集",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        top = padding.calculateTopPadding() + 12.dp,
                        end = 8.dp,
                        bottom = padding.calculateBottomPadding() + 12.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(mangas, key = { it.id }) { manga ->
                        MangaCompactGridItem(
                            coverData = MangaCover(
                                mangaId = manga.id,
                                sourceId = manga.source,
                                isMangaFavorite = manga.favorite,
                                url = manga.thumbnailUrl,
                                lastModified = manga.coverLastModified,
                            ),
                            title = manga.title,
                            isSelected = manga.id == selectedMangaId,
                            onClick = {
                                onSelected(manga.id)
                                onDismissRequest()
                            },
                            onLongClick = {},
                        )
                    }
                }
            }
        }
    }
}
