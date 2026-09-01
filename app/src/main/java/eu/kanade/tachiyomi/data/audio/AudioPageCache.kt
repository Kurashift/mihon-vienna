package eu.kanade.tachiyomi.data.audio

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Process-wide store for the pages the browse screen has fetched.
 *
 * This used to be a field of `AudioBrowseViewModel`, which meant leaving the audio section threw
 * every page away: coming back always behaved like a cold start even though the rows had been
 * fetched moments earlier. Holding it in Injekt lets the pages outlive the screen and its
 * ViewModel, so re-entering within [MAX_AGE] paints what was there before and skips the network.
 *
 * Deliberately in-memory only, unlike [AudioCategoryCache]: a list that is hours old is worse
 * than a short spinner, and the OkHttp disk cache already covers the first minutes back.
 */
class AudioPageCache {

    private val entries = ConcurrentHashMap<String, AudioPageSnapshot>()

    fun get(key: String): AudioPageSnapshot? = entries[key]

    fun put(key: String, works: List<Work>, totalCount: Int) {
        entries[key] = AudioPageSnapshot(
            works = works,
            totalCount = totalCount,
            savedAt = System.currentTimeMillis(),
        )
        trim()
    }

    fun clear() {
        entries.clear()
    }

    fun isFresh(snapshot: AudioPageSnapshot): Boolean {
        return System.currentTimeMillis() - snapshot.savedAt < MAX_AGE.inWholeMilliseconds
    }

    private fun trim() {
        val excess = entries.size - MAX_CACHED_PAGES
        if (excess <= 0) return
        entries.entries
            .sortedBy { it.value.savedAt }
            .take(excess)
            .forEach { entries.remove(it.key) }
    }

    companion object {
        /** Enough for every tab/sort combination without growing unbounded. */
        private const val MAX_CACHED_PAGES = 24

        /**
         * How long a page is served without asking the backend again. Works are released a few
         * times a day, not a few times a minute, so flipping between tabs — or leaving the section
         * and coming back — has no reason to spend a round trip on data that has not moved.
         */
        val MAX_AGE: Duration = 30.minutes
    }
}

/** @property savedAt Epoch millis the page was fetched, used for staleness checks. */
data class AudioPageSnapshot(
    val works: List<Work>,
    val totalCount: Int,
    val savedAt: Long,
)
