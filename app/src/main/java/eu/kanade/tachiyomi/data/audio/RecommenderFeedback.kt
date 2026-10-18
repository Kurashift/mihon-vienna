package eu.kanade.tachiyomi.data.audio

import eu.kanade.domain.base.BasePreferences
import java.util.UUID

/**
 * The UUID the recommender files this install's listens under, created on first use.
 *
 * The website mints one per browser profile and the account carries its own, so the value has to
 * survive between launches: minting a fresh one per session would file every listen under a profile
 * nobody ever reads again. Signed in, the login overwrites this with the account's UUID, which is
 * what lets the app and the website feed one profile.
 */
internal fun BasePreferences.recommenderUuid(): String {
    val existing = audioRecommenderUuid.get()
    if (existing.isNotBlank()) return existing
    return UUID.randomUUID().toString().also { audioRecommenderUuid.set(it) }
}

/**
 * The listen milestones the backend's recommender listens for, from first play to hearing a work
 * out.
 *
 * The website reports these from its player, and the profile they build is keyed by the account's
 * recommender UUID rather than by the work, so both clients feed one profile as long as they send
 * the same names.
 */
internal enum class RecommenderFeedback(val wireValue: String) {
    START_LISTEN("start-listen"),
    LISTEN_5_MIN("listen-5mins"),
    LISTEN_15_MIN("listen-15mins"),
    LISTEN_30_MIN("listen-30mins"),
    LISTEN_60_MIN("listen-60mins"),
    LISTEN_30_PERCENT("listen-30percent"),
    LISTEN_60_PERCENT("listen-60percent"),
    LISTEN_90_PERCENT("listen-90percent"),
    ;

    /**
     * Whether [playedMs] out of [totalMs] has reached this milestone.
     *
     * Percent milestones need a duration to be a percentage of; a work whose length the backend did
     * not report simply never reaches them, which is better than treating every listen as complete.
     */
    fun isDue(playedMs: Long, totalMs: Long): Boolean = when (this) {
        START_LISTEN -> true
        LISTEN_5_MIN -> playedMs >= MINUTE_MS * 5
        LISTEN_15_MIN -> playedMs >= MINUTE_MS * 15
        LISTEN_30_MIN -> playedMs >= MINUTE_MS * 30
        LISTEN_60_MIN -> playedMs >= MINUTE_MS * 60
        LISTEN_30_PERCENT -> totalMs > 0 && playedMs * 10 >= totalMs * 3
        LISTEN_60_PERCENT -> totalMs > 0 && playedMs * 10 >= totalMs * 6
        LISTEN_90_PERCENT -> totalMs > 0 && playedMs * 10 >= totalMs * 9
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}

/**
 * Turns a running play position into the milestones that have just come due.
 *
 * Counts wall-clock time spent playing rather than the position, so seeking forward does not claim
 * a listen that never happened, and pausing does not either. Each milestone comes back once per
 * work: the backend treats a repeat as another engagement, which would let a replayed file report
 * "heard it out" without ever being heard out.
 */
internal class RecommenderFeedbackTracker {

    private var workId: Long? = null
    private var totalMs = 0L
    private var playedMs = 0L
    private var lastTickAt = 0L
    private var ticking = false
    private val reported = mutableSetOf<RecommenderFeedback>()

    /**
     * Feeds one tick and answers the milestones that came due with it.
     *
     * @param workId the work playing now, or null when nothing is.
     * @param totalMs the length of the whole work, which is what the percentage milestones are a
     * percentage of — not the current track, or a five-track work would report "heard it out" five
     * times.
     */
    fun onTick(workId: Long?, totalMs: Long, nowMs: Long, playing: Boolean): List<RecommenderFeedback> {
        if (workId == null) {
            reset()
            return emptyList()
        }
        if (workId != this.workId) {
            reset()
            this.workId = workId
        }
        if (totalMs > 0) this.totalMs = totalMs
        if (!playing) {
            ticking = false
            return emptyList()
        }
        if (ticking) playedMs += (nowMs - lastTickAt).coerceAtLeast(0)
        ticking = true
        lastTickAt = nowMs

        val due = RecommenderFeedback.entries.filter { it !in reported && it.isDue(playedMs, this.totalMs) }
        if (due.isEmpty()) return emptyList()
        reported += due
        return due
    }

    private fun reset() {
        workId = null
        totalMs = 0
        playedMs = 0
        lastTickAt = 0
        ticking = false
        reported.clear()
    }
}
