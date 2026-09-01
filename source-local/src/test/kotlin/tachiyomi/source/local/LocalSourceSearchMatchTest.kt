package tachiyomi.source.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalSourceSearchMatchTest {

    @Test
    fun `manga title match keeps the hit without crediting a chapter`() {
        assertEquals(
            LocalSearchMatch(null),
            localSearchMatch(
                query = "arifureta",
                title = "Arifureta",
                chapterNames = listOf("Chapter 1"),
                translatedNames = emptyList(),
            ),
        )
    }

    @Test
    fun `chapter file name match is credited`() {
        assertEquals(
            LocalSearchMatch("Vol.5 Chapter 42"),
            localSearchMatch(
                query = "chapter 42",
                title = "Arifureta",
                chapterNames = listOf("Vol.4 Chapter 41", "Vol.5 Chapter 42"),
                translatedNames = emptyList(),
            ),
        )
    }

    @Test
    fun `translated name match finds the manga`() {
        assertEquals(
            LocalSearchMatch("有坂柳ntr"),
            localSearchMatch(
                query = "有坂柳",
                title = "Arifureta",
                chapterNames = listOf("Chapter 1"),
                translatedNames = listOf("有坂柳ntr"),
            ),
        )
    }

    @Test
    fun `translated name match tolerates simplified and traditional variants`() {
        assertEquals(
            LocalSearchMatch("進擊的巨人"),
            localSearchMatch(
                query = "进击",
                title = "Attack on Titan",
                chapterNames = listOf("Chapter 1"),
                translatedNames = listOf("進擊的巨人"),
            ),
        )
    }

    @Test
    fun `file name wins over a translated name`() {
        assertEquals(
            LocalSearchMatch("進擊的巨人 01"),
            localSearchMatch(
                query = "進擊",
                title = "Attack on Titan",
                chapterNames = listOf("進擊的巨人 01"),
                translatedNames = listOf("進擊的巨人"),
            ),
        )
    }

    @Test
    fun `unrelated translated name does not match`() {
        assertNull(
            localSearchMatch(
                query = "有坂柳",
                title = "Attack on Titan",
                chapterNames = listOf("Chapter 1"),
                translatedNames = listOf("進擊的巨人"),
            ),
        )
    }

    @Test
    fun `nothing matches without a translated name index`() {
        assertNull(
            localSearchMatch(
                query = "有坂柳",
                title = "Arifureta",
                chapterNames = listOf("Chapter 1"),
                translatedNames = emptyList(),
            ),
        )
    }
}
