package eu.kanade.tachiyomi.data.audio

import eu.kanade.domain.base.BasePreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists locally collected audio works without expanding them into individual tracks. */
class AudioFavoriteStore(
    private val preferences: BasePreferences,
    private val json: Json,
) {

    @Synchronized
    fun load(): List<Work> {
        val raw = preferences.audioFavorites.get()
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<Work>>(raw) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun contains(workId: Long): Boolean = load().any { it.id == workId }

    /** Collects [work]; a work already present keeps the copy it already has. */
    @Synchronized
    fun add(work: Work) {
        val current = load()
        if (current.any { it.id == work.id }) return
        save(listOf(work) + current)
    }

    /** Drops [workId] from the local copy. */
    @Synchronized
    fun remove(workId: Long) {
        val current = load()
        if (current.none { it.id == workId }) return
        save(current.filterNot { it.id == workId })
    }

    /** Non-destructively adds account works while keeping all existing local favourites. */
    @Synchronized
    fun merge(works: List<Work>): Int {
        if (works.isEmpty()) return 0
        val current = load().toMutableList()
        val existingIds = current.mapTo(hashSetOf()) { it.id }
        val additions = works.filter { it.id !in existingIds }.distinctBy { it.id }
        if (additions.isEmpty()) return 0
        current.addAll(0, additions)
        save(current)
        return additions.size
    }

    private fun save(works: List<Work>) {
        preferences.audioFavorites.set(json.encodeToString(works.distinctBy { it.id }))
    }
}

fun AudioPlayItem.toWorkSnapshot(): Work = Work(
    id = workId,
    title = workTitle,
    name = circleName,
    mainCoverUrl = coverUrl,
)
