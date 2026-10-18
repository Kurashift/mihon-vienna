package eu.kanade.tachiyomi.data.audio

import eu.kanade.domain.base.BasePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

enum class AudioAccountProgress(val wireValue: String) {
    MARKED("marked"),
    LISTENING("listening"),
    LISTENED("listened"),
    REPLAY("replay"),
    POSTPONED("postponed"),
    ;

    companion object {
        /** The state [value] names, or null when it names none of them — a cleared row. */
        fun fromWire(value: String?): AudioAccountProgress? =
            value?.let { wire -> entries.firstOrNull { it.wireValue == wire } }
    }
}

/**
 * Reads and writes the per-work state the account keeps on the backend.
 *
 * Only collecting a work writes here. Playback deliberately does not: the backend keeps one row per
 * work and creates it on the first write, so reporting progress from the player filled the account —
 * and with it the website's list — with works that had only been played. Exact positions stay in
 * [AudioHistoryStore], which is local and always has been.
 */
class AudioAccountSync(
    private val api: KikoeruApi,
    private val preferences: BasePreferences,
    private val favoriteStore: AudioFavoriteStore,
) {
    private val mutex = Mutex()
    private val knownProgress = mutableMapOf<Long, AudioAccountProgress>()
    private var accountWorks: List<Work> = emptyList()
    private var synchronizedIdentity: String? = null
    private var progressIdentity: String? = null

    private val _revision = MutableStateFlow(0L)

    /** Bumped whenever [works] or a work's state changed, so a screen showing them can repaint. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun isSignedIn(): Boolean = currentIdentity() != null

    /** Whether the account has answered at least once, so [works] is the truth and not a blank. */
    fun hasLoaded(): Boolean = synchronizedIdentity != null

    /** Every row the account holds, as the backend last answered it. */
    fun works(): List<Work> = accountWorks

    /** The rows the user collected, which is the only thing the favourites tab shows. */
    fun markedWorks(): List<Work> = accountWorks.filter { it.isMarkedOnAccount() }

    fun progressOf(workId: Long): AudioAccountProgress? = knownProgress[workId]

    fun isMarked(workId: Long): Boolean = knownProgress[workId] == AudioAccountProgress.MARKED

    suspend fun synchronize(force: Boolean = false) {
        val identity = currentIdentity() ?: return
        mutex.withLock {
            if (!force && synchronizedIdentity == identity) return
            try {
                progressIdentity = identity
                // One unfiltered pass carries every row together with the state it is in, so the
                // five filtered lists the website asks for are not needed here. It is also the
                // only request that returns rows whose state was cleared.
                val works = fetchAll(null)
                if (currentIdentity() != identity) return
                knownProgress.clear()
                works.forEach { work ->
                    AudioAccountProgress.fromWire(work.progress)?.let { knownProgress[work.id] = it }
                }
                accountWorks = works
                favoriteStore.merge(works.filter { it.isMarkedOnAccount() })
                synchronizedIdentity = identity
                bumpRevision()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Audio account sync failed" }
            }
        }
    }

    /**
     * Marks or unmarks [work] on the account, returning false when the write did not land.
     *
     * Unmarking deletes the row rather than clearing its state: a cleared row keeps appearing in
     * the website's list with no state at all, which is what made an uncollect there look like it
     * had never been applied.
     */
    suspend fun setMarked(work: Work, marked: Boolean): Boolean {
        val identity = currentIdentity() ?: return false
        mutex.withLock {
            if (progressIdentity != identity) {
                knownProgress.clear()
                progressIdentity = identity
            }
            try {
                if (marked) {
                    api.updateAccountProgress(work.id, AudioAccountProgress.MARKED)
                    knownProgress[work.id] = AudioAccountProgress.MARKED
                    // The favourites list is built from this list, so a mark has to land here as
                    // well. Leaving it to the next sync is what made a new favourite show up only
                    // after the tab was reopened and had waited for a round trip.
                    accountWorks = listOf(work.copy(progress = AudioAccountProgress.MARKED.wireValue)) +
                        accountWorks.filterNot { it.id == work.id }
                } else {
                    api.deleteAccountReview(work.id)
                    knownProgress.remove(work.id)
                    accountWorks = accountWorks.filterNot { it.id == work.id }
                }
                bumpRevision()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Audio account favourite update failed" }
                return false
            }
        }
    }

    /**
     * Drops every work in [workIds] from the account and answers how many rows went away.
     *
     * The backend has one row per work and no bulk delete, so this is still one request per work.
     * They go out together rather than one after another, and the list is repainted once at the
     * end — the reason a bulk removal used to look like it was working through the selection item
     * by item is that every single row bumped the revision on its own.
     */
    suspend fun uncollectAll(workIds: Collection<Long>): Int {
        if (workIds.isEmpty()) return 0
        val identity = currentIdentity() ?: return 0
        mutex.withLock {
            if (progressIdentity != identity) {
                knownProgress.clear()
                progressIdentity = identity
            }
            val ids = workIds.toList()
            val dropped = coroutineScope {
                ids.map { id ->
                    async {
                        try {
                            api.deleteAccountReview(id)
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logcat(LogPriority.WARN, e) { "Audio account uncollect failed" }
                            false
                        }
                    }
                }.awaitAll()
            }
            val removed = ids.filterIndexed { index, _ -> dropped[index] }.toSet()
            if (removed.isEmpty()) return 0
            knownProgress.keys.removeAll(removed)
            accountWorks = accountWorks.filterNot { it.id in removed }
            bumpRevision()
            return removed.size
        }
    }

    fun resetSession() {
        synchronizedIdentity = null
        progressIdentity = null
        knownProgress.clear()
        accountWorks = emptyList()
        bumpRevision()
    }

    private fun bumpRevision() {
        _revision.value += 1
    }

    private suspend fun fetchAll(progress: AudioAccountProgress?): List<Work> {
        val works = mutableListOf<Work>()
        var page = 1
        while (page <= MAX_ACCOUNT_PAGES) {
            val response = api.fetchAccountWorks(progress, page)
            works += response.works
            if (!hasNextAccountPage(response, works.size)) break
            page++
        }
        return works.distinctBy { it.id }
    }

    private fun currentIdentity(): String? {
        val token = preferences.audioAuthToken.get()
        val username = preferences.audioUsername.get()
        if (token.isBlank() || username.isBlank()) return null
        return "$username\u0000${token.hashCode()}"
    }

    private companion object {
        const val MAX_ACCOUNT_PAGES = 200
    }
}

private fun Work.isMarkedOnAccount(): Boolean =
    AudioAccountProgress.fromWire(progress) == AudioAccountProgress.MARKED

internal fun hasNextAccountPage(response: WorksResponse, loadedCount: Int): Boolean {
    return response.works.isNotEmpty() && loadedCount < response.pagination.totalCount
}
