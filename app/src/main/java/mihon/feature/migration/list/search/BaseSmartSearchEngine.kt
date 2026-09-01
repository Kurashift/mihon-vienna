package mihon.feature.migration.list.search

import com.aallam.similarity.NormalizedLevenshtein
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import tachiyomi.core.common.util.lang.SearchTextNormalizer

typealias SearchAction<T> = suspend (String) -> List<T>

abstract class BaseSmartSearchEngine<T>(
    private val extraSearchParams: String? = null,
    private val eligibleThreshold: Double = MIN_ELIGIBLE_THRESHOLD,
) {
    private val normalizedLevenshtein = NormalizedLevenshtein()

    protected abstract fun getTitle(result: T): String

    protected suspend fun regularSearch(searchAction: SearchAction<T>, title: String): T? {
        val normalizedTitle = SearchTextNormalizer.normalize(title)
        return baseSearch(searchAction, listOf(title)) {
            normalizedLevenshtein.similarity(normalizedTitle, SearchTextNormalizer.normalize(getTitle(it)))
        }
    }

    protected suspend fun deepSearch(searchAction: SearchAction<T>, title: String): T? {
        val cleanedTitle = cleanDeepSearchTitle(title)

        val queries = getDeepSearchQueries(cleanedTitle)

        return baseSearch(searchAction, queries) {
            val cleanedMangaTitle = cleanDeepSearchTitle(getTitle(it))
            normalizedLevenshtein.similarity(cleanedTitle, cleanedMangaTitle)
        }
    }

    private suspend fun baseSearch(
        searchAction: SearchAction<T>,
        queries: List<String>,
        calculateDistance: (T) -> Double,
    ): T? {
        val eligibleManga = supervisorScope {
            queries.map { query ->
                async(Dispatchers.Default) {
                    val builtQuery = if (!extraSearchParams.isNullOrBlank()) {
                        "$query $extraSearchParams"
                    } else {
                        query
                    }

                    val candidates = searchAction(builtQuery)
                    candidates
                        .map {
                            val distance = if (queries.size > 1 || candidates.size > 1) {
                                calculateDistance(it)
                            } else {
                                1.0
                            }
                            SearchEntry(it, distance)
                        }
                        .filter { it.distance >= eligibleThreshold }
                }
            }
                .flatMap { it.await() }
        }

        return eligibleManga.maxByOrNull { it.distance }?.entry
    }

    private fun cleanDeepSearchTitle(title: String): String {
        // Normalize up front so traditional/simplified and full-width/half-width variants of the
        // same title collapse into one comparable form.
        val preTitle = SearchTextNormalizer.normalize(title)

        // Remove text in brackets
        var cleanedTitle = removeTextInBrackets(preTitle, true)
        if (cleanedTitle.length <= 5) { // Title is suspiciously short, try parsing it backwards
            cleanedTitle = removeTextInBrackets(preTitle, false)
        }

        // Strip chapter reference RU
        cleanedTitle = cleanedTitle.replace(chapterRefCyrillicRegexp, " ").trim()

        // Strip everything that is not a letter, a digit, a hyphen or a space. Letters are kept
        // for every script: the previous Latin-only pattern reduced CJK titles to whitespace,
        // which made deep search unusable for them.
        cleanedTitle = cleanedTitle.replace(titleRegex, " ")

        // Strip splitters and consecutive spaces
        cleanedTitle = cleanedTitle.trim().replace(" - ", " ").replace(consecutiveSpacesRegex, " ").trim()

        return cleanedTitle
    }

    private fun removeTextInBrackets(text: String, readForward: Boolean): String {
        val openingChars = if (readForward) "([<{" else ")]}>"
        val closingChars = if (readForward) ")]}>" else "([<{"
        var depth = 0

        return buildString {
            for (char in (if (readForward) text else text.reversed())) {
                when (char) {
                    in openingChars -> depth++
                    in closingChars -> if (depth > 0) depth-- // Avoid depth going negative on mismatched closing
                    else -> if (depth == 0) {
                        // If reading backward, the result is reversed, so prepend
                        if (readForward) append(char) else insert(0, char)
                    }
                }
            }
        }
    }

    private fun getDeepSearchQueries(cleanedTitle: String): List<String> {
        val splitCleanedTitle = cleanedTitle.split(" ")
        val splitSortedByLargest = splitCleanedTitle.sortedByDescending { it.length }

        if (splitCleanedTitle.isEmpty()) {
            return emptyList()
        }

        // Search cleaned title
        // Search two largest words
        // Search largest word
        // Search first two words
        // Search first word
        val searchQueries = listOf(
            listOf(cleanedTitle),
            splitSortedByLargest.take(2),
            splitSortedByLargest.take(1),
            splitCleanedTitle.take(2),
            splitCleanedTitle.take(1),
        )

        return searchQueries
            .map { it.joinToString(" ").trim() }
            .distinct()
    }

    companion object {
        const val MIN_ELIGIBLE_THRESHOLD = 0.4

        private val titleRegex = Regex("[^\\p{L}\\p{N} -]")
        private val consecutiveSpacesRegex = Regex(" +")
        private val chapterRefCyrillicRegexp = Regex("""((- часть|- глава) \d*)""")
    }
}

data class SearchEntry<T>(val entry: T, val distance: Double)
