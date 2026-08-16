package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RandomReaderHistoryTest {

    @AfterEach
    fun tearDown() {
        RandomReaderHistory.clear()
    }

    @Test
    fun `history returns the most recent reader first`() {
        RandomReaderHistory.push(entry(1))
        RandomReaderHistory.push(entry(2))

        assertEquals(2L, RandomReaderHistory.pop()?.mangaId)
        assertEquals(1L, RandomReaderHistory.pop()?.mangaId)
        assertNull(RandomReaderHistory.pop())
    }

    @Test
    fun `history keeps only the latest twenty readers`() {
        (1L..25L).forEach { RandomReaderHistory.push(entry(it)) }

        assertEquals(20, RandomReaderHistory.size())
        assertEquals(25L, RandomReaderHistory.pop()?.mangaId)
        repeat(18) { RandomReaderHistory.pop() }
        assertEquals(6L, RandomReaderHistory.pop()?.mangaId)
    }

    private fun entry(id: Long) = RandomReaderHistory.Entry(
        mangaId = id,
        chapterId = id * 10,
        pageIndex = id.toInt(),
        returnDirection = "right",
    )
}
