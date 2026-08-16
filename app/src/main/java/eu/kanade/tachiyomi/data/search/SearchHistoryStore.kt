package eu.kanade.tachiyomi.data.search

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class SearchHistoryStore(
    private val preferenceStore: PreferenceStore,
    private val json: Json,
) {

    private val preferences = mutableMapOf<String, Preference<String>>()

    fun observe(scope: String): Flow<List<String>> = preference(scope).changes().map(::decode)

    fun get(scope: String): List<String> = load(scope)

    fun add(scope: String, query: String) {
        val normalized = query.trim().take(MAX_QUERY_LENGTH)
        if (normalized.isEmpty()) return

        val updated = load(scope)
            .filterNot { it.equals(normalized, ignoreCase = true) }
            .let { listOf(normalized) + it }
            .take(MAX_ENTRIES)
        save(scope, updated)
    }

    fun remove(scope: String, query: String) {
        save(scope, load(scope).filterNot { it == query })
    }

    fun clear(scope: String) {
        preference(scope).delete()
    }

    private fun load(scope: String): List<String> = decode(preference(scope).get())

    private fun save(scope: String, entries: List<String>) {
        preference(scope).set(json.encodeToString(entries))
    }

    private fun decode(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }
            .getOrDefault(emptyList())
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
            .take(MAX_ENTRIES)
    }

    private fun preference(scope: String): Preference<String> {
        return preferences.getOrPut(scope) {
            preferenceStore.getString(Preference.appStateKey("search_history_$scope"), "")
        }
    }

    private companion object {
        const val MAX_ENTRIES = 12
        const val MAX_QUERY_LENGTH = 160
    }
}

object SearchHistoryScope {
    const val LIBRARY = "library"
    const val READING_HISTORY = "reading_history"
    const val GLOBAL = "global"
    const val MIGRATION_GLOBAL = "migration_global"
    const val EXTENSIONS = "extensions"
    const val AUDIO = "audio"
    const val SETTINGS = "settings"

    fun source(sourceId: Long): String = "source_$sourceId"

    fun migrationSource(sourceId: Long): String = "migration_source_$sourceId"

    fun audioCategory(category: String): String = "audio_category_${category.lowercase()}"

    fun tracker(serviceId: Long): String = "tracker_$serviceId"
}
