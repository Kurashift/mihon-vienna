package eu.kanade.tachiyomi.data.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudioSearchCompilerTest {

    private val snapshot = AudioCategorySnapshot(
        circles = listOf(
            CircleItem(id = 1, name = "梅麻呂", count = 30),
        ),
        vas = listOf(
            VaItem(id = "va1", name = "春花らん", count = 12),
        ),
        tags = listOf(
            TagItem(id = 10, name = "処女", count = 100),
            TagItem(id = 11, name = "耳かき", count = 90),
            TagItem(id = 12, name = "ASMR", count = 80),
        ),
    )

    private fun compiler(snapshot: AudioCategorySnapshot?) = AudioSearchCompiler { snapshot }

    @Test
    fun `exact dictionary names become markers`() {
        assertEquals("\$tag:処女\$", compiler(snapshot).compile("処女"))
        assertEquals("\$circle:梅麻呂\$", compiler(snapshot).compile("梅麻呂"))
        assertEquals("\$va:春花らん\$", compiler(snapshot).compile("春花らん"))
    }

    @Test
    fun `simplified and traditional spellings reach the dictionary entry`() {
        assertEquals("\$tag:処女\$", compiler(snapshot).compile("处女"))
        assertEquals("\$tag:処女\$", compiler(snapshot).compile("處女"))
    }

    @Test
    fun `case and width variants reach the entry through normalization`() {
        assertEquals("\$tag:ASMR\$", compiler(snapshot).compile("ａｓｍｒ"))
    }

    @Test
    fun `an exact name wins over its variant lookalikes`() {
        val both = AudioCategorySnapshot(
            tags = listOf(
                TagItem(id = 1, name = "処女", count = 1),
                TagItem(id = 2, name = "处女", count = 1),
            ),
        )
        assertEquals("\$tag:处女\$", compiler(both).compile("处女"))
        assertEquals("\$tag:処女\$", compiler(both).compile("処女"))
    }

    @Test
    fun `a term matching several entries stays free text`() {
        val ambiguous = AudioCategorySnapshot(
            tags = listOf(TagItem(id = 1, name = "ＡＳＭＲ", count = 1)),
            vas = listOf(VaItem(id = "v", name = "ASMR", count = 1)),
        )
        assertEquals("asmr", compiler(ambiguous).compile("asmr"))
    }

    @Test
    fun `terms carrying a dollar sign pass through untouched`() {
        assertEquals("\$tag:x\$ yo", compiler(snapshot).compile("\$tag:x\$ yo"))
    }

    @Test
    fun `dictionary names with a dollar sign are never wrapped`() {
        val odd = AudioCategorySnapshot(tags = listOf(TagItem(id = 1, name = "a\$b", count = 1)))
        // Misses the exact index only through the full-width b, so the normalized path is what
        // must refuse to wrap the name.
        assertEquals("a\$ｂ", compiler(odd).compile("a\$ｂ"))
    }

    @Test
    fun `mixed terms compile in order with single spaces`() {
        assertEquals(
            "\$tag:処女\$ \$circle:梅麻呂\$ RJ01234567",
            compiler(snapshot).compile("处女　梅麻吕 \t RJ01234567"),
        )
    }

    @Test
    fun `without dictionaries the keyword is untouched`() {
        assertEquals("処女 处女", compiler(null).compile("処女 处女"))
        assertEquals("処女", compiler(AudioCategorySnapshot()).compile("処女"))
    }

    @Test
    fun `blank keywords come back blank`() {
        assertEquals("", compiler(snapshot).compile("   "))
    }
}
