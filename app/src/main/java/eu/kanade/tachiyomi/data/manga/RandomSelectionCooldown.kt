package eu.kanade.tachiyomi.data.manga

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import tachiyomi.core.common.preference.Preference
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Remembers the works a random pick recently offered, so the same one does not come straight back.
 * Shared by every random entry point: the dice on a work's page, the browse and library toolbar
 * buttons, and the reader's swipe jumps.
 *
 * The cooldown is per work, not per chapter. Every pick chooses a work first and a chapter within
 * it second, so cooling a single chapter would leave that work's other chapters eligible and let
 * the same work return wearing a different chapter - which is exactly what reads as "the same work
 * again" to whoever swiped.
 */
class RandomSelectionCooldown(
    private val preference: Preference<String>,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val randomIndex: (Int) -> Int = { Random.nextInt(it) },
) {

    @Synchronized
    fun pickManga(candidates: Collection<Long>, currentMangaId: Long? = null): Long? {
        val pool = candidates
            .asSequence()
            .filter { it > 0 && it != currentMangaId }
            .distinct()
            .toList()
        if (pool.isEmpty()) return null

        val resolvedPool = resolvePool(pool) { it }
        val selected = resolvedPool[randomIndex(resolvedPool.size)]
        rememberManga(selected)
        return selected
    }

    /**
     * The [candidates] whose work is not cooling down, in the order they were given.
     *
     * [currentMangaId] is dropped as well, so a jump always leaves the work being read. It only
     * stays in play when it is the entire pool, where dropping it would leave nothing to pick.
     */
    @Synchronized
    fun <T> eligibleChapters(
        candidates: Collection<T>,
        currentMangaId: Long?,
        releaseOnExhaustion: Boolean,
        mangaId: (T) -> Long,
        chapterId: (T) -> Long,
    ): List<T> {
        val pool = candidates.distinctBy { mangaId(it) to chapterId(it) }
        if (pool.isEmpty()) return emptyList()

        val others = if (currentMangaId != null && pool.any { mangaId(it) != currentMangaId }) {
            pool.filterNot { mangaId(it) == currentMangaId }
        } else {
            pool
        }

        val entries = activeEntries()
        val cooledMangaIds = entries.mapTo(mutableSetOf()) { it.mangaId }
        val available = others.filterNot { mangaId(it) in cooledMangaIds }
        if (available.isNotEmpty()) return available
        if (!releaseOnExhaustion) return emptyList()
        return resolvePool(others, mangaId)
    }

    /** Cools [mangaId] until the window passes, so the next pick cannot offer it again. */
    @Synchronized
    fun rememberManga(mangaId: Long) {
        if (mangaId <= 0) return
        val entries = activeEntries()
            .filterNot { it.mangaId == mangaId }
            .plus(Entry(mangaId, now()))
            .takeLast(MAX_ENTRIES)
        writeEntries(entries)
    }

    private fun <T> resolvePool(pool: List<T>, mangaId: (T) -> Long): List<T> {
        val entries = activeEntries()
        val cooledMangaIds = entries.mapTo(mutableSetOf()) { it.mangaId }
        val available = pool.filterNot { mangaId(it) in cooledMangaIds }
        if (available.isNotEmpty()) return available

        // Exhaustion: every work in the pool is cooling down. Hold back the newest poolSize - 1 of
        // them, which leaves the one waiting longest drawable. Wiping the window instead would
        // offer the work just left, and returning nothing would strand a shelf smaller than it.
        val poolIds = pool.mapTo(mutableSetOf(), mangaId)
        val keepCount = (poolIds.size - 1).coerceIn(1, MAX_ENTRIES)
        val kept = entries.filter { it.mangaId in poolIds }.takeLast(keepCount)
        if (kept.isEmpty()) return pool
        writeEntries(kept)
        val keptMangaIds = kept.mapTo(mutableSetOf()) { it.mangaId }
        return pool.filterNot { mangaId(it) in keptMangaIds }.ifEmpty { pool }
    }

    private fun activeEntries(): List<Entry> {
        val currentTime = now()
        val parsed = readEntries()
        val active = parsed.filter { currentTime - it.at in 0 until WINDOW_MILLIS }
        if (active.size != parsed.size) writeEntries(active)
        return active
    }

    private fun readEntries(): List<Entry> {
        val raw = runCatching(preference::get).getOrDefault("")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                runCatching {
                    val item = element.jsonObject
                    val mangaId = item[MANGA_ID]?.jsonPrimitive?.longOrNull ?: -1L
                    val at = item[AT]?.jsonPrimitive?.longOrNull ?: -1L
                    Entry(mangaId, at).takeIf { mangaId > 0 && at >= 0 }
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun writeEntries(entries: List<Entry>) {
        val serialized = if (entries.isEmpty()) {
            ""
        } else {
            buildJsonArray {
                entries.forEach { entry ->
                    add(
                        buildJsonObject {
                            put(MANGA_ID, entry.mangaId)
                            put(AT, entry.at)
                        },
                    )
                }
            }.toString()
        }
        runCatching { preference.set(serialized) }
    }

    private data class Entry(
        val mangaId: Long,
        val at: Long,
    )

    private companion object {
        const val WINDOW_MILLIS = 60 * 60 * 1000L

        /**
         * Sized to cover a full reading session on a large library rather than the handful the
         * window used to hold: at 10 entries against a library of hundreds, a work came back after
         * roughly ten swipes. Exhaustion keeps this from starving a smaller shelf.
         */
        const val MAX_ENTRIES = 50
        const val MANGA_ID = "mangaId"
        const val AT = "at"
    }
}
