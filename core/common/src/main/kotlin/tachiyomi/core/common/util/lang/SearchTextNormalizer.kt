package tachiyomi.core.common.util.lang

import java.text.Normalizer

/**
 * Text normalization used to make search comparisons tolerant of the many ways the "same"
 * title can be spelled. Library search, chapter search and migration all compare raw strings,
 * which used to mean that any of the following differences made a title unsearchable:
 *
 * - Half-width vs full-width punctuation and letters (e.g. `ＡＢＣ` vs `ABC`, `：` vs `:`)
 * - Traditional vs simplified Chinese (e.g. `進擊的巨人` vs `进击的巨人`)
 * - Japanese shinjitai vs kyujitai (e.g. `桜` vs `櫻`) and katakana vs hiragana
 * - Stray or doubled whitespace, zero-width and variation-selector characters
 * - Case differences for non-ASCII scripts (Greek, Cyrillic, full-width Latin)
 *
 * Feeding both the query and the candidate text through [normalize] makes a plain substring
 * check behave the way users expect, without needing SQLite FTS.
 *
 * On top of that, [containsSearch] falls back to pinyin for romanized queries, so `jinji` and
 * `jj` both reach 进击. That path lives in [SearchPinyinData].
 *
 * The result is only meant for comparisons: it is lossy and must never be shown to the user.
 */
object SearchTextNormalizer {

    /**
     * Upper bound on the number of cached normalizations.
     *
     * Deliberately small. Raising it to 8192 was measured on a 1000/5000 title library and the
     * difference landed inside the noise (the 1000-title case got slower, the 5000-title case
     * faster), because a large library has far more distinct field values than any cache will
     * hold - the miss path is the common one either way. Filtering runs off the main thread
     * behind a 250 ms debounce, so the current cost is not what dominates perceived latency.
     */
    private const val CACHE_LIMIT = 1024

    /** Distance from katakana to the matching hiragana code point. */
    private const val KATAKANA_OFFSET = 0x60

    private val lock = Any()

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            return size > CACHE_LIMIT
        }
    }

    /**
     * Returns a comparable form of [text], or `text` itself when it is already normalized.
     */
    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        synchronized(lock) {
            cache[text]?.let { return it }
        }
        val normalized = normalizeUncached(text)
        synchronized(lock) {
            cache[text] = normalized
        }
        return normalized
    }

    /**
     * Substring check that falls back to normalized comparison and then to pinyin.
     *
     * The fast path keeps pure-ASCII comparisons - by far the most common case - free of any
     * normalization cost: `contains` with `ignoreCase` already handles them, and normalization
     * is a no-op for ASCII other than lower-casing.
     */
    fun String.containsSearch(other: String): Boolean {
        if (isEmpty()) return other.isEmpty()
        if (contains(other, ignoreCase = true)) return true
        if (!hasNonAscii(this) && !hasNonAscii(other)) return false
        val normalizedOther = normalize(other)
        if (normalizedOther.isEmpty()) return false
        val normalizedThis = normalize(this)
        if (normalizedThis.contains(normalizedOther)) return true
        return matchesByPinyin(normalizedThis, normalizedOther)
    }

    /**
     * Last resort: treat a romanized query as pinyin and look for it in the Han characters of
     * the text, so `jinji` finds 进击 and `jj` does too.
     *
     * Gated on the query being romanized and the text containing Han characters, which keeps
     * the cost at zero for the overwhelmingly common case of an English query or an English
     * title. [SearchPinyinData] loads its table on first use, so this only ever runs - and only
     * ever pays - once a user actually types pinyin.
     */
    private fun matchesByPinyin(normalizedText: String, normalizedQuery: String): Boolean {
        if (!hasHan(normalizedText)) return false

        var letters = 0
        var spaced = false
        for (i in normalizedQuery.indices) {
            val char = normalizedQuery[i]
            when {
                char in 'a'..'z' -> letters++
                char == ' ' -> spaced = true
                else -> return false
            }
        }
        if (letters < SearchPinyinData.MIN_QUERY) return false

        val query = if (spaced) normalizedQuery.replace(" ", "") else normalizedQuery
        return SearchPinyinData.matches(normalizedText, query)
    }

    private fun hasNonAscii(value: String): Boolean {
        for (i in value.indices) {
            if (value[i].code > 0x7F) return true
        }
        return false
    }

    private fun hasHan(value: String): Boolean {
        for (i in value.indices) {
            val code = value[i].code
            if (code in 0x4E00..0x9FFF) return true
        }
        return false
    }

    private fun normalizeUncached(text: String): String {
        // NFKC folds full-width forms, compatibility ideographs, ligatures and ligated
        // Roman numerals into their canonical half-width equivalents.
        val folded = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKC)

        val out = StringBuilder(folded.length)
        var pendingSpace = false
        var i = 0
        while (i < folded.length) {
            val codePoint = folded.codePointAt(i)
            i += Character.charCount(codePoint)

            if (isIgnorable(codePoint)) continue

            if (Character.isWhitespace(codePoint)) {
                // Collapse runs of whitespace into a single separator; titles routinely differ
                // only by spacing.
                pendingSpace = true
                continue
            }

            if (pendingSpace) {
                if (out.isNotEmpty()) out.append(' ')
                pendingSpace = false
            }

            if (codePoint <= 0xFFFF) {
                out.append(SearchChineseVariants.toSimplified(foldJapanese(codePoint.toChar())))
            } else {
                out.appendCodePoint(codePoint)
            }
        }

        val length = out.length
        var end = length
        while (end > 0 && out[end - 1] == ' ') end--
        var start = 0
        while (start < end && out[start] == ' ') start++
        return if (start == 0 && end == length) out.toString() else out.substring(start, end)
    }

    /**
     * Characters that carry no meaning for search: zero-width joiners, bidirectional marks,
     * soft hyphens and the variation selectors that decide whether a CJK glyph is rendered in
     * its Chinese or Japanese form.
     */
    private fun isIgnorable(codePoint: Int): Boolean {
        return when (codePoint) {
            0x00AD -> true // soft hyphen
            0x200B, 0x200C, 0x200D, 0x200E, 0x200F -> true
            0x2028, 0x2029 -> true
            in 0x202A..0x202E -> true
            in 0x2060..0x2064 -> true
            0xFEFF -> true
            in 0xFE00..0xFE0F -> true // variation selectors
            in 0xE0100..0xE01EF -> true // variation selectors supplement
            else -> false
        }
    }

    /**
     * Folds the two Japanese-specific sources of variation so that
     * [SearchChineseVariants.toSimplified] can finish the job.
     *
     * Katakana is shifted down to hiragana - a plain code point offset, no table needed - so a
     * title written in either syllabary is reachable from a query written in the other.
     *
     * Shinjitai is mapped to its kyujitai, deliberately *not* straight to simplified Chinese:
     * 桜 -> 櫻 falls through the traditional -> simplified step below to 樱, and 櫻 reaches 樱
     * on its own. Both spellings therefore land on the same character.
     */
    private fun foldJapanese(char: Char): Char {
        if (char in '\u30A1'..'\u30F6') {
            return (char.code - KATAKANA_OFFSET).toChar()
        }
        return SearchJapaneseVariants.toKyujitai(char)
    }
}
