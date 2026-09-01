package mihon.feature.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import mihon.domain.library.model.search.QueryNode
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class QueryNodeExtensionsTest {

    private fun libraryItem(title: String): LibraryItem {
        val manga = Manga.create().copy(id = 7, title = title)
        return LibraryItem(
            libraryManga = LibraryManga(
                manga = manga,
                categories = emptyList(),
                totalChapters = 0,
                readCount = 0,
                bookmarkCount = 0,
                latestUpload = 0,
                chapterFetchedAt = 0,
                lastRead = 0,
            ),
            downloadCount = 0,
            unreadCount = 0,
            isLocal = false,
            sourceName = "source",
            sourceLanguage = "zh",
            badges = LibraryItem.Badges(downloadCount = 0, unreadCount = 0, isLocal = false, sourceLanguage = "zh"),
        )
    }

    @Test
    fun `general query matches manga by title`() {
        QueryNode.from("进击").matches(libraryItem("进击的巨人")) shouldBe true
    }

    @Test
    fun `general query matches a chapter translated name`() {
        val translated = mapOf(7L to listOf("有坂柳ntr"))
        QueryNode.from("有坂柳").matches(libraryItem("Original Title"), translated) shouldBe true
    }

    @Test
    fun `general query matches translated name by simplified form`() {
        val translated = mapOf(7L to listOf("進擊的巨人"))
        QueryNode.from("进击").matches(libraryItem("Original"), translated) shouldBe true
    }

    @Test
    fun `general query does not match unrelated translated names`() {
        val translated = mapOf(7L to listOf("有坂柳ntr"))
        QueryNode.from("完全无关").matches(libraryItem("Original"), translated) shouldBe false
    }

    @Test
    fun `title match still works without a translated name index`() {
        QueryNode.from("Original").matches(libraryItem("Original Title")) shouldBe true
    }
}
