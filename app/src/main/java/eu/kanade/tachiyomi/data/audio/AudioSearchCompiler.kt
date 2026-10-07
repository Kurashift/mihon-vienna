package eu.kanade.tachiyomi.data.audio

import tachiyomi.core.common.util.lang.SearchTextNormalizer

/**
 * Rewrites the user's search keywords into the backend's advanced search syntax.
 *
 * The backend parses `$tag:Name$`-style markers in the search path and ANDs them with the
 * remaining free-text terms (measured against the live API on 2026-10-07: `$tag:耳かき$` answers
 * 4533 works, adding the free word `囁き` narrows them to 146; two space-separated markers
 * intersect — `$tag:耳かき$ $tag:オナニー$` answers 57). Free text alone never filters by tag:
 * the bare word 耳かき answers 4798 works, i.e. title matches beyond the tag's own 4533.
 *
 * Every whitespace-separated term that names a real dictionary entry is therefore rewritten into
 * that entry's marker, carrying the dictionary's own spelling. Comparisons run through
 * [SearchTextNormalizer], which is what makes simplified and traditional input land on the same
 * entry: 处女 and 處女 both reach the tag named 処女. Terms that match nothing stay free text —
 * the backend's own index covers those, its simplified-Chinese aliases included. There is
 * deliberately no blind traditional→simplified rewriting of free text: the backend does not fold
 * variants, and rewriting 愛 (5235 works) into 爱 (3553) would silently drop results.
 *
 * Markers have to be separated from the rest by spaces — two concatenated markers parse as
 * nothing — so the compiled parts are always joined with single spaces.
 *
 * Compilation reads the whole tag dictionary (tens of thousands of names, each normalized) once
 * per distinct keyword; callers run it off the main thread and memoize by raw keyword, because
 * every page of one search must compile to the same request.
 *
 * @param snapshotProvider Reads the category dictionaries; pass [AudioCategoryCache.read]. A null
 * snapshot (nothing cached yet) leaves the keyword untouched.
 */
class AudioSearchCompiler(
    private val snapshotProvider: () -> AudioCategorySnapshot?,
) {

    fun compile(keyword: String): String {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return trimmed
        val snapshot = snapshotProvider() ?: return trimmed
        val exact = exactIndex(snapshot)
        // Only terms that missed the exact index pay for the normalized scan over every
        // dictionary name, so a keyword of exact names never builds it at all.
        val normalized = lazy { normalizedIndex(snapshot) }
        return trimmed.split(TERM_SEPARATOR)
            .filter { it.isNotEmpty() }
            .joinToString(separator = " ") { term ->
                when {
                    // A term carrying '$' of its own is left alone: wrapped into a marker it
                    // could break the syntax open (a '$' closes the marker), and as free text
                    // it is harmless.
                    term.contains('$') -> term
                    else -> {
                        val marker = exact[term]?.marker()
                            ?: normalized.value[SearchTextNormalizer.normalize(term)]
                                ?.map { it.marker() }
                                ?.distinct()
                                ?.singleOrNull()
                        marker ?: term
                    }
                }
            }
    }

    /** One dictionary name eligible for a marker, with the field it was found under. */
    private data class Entry(val field: AudioCategoryField, val name: String) {
        fun marker(): String = field.legacyKeyword(name)
    }

    private fun entries(snapshot: AudioCategorySnapshot): Sequence<Entry> =
        snapshot.tags.asSequence().map { Entry(AudioCategoryField.TAG, it.name) } +
            snapshot.vas.asSequence().map { Entry(AudioCategoryField.VA, it.name) } +
            snapshot.circles.asSequence().map { Entry(AudioCategoryField.CIRCLE, it.name) }

    private fun exactIndex(snapshot: AudioCategorySnapshot): Map<String, Entry> {
        val index = HashMap<String, Entry>()
        // Tags win a name shared across dictionaries: a typed word is by far most often meant as
        // a tag, and the marker carries no id, so dropping the other fields' claim costs nothing.
        entries(snapshot)
            .filter { it.name.isNotBlank() && !it.name.contains('$') }
            .forEach { entry -> index.putIfAbsent(entry.name, entry) }
        return index
    }

    private fun normalizedIndex(snapshot: AudioCategorySnapshot): Map<String, List<Entry>> =
        entries(snapshot)
            .filter { it.name.isNotBlank() && !it.name.contains('$') }
            .groupBy { SearchTextNormalizer.normalize(it.name) }

    private companion object {
        // \s covers ASCII whitespace; the two additions are the full-width ideographic space and
        // the no-break space, both of which reach the search box from CJK and web input.
        val TERM_SEPARATOR = Regex("[\\s\\u00A0\\u3000]+")
    }
}
