package eu.kanade.tachiyomi.data.audio

data class LyricLine(
    val timeMs: Long,
    val text: String,
)

/** Parses the subtitle formats commonly exposed by Kikoeru-compatible backends. */
object SubtitleParser {

    fun parse(content: String, fileName: String): List<LyricLine> {
        val trimmed = content.trim()
        val lines = when {
            fileName.endsWith(".lrc", ignoreCase = true) -> parseLrc(trimmed)
            fileName.endsWith(".vtt", ignoreCase = true) -> parseVtt(trimmed)
            fileName.endsWith(".srt", ignoreCase = true) -> parseSrt(trimmed)
            fileName.endsWith(".ass", ignoreCase = true) -> parseAss(trimmed)
            else -> parseLrc(trimmed)
                .ifEmpty { parseVtt(trimmed) }
                .ifEmpty { parseSrt(trimmed) }
                .ifEmpty { parseAss(trimmed) }
        }
        return lines.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }
    }

    private fun parseLrc(content: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        val tagRegex = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        content.lineSequence().forEach { raw ->
            val matches = tagRegex.findAll(raw).toList()
            if (matches.isEmpty()) return@forEach
            val text = raw.substring(matches.last().range.last + 1).trim()
            matches.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: 0
                val seconds = match.groupValues[2].toLongOrNull() ?: 0
                result += LyricLine(
                    minutes * 60_000 + seconds * 1_000 + fractionToMillis(match.groupValues[3]),
                    text,
                )
            }
        }
        return result
    }

    private fun parseVtt(content: String): List<LyricLine> {
        return parseCueText(
            content,
            Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})\s*-->"""),
        )
    }

    private fun parseSrt(content: String): List<LyricLine> {
        return parseCueText(
            content,
            Regex("""(\d{1,2}):(\d{1,2}):(\d{1,2})[.,](\d{1,3})\s*-->"""),
        )
    }

    private fun parseCueText(content: String, cueRegex: Regex): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        val lines = content.lines()
        var index = 0
        while (index < lines.size) {
            val match = cueRegex.find(lines[index])
            if (match == null) {
                index++
                continue
            }
            val textLines = mutableListOf<String>()
            var next = index + 1
            while (next < lines.size && lines[next].isNotBlank() && !lines[next].contains("-->")) {
                textLines += lines[next].trim()
                next++
            }
            if (textLines.isNotEmpty()) {
                val groups = match.groupValues
                result += LyricLine(
                    (groups[1].toLongOrNull() ?: 0) * 3_600_000 +
                        (groups[2].toLongOrNull() ?: 0) * 60_000 +
                        (groups[3].toLongOrNull() ?: 0) * 1_000 +
                        fractionToMillis(groups[4]),
                    textLines.joinToString(" "),
                )
            }
            index = next
        }
        return result
    }

    private fun parseAss(content: String): List<LyricLine> {
        var startIndex = 1
        var textIndex = 9
        var fieldCount = 10
        val result = mutableListOf<LyricLine>()
        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("Format:", ignoreCase = true)) {
                val fields = line.substringAfter(':').split(',').map { it.trim() }
                val newStartIndex = fields.indexOfFirst { it.equals("Start", ignoreCase = true) }
                val newTextIndex = fields.indexOfFirst { it.equals("Text", ignoreCase = true) }
                if (newStartIndex >= 0 && newTextIndex >= 0) {
                    startIndex = newStartIndex
                    textIndex = newTextIndex
                    fieldCount = fields.size
                }
            } else if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val fields = line.substringAfter(':').split(',', limit = fieldCount)
                if (fields.size <= maxOf(startIndex, textIndex)) return@forEach
                val timeMs = parseAssTimestamp(fields[startIndex].trim()) ?: return@forEach
                val text = fields[textIndex]
                    .replace(ASS_OVERRIDE_REGEX, "")
                    .replace("\\N", "\n", ignoreCase = true)
                    .trim()
                result += LyricLine(timeMs, text)
            }
        }
        return result
    }

    private fun parseAssTimestamp(value: String): Long? {
        val match = ASS_TIMESTAMP_REGEX.matchEntire(value) ?: return null
        return (match.groupValues[1].toLongOrNull() ?: return null) * 3_600_000 +
            (match.groupValues[2].toLongOrNull() ?: return null) * 60_000 +
            (match.groupValues[3].toLongOrNull() ?: return null) * 1_000 +
            fractionToMillis(match.groupValues[4])
    }

    private fun fractionToMillis(value: String): Long {
        val number = value.toLongOrNull() ?: return 0
        return when (value.length) {
            1 -> number * 100
            2 -> number * 10
            else -> number
        }
    }

    private val ASS_TIMESTAMP_REGEX = Regex("""(\d{1,2}):(\d{1,2}):(\d{1,2})[.](\d{1,3})""")
    private val ASS_OVERRIDE_REGEX = Regex("""\{[^}]*}""")
}
