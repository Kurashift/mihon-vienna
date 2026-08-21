package eu.kanade.presentation.browse.components

import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceUiModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class BrowseSourceMangaNumberTest {

    @Test
    fun `unloaded placeholders before an item still count toward its absolute number`() {
        val items = List<BrowseSourceUiModel?>(120) { null }.toMutableList()
        items[119] = item(1)

        assertEquals(120, absoluteMangaNumberAt(119, items::get))
    }

    @Test
    fun `date headers do not count as manga`() {
        val items = listOf(
            BrowseSourceUiModel.Header(1),
            item(1),
            null,
            BrowseSourceUiModel.Header(2),
            item(2),
        )

        assertEquals(1, absoluteMangaNumberAt(1, items::get))
        assertEquals(3, absoluteMangaNumberAt(4, items::get))
    }

    private fun item(id: Long) = BrowseSourceUiModel.Item(
        manga = Manga.create().copy(id = id),
    )
}
