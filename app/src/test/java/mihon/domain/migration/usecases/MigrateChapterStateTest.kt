package mihon.domain.migration.usecases

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class MigrateChapterStateTest {

    @Test
    fun `same chapter number with different url does not inherit state`() {
        val previous = chapter(
            url = "old/Work 01.cbz",
            chapterNumber = 1.0,
            read = true,
            bookmark = true,
            lastPageRead = 20,
            dateFetch = 100,
        )
        val target = chapter(
            url = "new/Work 01.cbz",
            chapterNumber = 1.0,
            totalPages = 20,
        )

        listOf(target).copyStateFromChaptersWithSameUrl(listOf(previous)) shouldBe listOf(target)
    }

    @Test
    fun `exact url inherits reading state and bookmark`() {
        val previous = chapter(
            url = "Work/Chapter.cbz",
            chapterNumber = 7.0,
            read = true,
            bookmark = true,
            lastPageRead = 8,
            dateFetch = 100,
        )
        val target = chapter(
            url = previous.url,
            chapterNumber = 99.0,
            totalPages = 12,
            dateFetch = 200,
        )

        listOf(target).copyStateFromChaptersWithSameUrl(listOf(previous)).single() shouldBe target.copy(
            read = true,
            bookmark = true,
            lastPageRead = 12,
            dateFetch = 100,
        )
    }

    @Test
    fun `partial progress is clamped to target chapter`() {
        val previous = chapter(
            url = "Work/Chapter.cbz",
            lastPageRead = 20,
        )
        val target = chapter(
            url = previous.url,
            totalPages = 10,
        )

        listOf(target).copyStateFromChaptersWithSameUrl(listOf(previous)).single().lastPageRead shouldBe 9L
    }

    private fun chapter(
        url: String,
        chapterNumber: Double = -1.0,
        read: Boolean = false,
        bookmark: Boolean = false,
        lastPageRead: Long = 0,
        totalPages: Long = 0,
        dateFetch: Long = 0,
    ): Chapter {
        return Chapter.create().copy(
            url = url,
            chapterNumber = chapterNumber,
            read = read,
            bookmark = bookmark,
            lastPageRead = lastPageRead,
            totalPages = totalPages,
            dateFetch = dateFetch,
        )
    }
}
