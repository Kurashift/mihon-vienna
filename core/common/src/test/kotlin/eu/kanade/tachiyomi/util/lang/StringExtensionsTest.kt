package eu.kanade.tachiyomi.util.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class StringExtensionsTest {

    @Test
    fun `page order keeps numeric pages before textual extras`() {
        val pages = listOf("zzz.webp", "010.webp", "002.webp", "001.webp")

        val sorted = pages.sortedWith { first, second ->
            first.compareToCaseInsensitiveNaturalPageOrder(second)
        }

        assertEquals(listOf("001.webp", "002.webp", "010.webp", "zzz.webp"), sorted)
    }

    @Test
    fun `page order recognizes digits after leading punctuation`() {
        val pages = listOf("cover.webp", "_002.webp", ".001.webp")

        val sorted = pages.sortedWith { first, second ->
            first.compareToCaseInsensitiveNaturalPageOrder(second)
        }

        assertEquals(listOf(".001.webp", "_002.webp", "cover.webp"), sorted)
    }

    @Test
    fun `title order sorts english titles alphabetically case-insensitively`() {
        val titles = listOf("Zulu", "alpha", "Beta")

        val sorted = titles.sortedWith { first, second ->
            first.compareToCaseInsensitiveNaturalOrder(second)
        }

        assertEquals(listOf("alpha", "Beta", "Zulu"), sorted)
    }

    @Test
    fun `title order keeps digit-leading titles after letters and compares numbers by value`() {
        val titles = listOf("A10", "123", "A2", "A", "B")

        val sorted = titles.sortedWith { first, second ->
            first.compareToCaseInsensitiveNaturalOrder(second)
        }

        assertEquals(listOf("A", "A2", "A10", "B", "123"), sorted)
        assertEquals(-1, "A2".compareToCaseInsensitiveNaturalOrder("A10"))
        assertEquals(0, "A01".compareToCaseInsensitiveNaturalOrder("A1"))
    }

    @Test
    fun `title order ignores punctuation and whitespace`() {
        assertEquals(0, "A-B".compareToCaseInsensitiveNaturalOrder("A B"))
        assertEquals(0, "A.B".compareToCaseInsensitiveNaturalOrder("AB"))
    }

    @Test
    fun `title order sorts simplified chinese by pinyin in chinese locale`() {
        val titles = listOf("八", "啊", "才")

        val sorted = titles.sortedWith { first, second ->
            first.compareToCaseInsensitiveNaturalOrder(second, Locale.CHINA)
        }

        assertEquals(listOf("啊", "八", "才"), sorted)
    }

    @Test
    fun `title order sorts japanese hiragana by gojuon in japanese locale`() {
        val titles = listOf("う", "あ", "い")

        val sorted = titles.sortedWith { first, second ->
            first.compareToCaseInsensitiveNaturalOrder(second, Locale.JAPANESE)
        }

        assertEquals(listOf("あ", "い", "う"), sorted)
    }
}
