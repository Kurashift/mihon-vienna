package eu.kanade.tachiyomi.data.audio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.Serializable as JavaSerializable

/**
 * DTOs for the kikoeru / asmr.one-style backend API used by the audio module.
 * All fields are optional with sensible defaults so the response can evolve
 * without breaking decoding.
 *
 * A few of these are held by Voyager [Screen]s (e.g. [Work] by the detail screen), which get
 * saved into a Bundle when the activity stops. They therefore also implement
 * [java.io.Serializable] so Android can parcel them, not just kotlinx.serialization.
 */
@Serializable
data class WorksResponse(
    val works: List<Work> = emptyList(),
    val pagination: Pagination = Pagination(),
)

@Serializable
data class Pagination(
    val currentPage: Int = 0,
    val pageSize: Int = 0,
    val totalCount: Int = 0,
)

@Serializable
data class Work(
    val id: Long = 0,
    val title: String = "",
    /** Circle name, exposed at the top level by the API. */
    val name: String = "",
    val release: String? = null,
    val duration: Double? = null,
    @SerialName("rate_average_2dp") val rateAverage2dp: Double? = null,
    @SerialName("rate_count") val rateCount: Int = 0,
    @SerialName("dl_count") val dlCount: Int = 0,
    val mainCoverUrl: String? = null,
    val thumbnailCoverUrl: String? = null,
    val samCoverUrl: String? = null,
    val tags: List<TagRef> = emptyList(),
    val vas: List<VaRef> = emptyList(),
    /** Account-level listening state returned by /api/review for authenticated users. */
    val progress: String? = null,
    val userRating: Double? = null,
) : JavaSerializable

/** Login/register response body. */
@Serializable
data class AuthResponse(
    val user: AuthUser = AuthUser(),
    val token: String = "",
) : JavaSerializable

@Serializable
data class AuthUser(
    val loggedIn: Boolean = false,
    val name: String = "",
    val group: String = "",
    val email: String? = null,
    val recommenderUuid: String? = null,
) : JavaSerializable

@Serializable
data class TagRef(
    val name: String = "",
) : JavaSerializable

@Serializable
data class VaRef(
    val name: String = "",
) : JavaSerializable

@Serializable
data class TagItem(
    val id: Long = 0,
    val name: String = "",
    val count: Int = 0,
) : JavaSerializable

@Serializable
data class VaItem(
    val id: String = "",
    val name: String = "",
    val count: Int = 0,
) : JavaSerializable

@Serializable
data class CircleItem(
    val id: Long = 0,
    val name: String = "",
    val count: Int = 0,
) : JavaSerializable

@Serializable
data class TrackNode(
    /** One of "folder", "audio" or "text". */
    val type: String = "",
    val title: String = "",
    val children: List<TrackNode> = emptyList(),
    val mediaStreamUrl: String? = null,
    val mediaDownloadUrl: String? = null,
    val streamLowQualityUrl: String? = null,
    val duration: Double? = null,
    val size: Long? = null,
) : JavaSerializable
