package eu.kanade.tachiyomi.data.audio

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Process-wide store for the track trees the backend has already described.
 *
 * The player resolves a work's streams through `/api/tracks/{id}` before every start, because a
 * stored address may predate the quality setting or the file layout that produced it. That is a
 * network round trip sitting in front of the first note, and until now the only thing making it
 * cheap was the five minute HTTP cache — which expires exactly when it is needed most, on a track
 * opened from history or from a playlist hours later.
 *
 * Holding the tree in memory turns that wait into a lookup. Nothing about the resolve step changes:
 * the same tree is rebuilt and the same stream is chosen, only without asking the backend again.
 *
 * Deliberately in-memory only, like [AudioPageCache]: a tree is a few hundred kilobytes of parsed
 * JSON that the backend can answer for in a few hundred milliseconds, so paying for a disk copy and
 * its invalidation rules is not worth it. A tree that turns out to be stale is caught by the
 * playback failure path, which drops the entry and re-resolves — see
 * `AudioPlayerController.recoverFromStaleTrackTree`.
 */
class AudioTrackCache(
    /** Injected so staleness can be tested without waiting; defaults to the wall clock. */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val entries = ConcurrentHashMap<Long, AudioTrackSnapshot>()

    /** Orders entries by arrival, independently of the clock's resolution. */
    private val sequence = AtomicLong()

    /**
     * The tree of [workId], or null when it has never been fetched or has outlived [MAX_AGE].
     */
    fun get(workId: Long): List<TrackNode>? {
        val snapshot = entries[workId] ?: return null
        if (now() - snapshot.savedAt >= MAX_AGE.inWholeMilliseconds) {
            entries.remove(workId, snapshot)
            return null
        }
        return snapshot.nodes
    }

    fun put(workId: Long, nodes: List<TrackNode>) {
        entries[workId] = AudioTrackSnapshot(nodes, now(), sequence.incrementAndGet())
        trim()
    }

    /**
     * Drops the tree of [workId] so the next resolve goes to the backend.
     *
     * Called when playback fails on an address that is simply gone: a cached tree is then the only
     * remaining suspect, and serving it again would repeat the same failure for [MAX_AGE].
     *
     * True when a tree was actually discarded, so a caller can tell "the address I reused was stale"
     * apart from "the address I just fetched is broken".
     */
    fun remove(workId: Long): Boolean = entries.remove(workId) != null

    fun clear() {
        entries.clear()
    }

    private fun trim() {
        val excess = entries.size - MAX_CACHED_TREES
        if (excess <= 0) return
        entries.entries
            // Ordered by insertion, not by the clock: two trees fetched inside the same millisecond
            // carry the same savedAt, and breaking that tie arbitrarily can evict the work being
            // played right now.
            .sortedBy { it.value.insertedAt }
            .take(excess)
            .forEach { entries.remove(it.key) }
    }

    companion object {
        /**
         * A handful of works covers listening to one title and flicking through what is nearby,
         * which is the only shape this cache is asked about.
         */
        private const val MAX_CACHED_TREES = 32

        /**
         * How long a tree is reused without asking the backend.
         *
         * Much longer than the browse pages: the files of a released work do not move, and a stale
         * tree costs a single failed start that invalidates it, whereas a stale list costs a wrong
         * screen the user has no reason to doubt.
         */
        val MAX_AGE: Duration = 30.minutes
    }
}

/**
 * @property savedAt Epoch millis the tree was fetched, used for staleness checks.
 * @property insertedAt Arrival order, used for eviction: see `AudioTrackCache.trim`.
 */
data class AudioTrackSnapshot(
    val nodes: List<TrackNode>,
    val savedAt: Long,
    val insertedAt: Long = 0,
)
