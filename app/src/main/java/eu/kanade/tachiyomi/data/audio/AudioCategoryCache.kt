package eu.kanade.tachiyomi.data.audio

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * On-disk store for the three category dictionaries (circles / VAs / tags).
 *
 * These are huge (the tag list alone is tens of thousands of rows), change at most a few times a
 * month, and are the entire content of the category screen — worth persisting even though every
 * other piece of audio metadata is deliberately transient. Lists are stored pre-sorted by count so
 * a cache hit costs one decode instead of a decode plus three sorts.
 */
class AudioCategoryCache(
    context: Context,
    private val json: Json,
) {

    private val file = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    @Synchronized
    fun read(): AudioCategorySnapshot? {
        return runCatching {
            if (!file.exists()) return@runCatching null
            json.decodeFromString<AudioCategorySnapshot>(file.readText())
        }.getOrNull()
    }

    /**
     * Writes the given dictionaries, keeping whatever the previous snapshot held for the fields
     * that were not passed. The category screen fetches its dictionaries on demand, one tab at a
     * time, so a single write must never wipe the fields that are still waiting on the network.
     *
     * Each written field records its own fetch time, so a stale VA list is not "refreshed" just
     * because the circles tab was pulled in and the shared snapshot timestamp moved forward.
     */
    @Synchronized
    fun write(
        circles: List<CircleItem>? = null,
        vas: List<VaItem>? = null,
        tags: List<TagItem>? = null,
    ) {
        val previous = read()
        val now = System.currentTimeMillis()
        val snapshot = AudioCategorySnapshot(
            savedAt = now,
            circlesSavedAt = if (circles != null) now else previous?.circlesSavedAt ?: 0L,
            vasSavedAt = if (vas != null) now else previous?.vasSavedAt ?: 0L,
            tagsSavedAt = if (tags != null) now else previous?.tagsSavedAt ?: 0L,
            circles = circles ?: previous?.circles.orEmpty(),
            vas = vas ?: previous?.vas.orEmpty(),
            tags = tags ?: previous?.tags.orEmpty(),
        )
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(snapshot))
            // rename is atomic within a directory, so being killed mid-write leaves the previous
            // snapshot intact instead of a truncated file that fails to decode.
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    /** True when the given dictionary was fetched recently enough to trust without a refresh. */
    fun isFieldFresh(snapshot: AudioCategorySnapshot, field: AudioCategoryField): Boolean {
        val savedAt = snapshot.savedAtFor(field)
        return System.currentTimeMillis() - savedAt < MAX_AGE.inWholeMilliseconds
    }

    companion object {
        private const val DIR_NAME = "audio"
        private const val FILE_NAME = "categories.json"
        val MAX_AGE: Duration = 7.days
    }
}

/** Per-field fetch times, which are what staleness is decided from. */
@Serializable
data class AudioCategorySnapshot(
    // Timestamp of the last write. Kept only so files stay readable by older builds; freshness is
    // always decided per field by savedAtFor, never by this value.
    val savedAt: Long = 0L,
    val circlesSavedAt: Long = 0L,
    val vasSavedAt: Long = 0L,
    val tagsSavedAt: Long = 0L,
    val circles: List<CircleItem> = emptyList(),
    val vas: List<VaItem> = emptyList(),
    val tags: List<TagItem> = emptyList(),
) {
    fun savedAtFor(field: AudioCategoryField): Long = when (field) {
        AudioCategoryField.CIRCLE -> circlesSavedAt
        AudioCategoryField.VA -> vasSavedAt
        AudioCategoryField.TAG -> tagsSavedAt
    }
}
