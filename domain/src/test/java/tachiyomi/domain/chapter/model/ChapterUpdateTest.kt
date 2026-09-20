package tachiyomi.domain.chapter.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterUpdateTest {

    @Test
    fun `a chapter without a finish timestamp does not clear the stored one`() {
        // 0 means "no timestamp on this object" (an unread row, or one from Chapter.create()), so it
        // must not travel as an update: the SQL layer would write it over a real date.
        val unread = Chapter.create().copy(id = 1L, read = true, markedReadAt = 0)

        unread.toChapterUpdate().markedReadAt shouldBe null
    }

    @Test
    fun `a recorded finish timestamp is carried through`() {
        val read = Chapter.create().copy(id = 1L, read = true, markedReadAt = 1_700_000_000_000)

        read.toChapterUpdate().markedReadAt shouldBe 1_700_000_000_000
    }
}
