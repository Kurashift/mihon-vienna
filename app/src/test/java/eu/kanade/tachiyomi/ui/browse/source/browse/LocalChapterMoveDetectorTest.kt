package eu.kanade.tachiyomi.ui.browse.source.browse

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class LocalChapterMoveDetectorTest {

    @Test
    fun `exact duplicate rows are grouped only within the same manga`() {
        val groups = findExactLocalChapterDuplicateGroups(
            listOf(
                dbChapter(id = 10, mangaId = 1, url = "Author/Story.cbz", name = "Story"),
                dbChapter(id = 11, mangaId = 1, url = "Author/Story.cbz", name = "Story"),
                dbChapter(id = 12, mangaId = 2, url = "Author/Story.cbz", name = "Story"),
            ),
        )

        groups.map { it.map(Chapter::id) } shouldBe listOf(listOf(10L, 11L))
    }

    @Test
    fun `exact duplicate merge keeps oldest identity and combines user data`() {
        val old = dbChapter(
            id = 10,
            mangaId = 1,
            url = "Author/Story.cbz",
            name = "Story",
            bookmark = true,
            translatedName = "中文译名",
        )
        val scanned = dbChapter(
            id = 11,
            mangaId = 1,
            url = "Author/Story.cbz",
            name = "Story",
            read = true,
            lastPageRead = 18,
            totalPages = 23,
            memo = JsonObject(mapOf("mihon.pageCount" to JsonPrimitive(23))),
        )

        val update = mergeExactLocalChapterDuplicates(listOf(old, scanned), preferredProgressChapterId = scanned.id)

        update.id shouldBe old.id
        update.read shouldBe true
        update.bookmark shouldBe true
        update.lastPageRead shouldBe 18
        update.totalPages shouldBe 23
        update.translatedName shouldBe "中文译名"
        update.memo?.get("mihon.pageCount") shouldBe JsonPrimitive(23)
    }

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
                duplicateChapterId = null,
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
    fun `destination already stored in database is returned as a duplicate to merge`() {
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

        moves shouldBe listOf(
            LocalChapterMoveCandidate(
                chapterId = 10,
                duplicateChapterId = 11,
                oldMangaId = 1,
                oldMangaUrl = "Author A",
                newMangaUrl = "Author B",
                fileName = "Story.cbz",
            ),
        )
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

    @Test
    fun `persisted chapter base name can recover a move after the sync index was lost`() {
        val moves = detectLocalChapterMoves(
            storedChapters = listOf(chapter(10, 1, "Furusuemi", "Moved story.cbz")),
            previousFileNamesByMangaUrl = mapOf(
                "Furusuemi" to setOf("Moved story"),
                "Full Exist" to setOf("Existing story"),
            ),
            currentFileNamesByMangaUrl = mapOf(
                "Full Exist" to setOf("Existing story.cbz", "Moved story.cbz"),
            ),
        )

        moves shouldBe listOf(
            LocalChapterMoveCandidate(
                chapterId = 10,
                duplicateChapterId = null,
                oldMangaId = 1,
                oldMangaUrl = "Furusuemi",
                newMangaUrl = "Full Exist",
                fileName = "Moved story.cbz",
            ),
        )
    }

    @Test
    fun `stale missing manga row is recovered by one exact current file`() {
        val moves = detectStaleLocalChapterMoves(
            storedChapters = listOf(
                chapter(10, 1, "Furusuemi", "Moved story.cbz"),
                chapter(11, 2, "Full Exist", "Moved story.cbz"),
            ),
            currentFileNamesByMangaUrl = mapOf(
                "Full Exist" to setOf("Moved story.cbz"),
            ),
        )

        moves shouldBe listOf(
            LocalChapterMoveCandidate(
                chapterId = 10,
                duplicateChapterId = 11,
                oldMangaId = 1,
                oldMangaUrl = "Furusuemi",
                newMangaUrl = "Full Exist",
                fileName = "Moved story.cbz",
            ),
        )
    }

    @Test
    fun `stale move recovery rejects a file name in multiple current folders`() {
        detectStaleLocalChapterMoves(
            storedChapters = listOf(chapter(10, 1, "Deleted author", "Extra.cbz")),
            currentFileNamesByMangaUrl = mapOf(
                "Author B" to setOf("Extra.cbz"),
                "Author C" to setOf("Extra.cbz"),
            ),
        ).shouldBeEmpty()
    }

    @Test
    fun `duplicate move keeps old identity and user data while adopting target metadata`() {
        val old = dbChapter(
            id = 10,
            mangaId = 1,
            url = "Furusuemi/Story.cbz",
            name = "Story",
            lastPageRead = 2,
            totalPages = 39,
            translatedName = "中文译名",
            memo = JsonObject(mapOf("mihon.pageCount" to JsonPrimitive(39))),
        )
        val duplicate = dbChapter(
            id = 11,
            mangaId = 2,
            url = "Full Exist/Story.cbz",
            name = "Updated story",
            read = true,
            lastPageRead = 35,
            totalPages = 65,
            sourceOrder = 7,
            memo = JsonObject(mapOf("mihon.pageCount" to JsonPrimitive(65))),
        )

        val update = mergeMovedLocalChapter(old, duplicate, 2, "Full Exist/Story.cbz")

        update.id shouldBe 10
        update.mangaId shouldBe 2
        update.url shouldBe "Full Exist/Story.cbz"
        update.name shouldBe "Updated story"
        update.read shouldBe true
        update.lastPageRead shouldBe 2
        update.totalPages shouldBe 65
        update.sourceOrder shouldBe 7
        update.translatedName shouldBe "中文译名"
        update.memo?.get("mihon.pageCount") shouldBe JsonPrimitive(65)
    }

    @Test
    fun `newer target reading history can supply progress without replacing old identity`() {
        val old = dbChapter(
            id = 10,
            mangaId = 1,
            url = "Old/Story.cbz",
            name = "Story",
            lastPageRead = 2,
            totalPages = 40,
        )
        val duplicate = dbChapter(
            id = 11,
            mangaId = 2,
            url = "New/Story.cbz",
            name = "Story",
            lastPageRead = 12,
            totalPages = 40,
        )

        mergeMovedLocalChapter(
            old,
            duplicate,
            targetMangaId = 2,
            targetUrl = "New/Story.cbz",
            preferDuplicateProgress = true,
        ).lastPageRead shouldBe 12
    }

    private fun chapter(id: Long, mangaId: Long, mangaUrl: String, fileName: String) = StoredLocalChapter(
        chapterId = id,
        mangaId = mangaId,
        mangaUrl = mangaUrl,
        fileName = fileName,
    )

    private fun dbChapter(
        id: Long,
        mangaId: Long,
        url: String,
        name: String,
        read: Boolean = false,
        bookmark: Boolean = false,
        lastPageRead: Long = 0,
        totalPages: Long = 0,
        sourceOrder: Long = 0,
        translatedName: String? = null,
        memo: JsonObject = JsonObject(emptyMap()),
    ) = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        url = url,
        name = name,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        totalPages = totalPages,
        sourceOrder = sourceOrder,
        translatedName = translatedName,
        memo = memo,
    )
}
