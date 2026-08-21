package eu.kanade.tachiyomi.data.manga

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.Database
import tachiyomi.data.subscribeToOne

data class MangaMark(
    val mangaId: Long,
    val mangaTitle: String,
    val chapterId: Long,
    val chapterName: String,
    val markedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mangaId", mangaId)
        put("mangaTitle", mangaTitle)
        put("chapterId", chapterId)
        put("chapterName", chapterName)
        put("markedAt", markedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): MangaMark? = runCatching {
            MangaMark(
                mangaId = json.getLong("mangaId"),
                mangaTitle = json.getString("mangaTitle"),
                chapterId = json.getLong("chapterId"),
                chapterName = json.getString("chapterName"),
                markedAt = json.getLong("markedAt"),
            )
        }.getOrNull()
    }
}

interface ChapterFlagStore {
    val marks: StateFlow<List<MangaMark>>

    suspend fun toggle(mark: MangaMark): Boolean
    suspend fun add(mark: MangaMark)
    suspend fun remove(mark: MangaMark)
    suspend fun setAll(marks: List<MangaMark>, marked: Boolean)
    suspend fun clearManga(mangaId: Long)
    suspend fun clearMangas(mangaIds: Set<Long>)
    suspend fun clear()
}

abstract class PreferenceChapterFlagStore(
    private val storePreference: Preference<String>,
) : ChapterFlagStore {
    private val _marks = MutableStateFlow(loadMarks(storePreference.get()))
    override val marks: StateFlow<List<MangaMark>> = _marks.asStateFlow()

    override suspend fun toggle(mark: MangaMark): Boolean {
        val added = _marks.value.none { it.chapterId == mark.chapterId }
        setAll(listOf(mark), added)
        return added
    }

    override suspend fun add(mark: MangaMark) = setAll(listOf(mark), true)

    override suspend fun remove(mark: MangaMark) = setAll(listOf(mark), false)

    override suspend fun setAll(marks: List<MangaMark>, marked: Boolean) {
        if (marks.isEmpty()) return
        val chapterIds = marks.mapTo(HashSet()) { it.chapterId }
        val current = _marks.value.filterNot { it.chapterId in chapterIds }.toMutableList()
        if (marked) current += marks
        publish(current)
    }

    override suspend fun clearManga(mangaId: Long) = clearMangas(setOf(mangaId))

    override suspend fun clearMangas(mangaIds: Set<Long>) {
        if (mangaIds.isEmpty()) return
        publish(_marks.value.filterNot { it.mangaId in mangaIds })
    }

    override suspend fun clear() = publish(emptyList())

    private fun publish(current: List<MangaMark>) {
        _marks.value = current
        storePreference.set(saveMarks(current))
    }
}

class MangaMarkStore(
    preferences: eu.kanade.domain.base.BasePreferences,
) : PreferenceChapterFlagStore(preferences.markedChapters) {

    suspend fun relocate(chapterId: Long, mangaId: Long, mangaTitle: String) {
        val existing = marks.value.firstOrNull { it.chapterId == chapterId } ?: return
        add(
            existing.copy(
                mangaId = mangaId,
                mangaTitle = mangaTitle,
            ),
        )
    }

    suspend fun merge(
        chapterId: Long,
        duplicateChapterId: Long,
        mangaId: Long,
        mangaTitle: String,
    ) {
        val existing = marks.value.firstOrNull { it.chapterId == chapterId }
            ?: marks.value.firstOrNull { it.chapterId == duplicateChapterId }
            ?: return
        remove(existing.copy(chapterId = duplicateChapterId))
        add(
            existing.copy(
                chapterId = chapterId,
                mangaId = mangaId,
                mangaTitle = mangaTitle,
            ),
        )
    }
}

/** Database-backed good-doujin state. The old JSON preference is imported once on startup. */
class GoodDoujinStore(
    private val database: Database,
    private val legacyPreference: Preference<String>,
) : ChapterFlagStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val _marks = MutableStateFlow<List<MangaMark>>(emptyList())
    override val marks: StateFlow<List<MangaMark>> = _marks.asStateFlow()

    init {
        scope.launch {
            migrateLegacyMarks()
            database.good_doujinsQueries.removeNonLocal()
            database.good_doujinsQueries
                .watchChanges()
                .subscribeToOne(Dispatchers.IO)
                .collectLatest { refreshMarks() }
        }
    }

    override suspend fun toggle(mark: MangaMark): Boolean = writeMutex.withLock {
        val added = _marks.value.none { it.chapterId == mark.chapterId }
        val result = if (added) {
            database.good_doujinsQueries.upsert(mark.chapterId, mark.mangaId, mark.markedAt) > 0L
        } else {
            removeByChapterIds(setOf(mark.chapterId))
            false
        }
        refreshMarks()
        result
    }

    override suspend fun add(mark: MangaMark) = setAll(listOf(mark), true)

    override suspend fun remove(mark: MangaMark) = setAll(listOf(mark), false)

    override suspend fun setAll(marks: List<MangaMark>, marked: Boolean) {
        if (marks.isEmpty()) return
        writeMutex.withLock {
            if (marked) {
                database.transaction {
                    marks.forEach { mark ->
                        database.good_doujinsQueries.upsert(mark.chapterId, mark.mangaId, mark.markedAt)
                    }
                }
            } else {
                removeByChapterIds(marks.mapTo(HashSet()) { it.chapterId })
            }
            refreshMarks()
        }
    }

    override suspend fun clearManga(mangaId: Long) {
        writeMutex.withLock {
            database.good_doujinsQueries.clearManga(mangaId)
            refreshMarks()
        }
    }

    override suspend fun clearMangas(mangaIds: Set<Long>) {
        if (mangaIds.isEmpty()) return
        writeMutex.withLock {
            database.good_doujinsQueries.clearMangas(mangaIds)
            refreshMarks()
        }
    }

    override suspend fun clear() {
        writeMutex.withLock {
            database.good_doujinsQueries.clear()
            refreshMarks()
        }
    }

    private suspend fun migrateLegacyMarks() = writeMutex.withLock {
        val legacy = loadMarks(legacyPreference.get())
        if (legacy.isNotEmpty()) {
            database.transaction {
                legacy.forEach { mark ->
                    database.good_doujinsQueries.upsert(mark.chapterId, mark.mangaId, mark.markedAt)
                }
            }
        }
        legacyPreference.delete()
    }

    private suspend fun removeByChapterIds(chapterIds: Set<Long>) {
        database.good_doujinsQueries.removeMany(chapterIds)
    }

    private suspend fun refreshMarks() {
        _marks.value = database.good_doujinsQueries
            .getAll(::mapGoodDoujin)
            .awaitAsList()
    }

    private fun mapGoodDoujin(
        manga_id: Long,
        manga_title: String,
        chapter_id: Long,
        chapter_name: String,
        marked_at: Long,
    ) = MangaMark(manga_id, manga_title, chapter_id, chapter_name, marked_at)
}

private fun loadMarks(raw: String): List<MangaMark> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let(MangaMark::fromJson)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())
}

private fun saveMarks(marks: List<MangaMark>): String = JSONArray().apply {
    marks.forEach { put(it.toJson()) }
}.toString()
