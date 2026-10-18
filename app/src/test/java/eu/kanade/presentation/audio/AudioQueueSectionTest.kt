package eu.kanade.presentation.audio

import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudioQueueSectionTest {

    @Test
    fun `one work stays one section across folders`() {
        val sections = buildQueueSections(
            listOf(
                track("a", 1, "chapter-1"),
                track("b", 1, "chapter-2"),
                track("c", 1, "chapter-1"),
            ),
        )

        assertEquals(1, sections.size)
        assertEquals(3, sections.single().tracks.size)
        assertEquals(listOf("chapter-1", "chapter-2"), sections.single().folders.map { it.path })
        assertEquals(2, sections.single().folders[0].tracks.size)
        assertEquals(0, sections.single().startIndex)
    }

    @Test
    fun `a second work starts a second section`() {
        val sections = buildQueueSections(
            listOf(
                track("a", 1, "chapter-1"),
                track("b", 2, "chapter-1"),
            ),
        )

        assertEquals(2, sections.size)
        assertEquals(listOf(0, 1), sections.map { it.startIndex })
    }

    @Test
    fun `folder rows keep the global start of their tracks`() {
        val sections = buildQueueSections(
            listOf(
                track("a", 1, "first"),
                track("b", 1, "first"),
                track("c", 1, "second"),
            ),
        )

        val folders = sections.single().folders
        assertEquals(listOf("first", "second"), folders.map { it.path })
        assertEquals(listOf(0, 2), folders.map { it.startIndex })
        assertEquals(2, folders[1].startIndex)
    }

    @Test
    fun `a work key is a stable section identity`() {
        val tracks = listOf(
            track("a", 1, "chapter-1"),
            track("b", 1, "chapter-2"),
        )

        assertEquals(buildQueueSections(tracks).single().key, buildQueueSections(tracks).single().key)
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
