package eu.kanade.tachiyomi.data.audio

import tachiyomi.core.common.util.lang.SearchTextNormalizer

/**
 * Prepares the user's search keywords for the backend's free-text search, which is a union by
 * design: its index covers work titles *and* tag names — the bare word 耳かき answers 4798 works
 * where the tag's own filter answers 4533, and the tagged works are all inside the free-text
 * result (`$tag:耳かき$ 耳かき` also answers exactly 4533). Space-separated terms are ANDed.
 *
 * Keywords therefore go out as free text — a word that names a tag finds both the tagged works
 * and the title matches, deliberately; strict tag-only filtering is what the category results
 * pages (pinned `$tag:Name$` filters) are for. What free text cannot do is match spellings the
 * backend does not fold: typing the traditional 處女 answers 2 works where the tag's simplified
 * alias 处女 answers 2640. Each term is thus compared with the category dictionaries through
 * [SearchTextNormalizer] — simplified/traditional, shinjitai/kyujitai, full/half width, case —
 * and a term that matches only under normalization is rewritten to the dictionary's own
 * spelling before sending. Everything else goes out exactly as typed: terms carrying a `$`
 * could corrupt the pinned-marker syntax on category pages, a term matching several
 * differently-spelled entries has no safe rewrite, and there is deliberately no blind
 * traditional→simplified conversion — the backend folds nothing, and rewriting 愛 (5235 works)
 * into 爱 (3553) would silently drop results. Only dictionary-anchored spellings are safe.
 *
 * Compilation reads the whole tag dictionary (tens of thousands of names) once per distinct
 * keyword; callers run it off the main thread and memoize by raw keyword, because every page of
 * one search must compile to the same request.
 *
 * @param snapshotProvider Reads the category dictionaries; pass [AudioCategoryCache.read]. A
 * null snapshot (nothing cached yet) leaves the keyword untouched.
 */
class AudioSearchCompiler(
    private val snapshotProvider: () -> AudioCategorySnapshot?,
) {

    fun compile(keyword: String): String {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return trimmed
        val snapshot = snapshotProvider() ?: return trimmed
        val exact = exactNames(snapshot)
        // Only terms that are not already a dictionary spelling pay for the normalized scan
        // over every name, so a keyword of exact names never builds it at all.
        val normalized = lazy { normalizedIndex(snapshot) }
        return trimmed.split(TERM_SEPARATOR)
            .filter { it.isNotEmpty() }
            .joinToString(separator = " ") { term ->
                when {
                    // A term carrying '$' of its own goes out untouched: on a pinned category
                    // page it is appended after the `$tag:Name$` marker, where a stray '$'
                    // could close the marker early and corrupt the query.
                    term.contains('$') -> term
                    term in exact -> term
                    else -> normalized.value[SearchTextNormalizer.normalize(term)]
                        ?.distinct()
                        ?.singleOrNull()
                        ?: term
                }
            }
    }

    private fun names(snapshot: AudioCategorySnapshot): Sequence<String> =
        snapshot.tags.asSequence().map { it.name } +
            snapshot.vas.asSequence().map { it.name } +
            snapshot.circles.asSequence().map { it.name }

    private fun exactNames(snapshot: AudioCategorySnapshot): Set<String> =
        names(snapshot).filter { it.isNotBlank() && !it.contains('$') }.toHashSet()

    private fun normalizedIndex(snapshot: AudioCategorySnapshot): Map<String, List<String>> =
        names(snapshot)
            .filter { it.isNotBlank() && !it.contains('$') }
            .groupBy { SearchTextNormalizer.normalize(it) }

    private companion object {
        // \s covers ASCII whitespace; the two additions are the full-width ideographic space and
        // the no-break space, both of which reach the search box from CJK and web input.
        val TERM_SEPARATOR = Regex("[\\s\\u00A0\\u3000]+")
    }
}
