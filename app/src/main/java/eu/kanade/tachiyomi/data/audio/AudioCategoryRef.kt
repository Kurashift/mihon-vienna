package eu.kanade.tachiyomi.data.audio

import kotlinx.serialization.Serializable
import java.io.Serializable as JavaSerializable

/**
 * The three dictionary-backed ways the backend can narrow a work list.
 *
 * @property pathSegment The collection segment used by `/api/{pathSegment}/{id}/works`.
 * @property legacyPrefix The singular name used by the legacy `$circle:Name$` search syntax.
 */
@Serializable
enum class AudioCategoryField(val pathSegment: String, private val legacyPrefix: String) {
    CIRCLE("circles", "circle"),
    VA("vas", "va"),
    TAG("tags", "tag"),
    ;

    /**
     * The old `$prefix:Name$` search keyword. Only used as a fallback by the detail page when a
     * dictionary id cannot be resolved; the id endpoints are always preferred.
     */
    fun legacyKeyword(name: String): String = "\$$legacyPrefix:$name\$"
}

/**
 * One entry of a category dictionary, addressed by id rather than by name.
 *
 * Category results used to be opened with a `$circle:Name$` search keyword, which sent a display
 * name through the URL path for the backend to re-parse. `/api/{field}s/{id}/works` resolves the
 * same filter in SQL from the id: measurably faster, immune to names containing `$`, spaces or
 * punctuation, and cacheable because the request does not carry `CacheControl.FORCE_NETWORK`.
 *
 * @property title The display name, kept only so the results page can label itself.
 *
 * Held by [eu.kanade.tachiyomi.ui.audio.AudioBrowseScreen], which is written into a Bundle when
 * the activity stops, so it also has to be [java.io.Serializable] and not only
 * kotlinx-serializable: without it Android throws
 * `BadParcelableException ... NotSerializableException` as soon as the browse page is on the stack.
 */
@Serializable
data class AudioCategoryRef(
    val field: AudioCategoryField,
    val id: String,
    val title: String,
) : JavaSerializable
