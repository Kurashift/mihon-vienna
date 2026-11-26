package tachiyomi.source.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Unified reader/writer for `local_source_chapter_names_index.json` and atomic index writes.
 * Replaces four separate JSON parsers previously duplicated inside [LocalSource].
 */
internal class LocalChapterNamesIndexStore(
    private val fileProvider: () -> File,
    private val version: Int,
) {

    data class Snapshot(
        val baseDirLastModified: Long,
        val builtAt: Long,
        val manga: Map<String, List<String>>,
    )

    fun readSnapshot(expectedBaseUri: String?): Snapshot? {
        val file = fileProvider()
        if (!file.exists()) return null
        return try {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
            val storedVersion = (root["version"] as? JsonPrimitive)?.intOrNull ?: -1
            if (storedVersion != version) return null
            val storedBaseUri = (root["baseUri"] as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf(String::isNotEmpty)
            if (storedBaseUri != expectedBaseUri) return null
            val mangaObject = root["manga"] as? JsonObject ?: return null
            val map = buildMap {
                mangaObject.forEach { (url, element) ->
                    val array = element as? JsonArray ?: return@forEach
                    val names = buildList {
                        array.forEach { item ->
                            (item as? JsonPrimitive)
                                ?.contentOrNull
                                ?.takeIf(String::isNotEmpty)
                                ?.let(::add)
                        }
                    }
                    if (names.isNotEmpty() || array.isEmpty()) put(url, names)
                }
            }
            Snapshot(
                baseDirLastModified = (root["baseDirLastModified"] as? JsonPrimitive)?.longOrNull ?: -1L,
                builtAt = (root["builtAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                manga = map,
            )
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to load local source chapter names index" }
            null
        }
    }

    fun readFresh(
        expectedBaseUri: String?,
        expectedBaseDirLastModified: Long,
        now: Long,
        maxAgeMillis: Long,
    ): Map<String, List<String>>? {
        if (expectedBaseDirLastModified < 0) return null
        val snapshot = readSnapshot(expectedBaseUri) ?: return null
        if (snapshot.baseDirLastModified != expectedBaseDirLastModified) return null
        if (now - snapshot.builtAt !in 0 until maxAgeMillis) return null
        return snapshot.manga
    }

    fun save(
        index: Map<String, List<String>>,
        baseUri: String?,
        baseDirLastModified: Long,
        now: Long,
    ) {
        try {
            val root = buildJsonObject {
                put("version", version)
                put("baseUri", baseUri.orEmpty())
                put("baseDirLastModified", baseDirLastModified)
                put("builtAt", now)
                putJsonObject("manga") {
                    index.forEach { (url, names) ->
                        putJsonArray(url) {
                            names.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                }
            }

            writeLocalIndexAtomically(fileProvider(), root.toString())
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to save local source chapter names index" }
        }
    }
}

internal fun writeLocalIndexAtomically(target: File, content: String) {
    val tempFile = File(target.parentFile, "${target.name}.tmp")
    tempFile.writeText(content)
    try {
        try {
            Files.move(
                tempFile.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(
                tempFile.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } finally {
        if (tempFile.exists()) tempFile.delete()
    }
}
