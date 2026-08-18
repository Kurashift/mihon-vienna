package eu.kanade.tachiyomi.data.audio

import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import java.io.IOException
import java.net.URLEncoder

/**
 * Thin client for the fixed kikoeru / asmr.one-style backend used by the audio section.
 */
class KikoeruApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val basePreferences: BasePreferences,
) {

    suspend fun fetchWorks(
        page: Int,
        pageSize: Int,
        order: String = "release",
        sort: String = "desc",
    ): WorksResponse = withIOContext {
        val url = "$BASE_URL/api/works?page=$page&pageSize=$pageSize&order=$order&sort=$sort"
        with(json) {
            executeWithRetry { client.newCall(authenticated(GET(url))).awaitSuccess().use { it.parseAs() } }
        }
    }

    suspend fun search(
        keyword: String,
        page: Int,
        pageSize: Int,
        order: String = "release",
        sort: String = "desc",
    ): WorksResponse = withIOContext {
        // Keyword goes in the URL path (the backend only parses the advanced
        // "$tag:xxx$" filter syntax there, not via a query parameter).
        val encoded = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        val url = "$BASE_URL/api/search/$encoded?page=$page&pageSize=$pageSize&order=$order&sort=$sort"
        with(json) {
            executeWithRetry {
                client.newCall(authenticated(GET(url, cache = CacheControl.FORCE_NETWORK)))
                    .awaitSuccess()
                    .use { it.parseAs() }
            }
        }
    }

    suspend fun fetchTracks(
        workId: Long,
    ): List<TrackNode> = withIOContext {
        val url = "$BASE_URL/api/tracks/$workId"
        with(json) {
            executeWithRetry { client.newCall(authenticated(GET(url))).awaitSuccess().use { it.parseAs() } }
        }
    }

    suspend fun fetchTags(): List<TagItem> = withIOContext {
        val url = "$BASE_URL/api/tags/"
        with(json) {
            executeWithRetry { client.newCall(authenticated(GET(url))).awaitSuccess().use { it.parseAs() } }
        }
    }

    suspend fun fetchVas(): List<VaItem> = withIOContext {
        val url = "$BASE_URL/api/vas/"
        with(json) {
            executeWithRetry { client.newCall(authenticated(GET(url))).awaitSuccess().use { it.parseAs() } }
        }
    }

    suspend fun fetchCircles(): List<CircleItem> = withIOContext {
        val url = "$BASE_URL/api/circles/"
        with(json) {
            executeWithRetry { client.newCall(authenticated(GET(url))).awaitSuccess().use { it.parseAs() } }
        }
    }

    /** Logs in and returns the JWT token + user profile. */
    suspend fun login(name: String, password: String): AuthResponse = withIOContext {
        val url = "$BASE_URL/api/auth/me"
        val body = buildJsonObject {
            put("name", name)
            put("password", password)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        with(json) {
            executeWithRetry { client.newCall(POST(url, body = body)).awaitSuccess().use { it.parseAs() } }
        }
    }

    /** Popular works (backend recommender), ordered by rank signals. */
    suspend fun fetchPopular(page: Int, pageSize: Int): WorksResponse = withIOContext {
        val url = "$BASE_URL/api/recommender/popular"
        val body = buildJsonObject {
            put("keyword", "")
            put("page", page)
            put("pageSize", pageSize)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        with(json) {
            executeWithRetry { client.newCall(authenticated(POST(url, body = body))).awaitSuccess().use { it.parseAs() } }
        }
    }

    /** Personalized recommendations keyed by an anonymous UUID. */
    suspend fun fetchRecommended(recommenderUuid: String, page: Int, pageSize: Int): WorksResponse = withIOContext {
        val url = "$BASE_URL/api/recommender/recommend-for-user"
        val body = buildJsonObject {
            put("keyword", "")
            put("recommenderUuid", recommenderUuid)
            put("page", page)
            put("pageSize", pageSize)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        with(json) {
            executeWithRetry { client.newCall(authenticated(POST(url, body = body))).awaitSuccess().use { it.parseAs() } }
        }
    }

    /** Account collection/listening states shown by the website's "Favourites" section. */
    suspend fun fetchAccountWorks(progress: AudioAccountProgress, page: Int): WorksResponse = withIOContext {
        val encodedProgress = URLEncoder.encode(progress.wireValue, "UTF-8")
        val url = "$BASE_URL/api/review?order=updated_at&sort=desc&page=$page&filter=$encodedProgress"
        with(json) {
            executeWithRetry {
                client.newCall(authenticated(GET(url, cache = CacheControl.FORCE_NETWORK)))
                    .awaitSuccess()
                    .use { it.parseAs() }
            }
        }
    }

    /** Updates only the account-level listening state, preserving rating and review text. */
    suspend fun updateAccountProgress(workId: Long, progress: AudioAccountProgress?) = withIOContext {
        val url = "$BASE_URL/api/review"
        val body = buildAccountProgressBody(workId, progress).toRequestBody(JSON_MEDIA_TYPE)
        executeWithRetry {
            client.newCall(authenticated(PUT(url, body = body))).awaitSuccess().close()
        }
    }

    /** Downloads raw subtitle text (.lrc / .vtt / .srt / .ass) from a track download URL. */
    suspend fun fetchSubtitle(url: String, fallbackUrl: String? = null): String = withIOContext {
        var lastError: Exception? = null
        subtitleUrls(url, fallbackUrl).forEach { candidate ->
            try {
                return@withIOContext executeWithRetry {
                    val request = GET(candidate, cache = CacheControl.FORCE_NETWORK).let { request ->
                        if (candidate.startsWith(BASE_URL, ignoreCase = true)) authenticated(request) else request
                    }
                    client.newCall(request).awaitSuccess().use { response ->
                        response.body.string()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("subtitle request failed")
    }

    private fun authenticated(request: Request): Request {
        return request.withAudioAuthorization(basePreferences.audioAuthToken.get())
    }

    /**
     * Retries transient failures (server 5xx, connection reset) a couple of times with a short
     * backoff. The backend sits behind Cloudflare and occasionally returns 503 under load, which
     * previously surfaced as an instant error.
     */
    private suspend fun <T> executeWithRetry(block: suspend () -> T): T {
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is HttpException && e.code == 401) {
                    basePreferences.audioAuthToken.set("")
                    basePreferences.audioUsername.set("")
                }
                if (e is HttpException && e.code in 400..499) throw e
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(RETRY_BACKOFF_MILLIS shl attempt)
                }
            }
        }
        throw lastError ?: IOException("request failed")
    }

    private companion object {
        const val BASE_URL = "https://api.asmr-200.com"
        const val MAX_ATTEMPTS = 3
        const val RETRY_BACKOFF_MILLIS = 400L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

internal fun subtitleUrls(url: String, fallbackUrl: String? = null): List<String> {
    fun candidatesFor(value: String): List<String> {
        val streamingUrl = value.replace("/media/download/", "/media/stream/", ignoreCase = true)
        return listOf(streamingUrl, value).distinct()
    }

    return (candidatesFor(url) + fallbackUrl.orEmpty().takeIf { it.isNotBlank() }?.let(::candidatesFor).orEmpty())
        .distinct()
}

internal fun buildAccountProgressBody(workId: Long, progress: AudioAccountProgress?): String {
    return buildJsonObject {
        put("work_id", workId)
        if (progress == null) {
            put("progress", JsonNull)
        } else {
            put("progress", progress.wireValue)
        }
    }.toString()
}

internal fun Request.withAudioAuthorization(token: String): Request {
    if (token.isBlank()) return this
    return newBuilder()
        .header("Authorization", "Bearer $token")
        .build()
}
