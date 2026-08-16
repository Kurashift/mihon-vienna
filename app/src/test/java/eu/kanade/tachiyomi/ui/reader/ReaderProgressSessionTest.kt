package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderProgressSessionTest {

    @Test
    fun `unsettled visible page is never used for exit flush`() {
        val session = ReaderProgressSession()

        assertNull(session.getSettled(chapterId = 1))
        assertNull(session.getForExit(chapterId = 1))
    }

    @Test
    fun `initial page establishes resume baseline without replacing settled progress`() {
        val session = ReaderProgressSession()
        session.recordInitial(chapterId = 1, pageIndex = 2)
        session.recordSettled(chapterId = 1, pageIndex = 5)
        session.recordInitial(chapterId = 1, pageIndex = 2)

        assertEquals(5, session.getSettled(chapterId = 1))
        assertEquals(5, session.getForExit(chapterId = 1))
    }

    @Test
    fun `latest settled page is authoritative per chapter`() {
        val session = ReaderProgressSession()
        session.recordSettled(chapterId = 1, pageIndex = 4)
        session.recordSettled(chapterId = 2, pageIndex = 7)
        session.recordSettled(chapterId = 1, pageIndex = 6)

        assertEquals(6, session.getSettled(chapterId = 1))
        assertEquals(7, session.getSettled(chapterId = 2))
        assertEquals(6, session.getForExit(chapterId = 1))
        assertEquals(7, session.getForExit(chapterId = 2))
    }

    @Test
    fun `new chapter entry cannot reuse a previous visit settled page`() {
        val session = ReaderProgressSession()
        session.recordSettled(chapterId = 1, pageIndex = 6)

        session.beginEntry(chapterId = 1)

        assertNull(session.getForExit(chapterId = 1))
    }
}
