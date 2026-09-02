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

    /**
     * @param pinned Marks a page the user drew on purpose and that must be served back unchanged
     * until they ask for another one. Only the random sort pins pages: a shuffle is a request the
     * user made, not a feed they are keeping up with.
     */
    fun put(key: String, works: List<Work>, totalCount: Int, pinned: Boolean = false) {
        entries[key] = AudioPageSnapshot(
            works = works,
            totalCount = totalCount,
            savedAt = System.currentTimeMillis(),
            pinned = pinned,
        )
        trim()
    }

    fun clear() {
        entries.clear()
    }

    fun isFresh(snapshot: AudioPageSnapshot): Boolean {
        // A pinned page answers as fresh for as long as it lives. It is a selection the user is
        // still working through, and there is no way back to it once an age check discards it —
        // unlike an ordered list, where a refresh can only ever add newer rows to the same order.
        if (snapshot.pinned) return true
        return System.currentTimeMillis() - snapshot.savedAt < MAX_AGE.inWholeMilliseconds
    }

    private fun trim() {
        val excess = entries.size - MAX_CACHED_PAGES
        if (excess <= 0) return
        entries.entries
            // Pinned draws go last: evicting one would throw away the user's own selection while
            // ordinary pages can always be refetched identically.
            .sortedWith(compareBy({ it.value.pinned }, { it.value.savedAt }))
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

/**
 * @property savedAt Epoch millis the page was fetched, used for staleness checks.
 * @property pinned A user-requested draw that is served back unchanged until it is redrawn.
 */
data class AudioPageSnapshot(
    val works: List<Work>,
    val totalCount: Int,
    val savedAt: Long,
    val pinned: Boolean = false,
)
