package eu.kanade.tachiyomi.ui.browse.source.browse

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LocalChapterMoveDetectorTest {

    @Test
    fun `unique same-name disappearance and addition is treated as a move`() {
        val moves = detectLocalChapterMoves(
            storedChapters = listOf(chapter(10, 1, "Author A", "Short Story.cbz")),
            previousFileNamesByMangaUrl = mapOf(
                "Author A" to setOf("Short Story.cbz"),
            ),
            currentFileNamesByMangaUrl = mapOf(
                "Author A" to emptySet(),
                "Author B" to setOf("Short Story.cbz"),
            ),
        )

        moves shouldBe listOf(
            LocalChapterMoveCandidate(
                chapterId = 10,
                oldMangaId = 1,
                oldMangaUrl = "Author A",
                newMangaUrl = "Author B",
                fileName = "Short Story.cbz",
            ),
        )
    }

    @Test
    fun `reordering without a path change is not treated as a move`() {
        val moves = detectLocalChapterMoves(
            storedChapters = listOf(
                chapter(10, 1, "Author", "First.cbz"),
                chapter(11, 1, "Author", "Second.cbz"),
            ),
            previousFileNamesByMangaUrl = mapOf(
                "Author" to setOf("First.cbz", "Second.cbz"),
            ),
            currentFileNamesByMangaUrl = mapOf(
                "Author" to linkedSetOf("Second.cbz", "First.cbz"),
            ),
        )

        moves.shouldBeEmpty()
    }

    @Test
    fun `copying a same-name file without removing the original is not treated as a move`() {
        val moves = detectLocalChapterMoves(
            storedChapters = listOf(chapter(10, 1, "Author A", "Story.cbz")),
            previousFileNamesByMangaUrl = mapOf(
                "Author A" to setOf("Story.cbz"),
            ),
            currentFileNamesByMangaUrl = mapOf(
                "Author A" to setOf("Story.cbz"),
                "Author B" to setOf("Story.cbz"),
            ),
        )

        moves.shouldBeEmpty()
    }

    @Test
    fun `ambiguous missing chapters with the same name are rejected`() {
        val moves = detectLocalChapterMoves(
            storedChapters = listOf(
                chapter(10, 1, "Author A", "Extra.cbz"),
                chapter(11, 2, "Author B", "Extra.cbz"),
            ),
            previousFileNamesByMangaUrl = mapOf(
                "Author A" to setOf("Extra.cbz"),
                "Author B" to setOf("Extra.cbz"),
            ),
            currentFileNamesByMangaUrl = mapOf(
                "Author A" to emptySet(),
                "Author B" to emptySet(),
                "Author C" to setOf("Extra.cbz"),
            ),
        )

        moves.shouldBeEmpty()
    }

    @Test
    fun `ambiguous destinations with the same name are rejected`() {
        val moves = detectLocalChapterMoves(
            storedChapters = listOf(chapter(10, 1, "Author A", "Extra.cbz")),
            previousFileNamesByMangaUrl = mapOf(
                "Author A" to setOf("Extra.cbz"),
            ),
            currentFileNamesByMangaUrl = mapOf(
                "Author A" to emptySet(),
                "Author B" to setOf("Extra.cbz"),
                "Author C" to setOf("Extra.cbz"),
            ),
        )

        moves.shouldBeEmpty()
    }

    @Test
    fun `destination already stored in database is rejected`() {
        val moves = detectLocalChapterMoves(
            storedChapters = listOf(
                chapter(10, 1, "Author A", "Story.cbz"),
                chapter(11, 2, "Author B", "Story.cbz"),
            ),
            previousFileNamesByMangaUrl = mapOf(
                "Author A" to setOf("Story.cbz"),
            ),
            currentFileNamesByMangaUrl = mapOf(
                "Author A" to emptySet(),
                "Author B" to setOf("Story.cbz"),
            ),
        )

        moves.shouldBeEmpty()
    }

    @Test
    fun `stale database row that was not present last scan is ignored`() {
        val moves = detectLocalChapterMoves(
            storedChapters = listOf(chapter(10, 1, "Author A", "Story.cbz")),
            previousFileNamesByMangaUrl = emptyMap(),
            currentFileNamesByMangaUrl = mapOf(
                "Author A" to emptySet(),
                "Author B" to setOf("Story.cbz"),
            ),
        )

        moves.shouldBeEmpty()
    }

    private fun chapter(id: Long, mangaId: Long, mangaUrl: String, fileName: String) = StoredLocalChapter(
        chapterId = id,
        mangaId = mangaId,
        mangaUrl = mangaUrl,
        fileName = fileName,
    )
}
