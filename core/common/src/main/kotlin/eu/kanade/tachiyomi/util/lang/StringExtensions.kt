package eu.kanade.tachiyomi.util.lang

import androidx.core.text.parseAsHtml
import net.greypanther.natsort.CaseInsensitiveSimpleNaturalComparator
import java.nio.charset.StandardCharsets
import java.text.Collator
import java.util.Locale
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
 * Case-insensitive, locale-aware natural comparator for titles. Punctuation and whitespace are
 * ignored, so titles that only differ by separators (commas, brackets, spaces) still order by
 * their text/number suffixes. Leading title groups sort Latin, then CJK/other scripts, then digits.
 * Digits within a title sort after text, so "Tale 2" comes after "Tale" and "Tale extra";
 * numeric runs are compared by value ("ch. 2" before "ch. 10"); text runs are compared with the
 * given locale's collator (Chinese -> pinyin, Japanese -> gojūon).
 */
fun String.compareToCaseInsensitiveNaturalOrder(
    other: String,
    locale: Locale = Locale.getDefault(),
): Int {
    val byLeadingClass = leadingTitleClass().compareTo(other.leadingTitleClass())
    if (byLeadingClass != 0) return byLeadingClass

    val collator = collatorFor(locale)
    val thisRuns = sortRuns()
    val otherRuns = other.sortRuns()
    var i = 0
    var j = 0
    while (i < thisRuns.size && j < otherRuns.size) {
        val thisRun = thisRuns[i]
        val otherRun = otherRuns[j]
        val thisIsDigit = thisRun.first().isDigit()
        val otherIsDigit = otherRun.first().isDigit()
        if (thisIsDigit != otherIsDigit) {
            // Non-digits sort before digits, regardless of position.
            return if (thisIsDigit) 1 else -1
        }
        val byRun = if (thisIsDigit) {
            compareNumericRuns(thisRun, otherRun)
        } else {
            collator.compare(thisRun, otherRun)
        }
        if (byRun != 0) return byRun
        i++
        j++
    }
    // A shorter meaningful name ("Tale") still sorts before a longer one ("Tale 2").
    return thisRuns.drop(i).sumOf(String::length).compareTo(otherRuns.drop(j).sumOf(String::length))
}

private fun String.leadingTitleClass(): Int {
    val firstMeaningful = firstOrNull(Char::isLetterOrDigit) ?: return TITLE_CLASS_OTHER
    if (firstMeaningful.isDigit()) return TITLE_CLASS_DIGIT
    return if (Character.UnicodeScript.of(firstMeaningful.code) == Character.UnicodeScript.LATIN) {
        TITLE_CLASS_LATIN
    } else {
        TITLE_CLASS_OTHER
    }
}

private fun String.sortRuns(): List<String> {
    val runs = mutableListOf<String>()
    var current = StringBuilder()
    var currentIsDigit: Boolean? = null
    for (index in indices) {
        val char = this[index]
        if (char == '.' && currentIsDigit == true && getOrNull(index - 1)?.isDigit() == true &&
            getOrNull(index + 1)?.isDigit() == true
        ) {
            current.append(char)
            continue
        }
        if (!char.isLetterOrDigit()) continue
        val isDigit = char.isDigit()
        if (current.isNotEmpty() && isDigit != currentIsDigit) {
            runs += current.toString()
            current = StringBuilder()
        }
        current.append(char)
        currentIsDigit = isDigit
    }
    if (current.isNotEmpty()) runs += current.toString()
    return runs
}

private fun compareNumericRuns(first: String, second: String): Int {
    if (first.contains('.') || second.contains('.')) {
        val firstParts = first.split('.', limit = 2)
        val secondParts = second.split('.', limit = 2)
        val wholeComparison = compareIntegerRuns(firstParts[0], secondParts[0])
        if (wholeComparison != 0) return wholeComparison
        val firstFraction = firstParts.getOrNull(1).orEmpty().trimEnd('0')
        val secondFraction = secondParts.getOrNull(1).orEmpty().trimEnd('0')
        val maxLength = maxOf(firstFraction.length, secondFraction.length)
        return firstFraction.padEnd(maxLength, '0').compareTo(secondFraction.padEnd(maxLength, '0'))
    }
    return compareIntegerRuns(first, second)
}

private fun compareIntegerRuns(first: String, second: String): Int {
    val firstTrimmed = first.trimStart('0').ifEmpty { "0" }
    val secondTrimmed = second.trimStart('0').ifEmpty { "0" }
    if (firstTrimmed.length != secondTrimmed.length) {
        return firstTrimmed.length.compareTo(secondTrimmed.length)
    }
    return firstTrimmed.compareTo(secondTrimmed)
}

private fun collatorFor(locale: Locale): Collator {
    // Collator is not thread-safe; cache one per thread and locale.
    val cached = collatorCache.get()
    if (cached?.first == locale) return cached.second
    return Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        .also { collatorCache.set(locale to it) }
}

private val collatorCache = ThreadLocal<Pair<Locale, Collator>>()
private const val TITLE_CLASS_LATIN = 0
private const val TITLE_CLASS_OTHER = 1
private const val TITLE_CLASS_DIGIT = 2

/**
 * Natural order for image page names. Numeric page names must come before textual extras such as
 * "zzz", while the title comparator intentionally applies the opposite rule.
 */
fun String.compareToCaseInsensitiveNaturalPageOrder(other: String): Int {
    val thisStartsWithDigit = firstOrNull(Char::isLetterOrDigit)?.isDigit() == true
    val otherStartsWithDigit = other.firstOrNull(Char::isLetterOrDigit)?.isDigit() == true
    if (thisStartsWithDigit != otherStartsWithDigit) {
        return if (thisStartsWithDigit) -1 else 1
    }

    val comparator = CaseInsensitiveSimpleNaturalComparator.getInstance<String>()
    return comparator.compare(this, other)
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
