package eu.kanade.tachiyomi.util.lang

import androidx.core.text.parseAsHtml
import java.nio.charset.StandardCharsets
import kotlin.math.floor

/**
 * Replaces the given string to have at most [count] characters using [replacement] at its end.
 * If [replacement] is longer than [count] an exception will be thrown when `length > count`.
 */
fun String.chop(count: Int, replacement: String = "…"): String {
    return if (length > count) {
        take(count - replacement.length) + replacement
    } else {
        this
    }
}

/**
 * Replaces the given string to have at most [count] characters using [replacement] near the center.
 * If [replacement] is longer than [count] an exception will be thrown when `length > count`.
 */
fun String.truncateCenter(count: Int, replacement: String = "..."): String {
    if (length <= count) {
        return this
    }

    val pieceLength: Int = floor((count - replacement.length).div(2.0)).toInt()

    return "${take(pieceLength)}$replacement${takeLast(pieceLength)}"
}

/**
 * Case-insensitive natural comparator for strings. Punctuation and whitespace are
 * ignored, so titles that only differ by separators (commas, brackets, spaces) still
 * order by their text/number suffixes. Digits always sort after any letter or CJK
 * character, so "Tale 2" comes after "Tale" and "Tale extra"; numeric runs are
 * compared by value ("ch. 2" before "ch. 10").
 */
fun String.compareToCaseInsensitiveNaturalOrder(other: String): Int {
    var i = 0
    var j = 0
    while (i < length && j < other.length) {
        val thisChar = this[i]
        val otherChar = other[j]
        // Skip punctuation and whitespace entirely.
        if (!thisChar.isLetterOrDigit()) {
            i++
            continue
        }
        if (!otherChar.isLetterOrDigit()) {
            j++
            continue
        }
        val thisIsDigit = thisChar.isDigit()
        val otherIsDigit = otherChar.isDigit()
        if (thisIsDigit != otherIsDigit) {
            // Non-digits sort before digits, regardless of position.
            return if (thisIsDigit) 1 else -1
        }
        if (thisIsDigit) {
            var thisValue = 0L
            var otherValue = 0L
            while (i < length && this[i].isDigit()) {
                thisValue = thisValue * 10 + this[i].digitToInt()
                i++
            }
            while (j < other.length && other[j].isDigit()) {
                otherValue = otherValue * 10 + other[j].digitToInt()
                j++
            }
            if (thisValue != otherValue) {
                return if (thisValue < otherValue) -1 else 1
            }
        } else {
            val thisLower = thisChar.lowercaseChar()
            val otherLower = otherChar.lowercaseChar()
            if (thisLower != otherLower) {
                return if (thisLower < otherLower) -1 else 1
            }
            i++
            j++
        }
    }
    // Skip trailing punctuation before comparing leftovers, so a shorter meaningful
    // name ("Tale") still sorts before "Tale 2".
    while (i < length && !this[i].isLetterOrDigit()) i++
    while (j < other.length && !other[j].isLetterOrDigit()) j++
    return (length - i).compareTo(other.length - j)
}

/**
 * Returns the size of the string as the number of bytes.
 */
fun String.byteSize(): Int {
    return toByteArray(StandardCharsets.UTF_8).size
}

/**
 * Returns a string containing the first [n] bytes from this string, or the entire string if this
 * string is shorter.
 */
fun String.takeBytes(n: Int): String {
    val bytes = toByteArray(StandardCharsets.UTF_8)
    return if (bytes.size <= n) {
        this
    } else {
        bytes.decodeToString(endIndex = n).replace("\uFFFD", "")
    }
}

/**
 * HTML-decode the string
 */
fun String.htmlDecode(): String {
    return this.parseAsHtml().toString()
}
