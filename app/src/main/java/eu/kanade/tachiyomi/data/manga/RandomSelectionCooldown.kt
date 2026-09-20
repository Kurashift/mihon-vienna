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
        // A hop needs at least two candidates to be a choice: `pickManga` draws its index against
        // the size of what comes back, so a single-member pool makes it deterministic. A pool of
        // one is the floor this cannot improve on, and hands back what it has.
        if (available.size >= TWO_CHOICES.coerceAtMost(pool.size)) return available

        // Exhaustion: so much of the pool is cooling that there is nothing left to choose from.
        // Hold back only the newest entry *from this pool* and start a fresh round, which leaves
        // every other work drawable again.
        //
        // Keeping pool.size - 1 of them cooling instead - and only reacting once the pool ran
        // completely dry - was worse in a way that showed: the window grew until exactly one work
        // was still drawable, and `available.size == 1` is not "a random pick out of one" but a
        // constant index. Every hop then came out of that single survivor, so a reader saw the same
        // work return every other hop in a fixed rotation. A pool of two is the one case that
        // cannot be helped - a hop has to be followed by something.
        //
        // Letting the rest back in does not let the work just left return. That is the callers'
        // job already - `pickManga` drops `currentMangaId`, and `eligibleChapters` narrows to
        // `others` - so the one hop that has to stay away is away without this window's help.
        //
        // Narrowed to this pool because the window is shared: the random entry points draw from
        // different lists, so its newest entry may belong to one this pick is not about.
        val poolIds = pool.mapTo(mutableSetOf(), mangaId)
        val heldBack = entries.lastOrNull { it.mangaId in poolIds }?.mangaId ?: return pool
        writeEntries(entries.filterNot { it.mangaId in poolIds && it.mangaId != heldBack })
        return pool.filterNot { mangaId(it) == heldBack }.ifEmpty { pool }
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
         * roughly ten swipes. A pool larger than this can never run dry, so it keeps a full window;
         * a smaller one cycles in rounds instead (see [resolvePool]).
         */
        const val MAX_ENTRIES = 50

        /**
         * How many works have to stay drawable for a hop to be a pick rather than a foregone
         * conclusion. A pool smaller than this cannot reach it, and is drawn from what it has.
         */
        const val TWO_CHOICES = 2
        const val MANGA_ID = "mangaId"
        const val AT = "at"
    }
}
