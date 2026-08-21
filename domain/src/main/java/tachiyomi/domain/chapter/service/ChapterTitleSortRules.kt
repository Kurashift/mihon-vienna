package tachiyomi.domain.chapter.service

import java.math.BigInteger

/**
 * Normalizes explicit sequence markers near the end of a translated chapter title. Ambiguous,
 * embedded, or composite markers keep the original title and use the normal natural comparator.
 */
internal object ChapterTitleSortRules {

    fun sortKey(title: String): String {
        val candidates = markerPatterns
            .flatMap { pattern ->
                pattern.regex.findAll(title)
                    .filter { match -> markerIsOutsideMetadata(title, match.range.first, match.value) }
                    .mapNotNull { match -> pattern.toMarker(match) }
                    .toList()
            }
            .distinctBy(SequenceMarker::range)
        if (candidates.size != 1) return title

        val marker = candidates.single()
        if (!metadataTail.matches(title.substring(marker.range.last + 1))) return title

        var prefix = title.substring(0, marker.range.first)
        if (prefix.lastOrNull() in openingParentheses && title.getOrNull(marker.range.last + 1) in closingParentheses) {
            prefix = prefix.dropLast(1)
        }
        return prefix + marker.sortToken
    }

    private fun markerIsOutsideMetadata(title: String, markerStart: Int, marker: String): Boolean {
        val prefix = title.substring(0, markerStart)
        val openRound = prefix.count { it == '(' } - prefix.count { it == ')' }
        val openFullWidthRound = prefix.count { it == '（' } - prefix.count { it == '）' }
        val openSquare = prefix.count { it == '[' } - prefix.count { it == ']' }
        val openCorner = prefix.count { it == '【' } - prefix.count { it == '】' }
        if (openSquare > 0 || openCorner > 0) return false
        if (openRound <= 0 && openFullWidthRound <= 0) return true

        // Parenthesized phase labels such as "（前篇）" are meaningful. Numbers inside metadata
        // such as "(COMIC X-EROS #32)" must never become chapter sequence markers.
        val previous = title.getOrNull(markerStart - 1)
        val next = title.getOrNull(markerStart + marker.length)
        return (previous == '(' && next == ')') || (previous == '（' && next == '）')
    }

    private data class MarkerPattern(
        val regex: Regex,
        val toMarker: (MatchResult) -> SequenceMarker?,
    )

    private data class SequenceMarker(
        val range: IntRange,
        val sortToken: String,
    )

    private val markerPatterns = listOf(
        MarkerPattern(
            regex = Regex("第\\s*(\\d+|[零〇一二两三四五六七八九十百千万]+)\\s*([话話章回集部卷册])"),
            toMarker = { match ->
                val order = parseNumber(match.groupValues[1]) ?: return@MarkerPattern null
                val counter = when (match.groupValues[2]) {
                    "話" -> "话"
                    else -> match.groupValues[2]
                }
                SequenceMarker(match.range, "第$order$counter")
            },
        ),
        MarkerPattern(
            regex = Regex("(?i)(?:^|(?<=[\\s_\\-~～—·・]))(ch(?:apter)?|vol(?:ume)?|part)\\.?\\s*(\\d+(?:\\.\\d+)?)"),
            toMarker = { match ->
                val label = match.groupValues[1].lowercase()
                val family = when {
                    label.startsWith("ch") -> "chapter"
                    label.startsWith("vol") -> "volume"
                    else -> "part"
                }
                decimalOrder(match.groupValues[2])?.let { order ->
                    SequenceMarker(match.range, "$family$order")
                }
            },
        ),
        MarkerPattern(
            regex = Regex("(?:^|(?<=[\\s_\\-~～—·・]))#\\s*(\\d+(?:\\.\\d+)?)"),
            toMarker = { match ->
                decimalOrder(match.groupValues[1])?.let { order ->
                    SequenceMarker(match.range, "number$order")
                }
            },
        ),
        MarkerPattern(
            regex = Regex("前篇|中篇|后篇|後篇|前編|中編|後編|上篇|下篇|上卷|中卷|下卷"),
            toMarker = { match ->
                val order = when (match.value.first()) {
                    '前', '上' -> 1
                    '中' -> 2
                    else -> 3
                }
                SequenceMarker(match.range, "sequence$order")
            },
        ),
        MarkerPattern(
            regex = Regex("(?:^|(?<=[\\s（(_\\-~～—·・]))([上中下])(?=$|[\\s）)\\[\\]【】_\\-~～—·・])"),
            toMarker = { match ->
                val order = when (match.groupValues[1]) {
                    "上" -> 1
                    "中" -> 2
                    else -> 3
                }
                SequenceMarker(match.range, "sequence$order")
            },
        ),
        MarkerPattern(
            regex = Regex(
                "(?:^|(?<=[\\s_\\-~～—·・]))((?=[MDCLXVI])M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3}))(?=$|[\\s（(\\[【_\\-~～—·・])",
            ),
            toMarker = { match ->
                romanOrder(match.groupValues[1])?.let { order ->
                    SequenceMarker(match.range, "roman$order")
                }
            },
        ),
    )

    private val metadataTail = Regex(
        "^[\\s）)_\\-~～—·・!！?？,，.。：:;；]*(?:(?:\\[[^]\\r\\n]*]|【[^】\\r\\n]*】|\\([^()\\r\\n]*\\)|（[^（）\\r\\n]*）)[\\s_\\-~～—·・!！?？,，.。：:;；]*)*$",
    )

    private fun parseNumber(value: String): BigInteger? {
        return value.toBigIntegerOrNull() ?: parseChineseNumber(value)?.toBigInteger()
    }

    private fun decimalOrder(value: String): BigInteger? {
        val parts = value.split('.', limit = 2)
        val whole = parts[0].toBigIntegerOrNull() ?: return null
        val fraction = parts.getOrNull(1)?.padEnd(6, '0')?.take(6)?.toBigIntegerOrNull() ?: BigInteger.ZERO
        return whole * DECIMAL_SCALE + fraction
    }

    private fun parseChineseNumber(value: String): Long? {
        if (value.isEmpty()) return null
        if (value.none(chineseUnits::containsKey)) {
            return value.fold(0L) { number, char ->
                number * 10 + (chineseDigits[char] ?: return null)
            }
        }
        var section = 0L
        var total = 0L
        var digit: Long? = null
        value.forEach { char ->
            val numeric = chineseDigits[char]
            if (numeric != null) {
                digit = numeric
                return@forEach
            }
            val unit = chineseUnits[char] ?: return null
            if (unit == 10_000L) {
                section += digit ?: 0L
                total += section * unit
                section = 0L
            } else {
                section += (digit ?: 1L) * unit
            }
            digit = null
        }
        return total + section + (digit ?: 0L)
    }

    private fun romanOrder(value: String): Int? {
        if (value.isEmpty() || value.any { it !in romanValues }) return null
        var total = 0
        value.forEachIndexed { index, char ->
            val current = romanValues.getValue(char)
            val next = value.getOrNull(index + 1)?.let(romanValues::getValue) ?: 0
            total += if (current < next) -current else current
        }
        return total.takeIf { it in 1..3999 && toRoman(it) == value }
    }

    private fun toRoman(value: Int): String {
        var remaining = value
        return buildString {
            romanTokens.forEach { (number, token) ->
                while (remaining >= number) {
                    append(token)
                    remaining -= number
                }
            }
        }
    }

    private val openingParentheses = setOf('(', '（')
    private val closingParentheses = setOf(')', '）')
    private val chineseDigits = mapOf(
        '零' to 0L,
        '〇' to 0L,
        '一' to 1L,
        '二' to 2L,
        '两' to 2L,
        '三' to 3L,
        '四' to 4L,
        '五' to 5L,
        '六' to 6L,
        '七' to 7L,
        '八' to 8L,
        '九' to 9L,
    )
    private val chineseUnits = mapOf('十' to 10L, '百' to 100L, '千' to 1000L, '万' to 10_000L)
    private val romanValues = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
    private val romanTokens = listOf(
        1000 to "M",
        900 to "CM",
        500 to "D",
        400 to "CD",
        100 to "C",
        90 to "XC",
        50 to "L",
        40 to "XL",
        10 to "X",
        9 to "IX",
        5 to "V",
        4 to "IV",
        1 to "I",
    )
    private val DECIMAL_SCALE = BigInteger.valueOf(1_000_000L)
}
