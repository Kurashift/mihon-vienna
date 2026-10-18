package eu.kanade.tachiyomi.data.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudioPlaylistOrderTest {

    @Test
    fun `a work moves to the front with all of its folders`() {
        val grouped = groupByWorkAndFolder(
            listOf(
                track("a1", 1, "first"),
                track("b1", 2, "first"),
                track("a2", 1, "second"),
            ),
        )

        assertEquals(listOf("a1", "a2", "b1"), grouped.map { it.mediaStreamUrl })
        assertEquals(listOf("first", "second", "first"), grouped.map { it.folderPath })
    }

    @Test
    fun `a newly queued folder stays before the work's older folders`() {
        val grouped = groupByWorkAndFolder(
            listOf(
                track("a2", 1, "second"),
                track("a1", 1, "first"),
                track("b1", 2, "first"),
            ),
        )

        assertEquals(listOf("a2", "a1", "b1"), grouped.map { it.mediaStreamUrl })
        assertEquals(listOf("second", "first", "first"), grouped.map { it.folderPath })
    }

    @Test
    fun `grouping twice changes nothing`() {
        val once = groupByWorkAndFolder(
            listOf(
                track("a1", 1, "first"),
                track("b1", 2, "first"),
                track("a2", 1, "second"),
            ),
        )

        assertEquals(once, groupByWorkAndFolder(once))
    }

    @Test
    fun `tracks inside one folder keep their order`() {
        val grouped = groupByWorkAndFolder(
            listOf(
                track("a2", 1, "first"),
                track("a1", 1, "first"),
            ),
        )

        assertEquals(listOf("a2", "a1"), grouped.map { it.mediaStreamUrl })
    }

    private fun track(url: String, workId: Long, folder: String): AudioPlayItem {
        return AudioPlayItem(
            workId = workId,
            workTitle = "work-$workId",
            folderPath = folder,
            mediaStreamUrl = url,
        )
    }
}
