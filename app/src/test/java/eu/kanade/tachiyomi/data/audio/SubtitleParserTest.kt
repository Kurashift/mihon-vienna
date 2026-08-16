package eu.kanade.tachiyomi.data.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SubtitleParserTest {

    @Test
    fun `lrc fractions are normalized to milliseconds`() {
        val result = SubtitleParser.parse("[00:01.5]A\n[00:02.50]B\n[00:03.500]C", "test.lrc")

        assertEquals(listOf(1_500L, 2_500L, 3_500L), result.map { it.timeMs })
    }

    @Test
    fun `lrc content is detected when api stream url has no file extension`() {
        val content = "\uFEFF[ti:Track00]\r\n[00:00.53]line\r\n"
        val result = SubtitleParser.parse(content, "https://api.asmr-200.com/api/media/stream/1653004/1937603")

        assertEquals(LyricLine(530L, "line"), result.single())
    }

    @Test
    fun `vtt accepts timestamps without hours and short fractions`() {
        val result = SubtitleParser.parse("00:01.5 --> 00:02.0\nhello", "test.vtt")

        assertEquals(LyricLine(1_500L, "hello"), result.single())
    }

    @Test
    fun `ass format line controls dialogue field positions`() {
        val content = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\i1}first\Nsecond
        """.trimIndent()

        assertEquals(LyricLine(1_500L, "first\nsecond"), SubtitleParser.parse(content, "test.ass").single())
    }
}
