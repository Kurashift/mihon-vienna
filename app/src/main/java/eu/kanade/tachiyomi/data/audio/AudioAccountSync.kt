package eu.kanade.tachiyomi.data.audio

import eu.kanade.domain.base.BasePreferences
import kotlinx.coroutines.CancellationException
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
}

/**
 * Synchronizes the work-level state supported by the audio backend. Exact track positions remain
 * local because the backend exposes no readable per-track playback-position field.
 */
class AudioAccountSync(
    private val api: KikoeruApi,
    private val preferences: BasePreferences,
    private val favoriteStore: AudioFavoriteStore,
) {
    private val mutex = Mutex()
    private val knownProgress = mutableMapOf<Long, AudioAccountProgress>()
    private var synchronizedIdentity: String? = null
    private var progressIdentity: String? = null

    suspend fun synchronize(force: Boolean = false) {
        val identity = currentIdentity() ?: return
        mutex.withLock {
            if (!force && synchronizedIdentity == identity) return
            try {
                knownProgress.clear()
                progressIdentity = identity
                for (progress in AudioAccountProgress.entries) {
                    if (currentIdentity() != identity) return
                    val works = fetchAll(progress)
                    if (currentIdentity() != identity) return
                    works.forEach { work -> knownProgress[work.id] = progress }
                    if (progress == AudioAccountProgress.MARKED) {
                        favoriteStore.merge(works)
                    }
                }
                synchronizedIdentity = identity
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Audio account sync failed" }
            }
        }
    }

    suspend fun updateProgress(workId: Long, progress: AudioAccountProgress?) {
        val identity = currentIdentity() ?: return
        mutex.withLock {
            if (progressIdentity != identity) {
                knownProgress.clear()
                progressIdentity = identity
            }
            if (currentIdentity() != identity || knownProgress[workId] == progress) return
            try {
                api.updateAccountProgress(workId, progress)
                if (progress == null) {
                    knownProgress.remove(workId)
                } else {
                    knownProgress[workId] = progress
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Audio account progress update failed" }
            }
        }
    }

    fun resetSession() {
        synchronizedIdentity = null
        progressIdentity = null
        knownProgress.clear()
    }

    private suspend fun fetchAll(progress: AudioAccountProgress): List<Work> {
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

internal fun hasNextAccountPage(response: WorksResponse, loadedCount: Int): Boolean {
    return response.works.isNotEmpty() && loadedCount < response.pagination.totalCount
}
