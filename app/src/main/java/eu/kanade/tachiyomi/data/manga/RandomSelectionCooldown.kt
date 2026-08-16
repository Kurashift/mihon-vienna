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

        val entries = activeEntries()
        val cooledMangaIds = entries.mapTo(mutableSetOf()) { it.mangaId }
        val available = pool.filterNot(cooledMangaIds::contains)
        val resolvedPool = when {
            available.isNotEmpty() -> available
            entries.isNotEmpty() -> {
                writeEntries(emptyList())
                pool
            }
            else -> pool
        }
        val selected = resolvedPool[randomIndex(resolvedPool.size)]
        remember(Entry(selected, chapterId = null, at = now()))
        return selected
    }

    @Synchronized
    fun <T> eligibleChapters(
        candidates: Collection<T>,
        releaseOnExhaustion: Boolean,
        mangaId: (T) -> Long,
        chapterId: (T) -> Long,
    ): List<T> {
        val pool = candidates.distinctBy { mangaId(it) to chapterId(it) }
        if (pool.isEmpty()) return emptyList()

        val entries = activeEntries()
        val cooledChapters = entries
            .mapNotNull { entry -> entry.chapterId?.let { entry.mangaId to it } }
            .toSet()
        val available = pool.filterNot { mangaId(it) to chapterId(it) in cooledChapters }
        return when {
            available.isNotEmpty() -> available
            releaseOnExhaustion && entries.isNotEmpty() -> {
                writeEntries(emptyList())
                pool
            }
            else -> emptyList()
        }
    }

    @Synchronized
    fun rememberChapter(mangaId: Long, chapterId: Long) {
        if (mangaId <= 0 || chapterId <= 0) return
        remember(Entry(mangaId, chapterId, now()))
    }

    @Synchronized
    fun isChapterCoolingDown(mangaId: Long, chapterId: Long): Boolean {
        return activeEntries().any { it.mangaId == mangaId && it.chapterId == chapterId }
    }

    private fun remember(entry: Entry) {
        val entries = activeEntries()
            .filterNot { it.mangaId == entry.mangaId && it.chapterId == entry.chapterId }
            .plus(entry)
            .takeLast(MAX_ENTRIES)
        writeEntries(entries)
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
                    val chapterId = item[CHAPTER_ID]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
                    val at = item[AT]?.jsonPrimitive?.longOrNull ?: -1L
                    Entry(mangaId, chapterId, at).takeIf { mangaId > 0 && at >= 0 }
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
                            entry.chapterId?.let { put(CHAPTER_ID, it) }
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
        val chapterId: Long?,
        val at: Long,
    )

    private companion object {
        const val WINDOW_MILLIS = 60 * 60 * 1000L
        const val MAX_ENTRIES = 10
        const val MANGA_ID = "mangaId"
        const val CHAPTER_ID = "chapterId"
        const val AT = "at"
    }
}
