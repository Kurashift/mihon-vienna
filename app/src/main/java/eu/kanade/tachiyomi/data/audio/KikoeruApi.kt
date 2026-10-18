package eu.kanade.tachiyomi.data.audio

import android.os.SystemClock
import android.util.Log
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.net.URLEncoder

/**
 * Thin client for the fixed kikoeru / asmr.one-style backend used by the audio section.
 */
class KikoeruApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val basePreferences: BasePreferences,
    private val trackCache: AudioTrackCache = AudioTrackCache(),
) {

    /**
     * The site the host picker currently has selected.
     *
     * Read per request rather than captured in the constructor: switching hosts has to take effect
     * on the next call without rebuilding the client, the account sync or the page cache, all of
     * which hold this instance. An empty preference means nobody has picked one yet, which is the
     * host the section shipped with.
     */
    private val baseUrl: String
        get() = basePreferences.audioBackendUrl.get().ifBlank { AudioBackend.DEFAULT.apiBaseUrl }

    /**
     * Round-trips the cheapest endpoint a mirror serves, for the host picker's latency column.
     *
     * Two attempts, cheapest wins: the first request to a host pays for the TLS handshake, so a
     * single sample measures DNS and a key exchange more than it measures the route. Null means the
     * host did not answer at all, which the picker shows as unreachable rather than as a slow one.
     */
    suspend fun measureLatency(apiBaseUrl: String): Long? = withIOContext {
        var best: Long? = null
        repeat(LATENCY_SAMPLES) {
            val started = SystemClock.elapsedRealtime()
            val answered = runCatching {
                client.newCall(GET("$apiBaseUrl/api/health", cache = CacheControl.FORCE_NETWORK))
                    .execute()
                    .use { it.isSuccessful }
            }.getOrDefault(false)
            if (!answered) return@repeat
            val elapsed = SystemClock.elapsedRealtime() - started
            best = best?.coerceAtMost(elapsed) ?: elapsed
        }
        best
    }

    /**
     * @param seed Seed of the random draw, sent as `seed=` when [order] is `random`.
     *
     * Measured against the live API: `order=random` without a seed answers the *same* page on every
     * request — three calls in a row returned byte-identical JSON, and a `Cache-Control: no-cache`
     * from the client changes nothing because the copy is held upstream of us. Only a new seed
     * reshuffles it, and the same seed on page two continues the draw of page one instead of
     * starting an unrelated one.
     */
    suspend fun fetchWorks(
        page: Int,
        pageSize: Int,
        order: String = "release",
        sort: String = "desc",
        cache: CacheControl? = null,
        seed: Long? = null,
        subtitlesOnly: Boolean = false,
    ): WorksResponse = withIOContext {
        val url = "$baseUrl/api/works?page=$page&pageSize=$pageSize&order=$order&sort=$sort" +
            seedParam(seed) + subtitleParam(subtitlesOnly)
        with(json) {
            executeWithRetry(url) { client.newCall(authenticated(get(url, cache))).awaitSuccess().use { it.parseAs() } }
        }
    }

    /**
     * Works belonging to one category entry, filtered by its id.
     *
     * Preferred over [search] with a `$circle:Name$` keyword. Measured against the live backend the
     * id endpoints answer in roughly half the time, and they honour `order`/`sort` the same way
     * [fetchWorks] does. They are also requested without `CacheControl.FORCE_NETWORK` unless a
     * caller passes one, so the network interceptor gives them a `max-age` and repeat visits are
     * served from disk.
     *
     * @param seed Seed of the random draw, same meaning as in [fetchWorks]: the id endpoints reshuffle
     * on a new seed and on nothing else.
     */
    suspend fun fetchCategoryWorks(
        field: AudioCategoryField,
        id: String,
        page: Int,
        pageSize: Int,
        order: String = "release",
        sort: String = "desc",
        cache: CacheControl? = null,
        seed: Long? = null,
        subtitlesOnly: Boolean = false,
    ): WorksResponse = withIOContext {
        val encodedId = URLEncoder.encode(id, "UTF-8")
        val url = "$baseUrl/api/${field.pathSegment}/$encodedId/works" +
            "?page=$page&pageSize=$pageSize&order=$order&sort=$sort" + seedParam(seed) +
            subtitleParam(subtitlesOnly)
        with(json) {
            executeWithRetry(url) { client.newCall(authenticated(get(url, cache))).awaitSuccess().use { it.parseAs() } }
        }
    }

    suspend fun search(
        keyword: String,
        page: Int,
        pageSize: Int,
        order: String = "release",
        sort: String = "desc",
        seed: Long? = null,
        subtitlesOnly: Boolean = false,
    ): WorksResponse = withIOContext {
        // Keyword goes in the URL path (the backend only parses the advanced
        // "$tag:xxx$" filter syntax there, not via a query parameter).
        val encoded = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        val url = "$baseUrl/api/search/$encoded?page=$page&pageSize=$pageSize&order=$order&sort=$sort" +
            seedParam(seed) + subtitleParam(subtitlesOnly)
        with(json) {
            executeWithRetry(url) {
                client.newCall(authenticated(GET(url, cache = CacheControl.FORCE_NETWORK)))
                    .awaitSuccess()
                    .use { it.parseAs() }
            }
        }
    }

    /**
     * One work's own metadata. The work feeds attach tags and VAs to every row, but the playback
     * paths only know a work as a bare snapshot (id, title, circle, cover), so a details page
     * opened from one of those used to show up without its tag chips. Served through the same
     * default disk cache as the other GETs, so flipping back to a recently visited page costs
     * no round trip.
     */
    suspend fun fetchWork(workId: Long): Work = withIOContext {
        val url = "$baseUrl/api/work/$workId"
        with(json) {
            executeWithRetry(url) { client.newCall(authenticated(get(url))).awaitSuccess().use { it.parseAs() } }
        }
    }

    /**
     * The tree of files making up a work.
     *
     * Served from [trackCache] when it was asked for recently, so the player's resolve step does not
     * put a round trip in front of the first note. [refresh] skips that copy: it is what a retry
     * after a broken stream needs, because the tree is then the prime suspect.
     */
    suspend fun fetchTracks(
        workId: Long,
        refresh: Boolean = false,
    ): List<TrackNode> = withIOContext {
        if (!refresh) trackCache.get(workId)?.let { return@withIOContext it }
        val url = "$baseUrl/api/tracks/$workId"
        val nodes: List<TrackNode> = with(json) {
            executeWithRetry(url) { client.newCall(authenticated(GET(url))).awaitSuccess().use { it.parseAs() } }
        }
        trackCache.put(workId, nodes)
        nodes
    }

    /**
     * Forgets the cached tree of [workId], so the next [fetchTracks] goes to the backend.
     *
     * True when there was a cached tree to drop. False when there was none, which is the answer the
     * caller needs: a stream that failed despite coming from a tree fetched moments ago is not
     * going to be fixed by asking for that tree again.
     */
    fun invalidateTracks(workId: Long): Boolean = trackCache.remove(workId)

    // The three dictionaries below are large and slow, and every one of them now has a persisted
    // copy to fall back on, so they give up sooner than the work feeds do.
    suspend fun fetchTags(): List<TagItem> = withIOContext {
        val url = "$baseUrl/api/tags/"
        with(json) {
            executeWithRetry(url, DICTIONARY_ATTEMPTS) {
                client.newCall(authenticated(GET(url))).awaitSuccess().use { it.parseAs() }
            }
        }
    }

    suspend fun fetchVas(): List<VaItem> = withIOContext {
        val url = "$baseUrl/api/vas/"
        with(json) {
            executeWithRetry(url, DICTIONARY_ATTEMPTS) {
                client.newCall(authenticated(GET(url))).awaitSuccess().use { it.parseAs() }
            }
        }
    }

    suspend fun fetchCircles(): List<CircleItem> = withIOContext {
        val url = "$baseUrl/api/circles/"
        with(json) {
            executeWithRetry(url, DICTIONARY_ATTEMPTS) {
                client.newCall(authenticated(GET(url))).awaitSuccess().use { it.parseAs() }
            }
        }
    }

    /** Logs in and returns the JWT token + user profile. */
    suspend fun login(name: String, password: String): AuthResponse = authenticate("/api/auth/me", name, password)

    /**
     * Creates an account and signs it in with the same request: the backend answers a registration
     * with the body a login returns, token included, so there is no second round trip and no
     * "sign in again" step in between.
     *
     * The endpoint takes no email and no invite code — a name and a password of at least five
     * characters each is the whole requirement.
     */
    suspend fun register(name: String, password: String): AuthResponse = authenticate("/api/auth/reg", name, password)

    /**
     * Posts credentials to one of the two auth endpoints.
     *
     * Deliberately not routed through [executeWithRetry]: a rejected credential answers with the
     * reason in its body (422 for a short name, "用户 xxx 已存在." for a taken one), and
     * [awaitSuccess] closes the response and throws a bare code before anyone can read it. Reading
     * the body here turns the backend's own wording into the message the dialog shows.
     *
     * Retries are for the transport only. A 4xx is a decision, so it is thrown on the first
     * attempt; a 5xx or a dropped connection is retried like the read paths are.
     */
    private suspend fun authenticate(path: String, name: String, password: String): AuthResponse = withIOContext {
        val url = "$baseUrl$path"
        val body = buildJsonObject {
            put("name", name)
            put("password", password)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return@withIOContext client.newCall(POST(url, body = body)).execute().use { response ->
                    val text = response.body.string()
                    if (response.isSuccessful) {
                        json.decodeFromString<AuthResponse>(text)
                    } else {
                        val reason = authErrorText(text)
                        if (response.code in 400..499) {
                            throw AudioAuthException(response.code, reason)
                        }
                        throw IOException(reason ?: "HTTP ${response.code}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AudioAuthException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(RETRY_BACKOFF_MILLIS shl attempt)
                }
            }
        }
        throw lastError ?: IOException("auth request failed")
    }

    /**
     * The account as the backend holds it right now: name, group and the bound email.
     *
     * The token is the only input — there is no user id to pass — and the answer is deliberately
     * not cached: this is what the account page reads to say whether an email is bound, and the
     * website can bind or unlink one while the app is closed.
     */
    suspend fun fetchProfile(): AuthUser = withIOContext {
        val url = "$baseUrl/api/auth/me"
        with(json) {
            executeWithRetry(url) {
                client.newCall(authenticated(GET(url, cache = CacheControl.FORCE_NETWORK)))
                    .awaitSuccess()
                    .use { it.parseAs<AuthResponse>() }
                    .user
            }
        }
    }

    /**
     * Sets a new password for the signed-in account.
     *
     * The backend asks for no current password — the token is the whole proof — so there is
     * nothing to verify here and nothing to send beyond the new one. The token stays valid
     * afterwards; the website does not sign the user out either.
     */
    suspend fun changePassword(newPassword: String) = withIOContext {
        postAuthAction(
            "/api/auth/change-password",
            buildJsonObject { put("newPassword", newPassword) }.toString(),
        )
    }

    /** Detaches the bound email from the account. One request, no parameters. */
    suspend fun unlinkEmail() = withIOContext {
        postAuthAction("/api/auth/link-email/unlink")
    }

    /**
     * Posts to an account endpoint that answers with no body worth reading.
     *
     * Same split as [authenticate]: a 4xx is the backend's decision and is thrown on the first
     * attempt carrying its own wording, while a dropped connection or a 5xx is retried.
     */
    private suspend fun postAuthAction(path: String, json: String? = null) {
        val url = "$baseUrl$path"
        val body = json?.toRequestBody(JSON_MEDIA_TYPE)
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val request = authenticated(if (body == null) POST(url) else POST(url, body = body))
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val text = response.body.string()
                        // A rejected token is a dead session, not a wrong password: drop it so the
                        // browse page flips back to signed out instead of retrying with it.
                        if (response.code == 401) clearSession()
                        val reason = authErrorText(text)
                        if (response.code in 400..499) throw AudioAuthException(response.code, reason)
                        throw IOException(reason ?: "HTTP ${response.code}")
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: AudioAuthException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(RETRY_BACKOFF_MILLIS shl attempt)
                }
            }
        }
        throw lastError ?: IOException("auth action failed")
    }

    /** Popular works (backend recommender), ordered by rank signals. */
    suspend fun fetchPopular(page: Int, pageSize: Int, subtitlesOnly: Boolean = false): WorksResponse = withIOContext {
        val url = "$baseUrl/api/recommender/popular"
        val body = buildJsonObject {
            put("keyword", "")
            put("page", page)
            put("pageSize", pageSize)
            // The recommender endpoints take the same flag as the work feeds, as a number in the
            // body rather than a query parameter. Measured: popular answers 100 rows, 41 with it.
            put("subtitle", if (subtitlesOnly) 1 else 0)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        with(json) {
            executeWithRetry(url) {
                client.newCall(authenticated(POST(url, body = body))).awaitSuccess().use { it.parseAs() }
            }
        }
    }

    /** Personalized recommendations keyed by an anonymous UUID. */
    suspend fun fetchRecommended(
        recommenderUuid: String,
        page: Int,
        pageSize: Int,
        subtitlesOnly: Boolean = false,
    ): WorksResponse = withIOContext {
        val url = "$baseUrl/api/recommender/recommend-for-user"
        val body = buildJsonObject {
            put("keyword", "")
            put("recommenderUuid", recommenderUuid)
            put("page", page)
            put("pageSize", pageSize)
            put("subtitle", if (subtitlesOnly) 1 else 0)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        with(json) {
            executeWithRetry(url) {
                client.newCall(authenticated(POST(url, body = body))).awaitSuccess().use { it.parseAs() }
            }
        }
    }

    /**
     * Reports one listen milestone to the recommender.
     *
     * Anonymous by design: the profile is keyed by [recommenderUuid] rather than by the token, and
     * the answer is a bare count nothing reads. Whether a listen is worth reporting is the caller's
     * decision; this only delivers it.
     */
    suspend fun sendFeedback(type: String, recommenderUuid: String, workId: Long) = withIOContext {
        val url = "$baseUrl/api/recommender/feedback"
        val body = buildJsonObject {
            put("type", type)
            put("recommenderUuid", recommenderUuid)
            put("itemId", workId)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        executeWithRetry(url) {
            client.newCall(POST(url, body = body)).awaitSuccess().close()
        }
    }

    /**
     * The works the backend considers similar to [workId], for the row under a work's track list.
     *
     * The recommender only holds vectors for the original edition of a work. A translated edition
     * — RJ335418 is the Chinese release of RJ234821 — answers with an empty list even though its
     * sibling answers with a full page, which is why a details page opened from the library used
     * to show no similar works at all. When the direct answer is empty, this asks the sibling
     * edition named by `other_language_editions_in_db` once; it never walks further than that.
     *
     * An id the backend does not know comes back as an empty list rather than an error, so there
     * is nothing to branch on beyond the sibling retry. The keyword and playlist fields the site
     * sends are left empty: a details page carries no filter of its own, and the app does not
     * collect into playlists.
     */
    suspend fun fetchNeighbors(workId: Long): List<Work> = withIOContext {
        val startedAt = SystemClock.elapsedRealtime()
        val direct = requestNeighbors(workId)
        Log.e("AUDIO_DIAG", "direct workId=$workId size=${direct.size} ms=${SystemClock.elapsedRealtime() - startedAt}")
        if (direct.isNotEmpty()) return@withIOContext direct
        val sibling = siblingEditionId(workId)
        Log.e("AUDIO_DIAG", "sibling workId=$workId -> $sibling ms=${SystemClock.elapsedRealtime() - startedAt}")
        if (sibling == null || sibling == workId) return@withIOContext emptyList()
        requestNeighbors(sibling).also {
            Log.e(
                "AUDIO_DIAG",
                "fallback sibling=$sibling size=${it.size} ms=${SystemClock.elapsedRealtime() - startedAt}",
            )
        }
    }

    private suspend fun requestNeighbors(workId: Long): List<Work> {
        val url = "$baseUrl/api/recommender/item-neighbors"
        val body = buildJsonObject {
            put("keyword", "")
            put("itemId", workId)
            putJsonArray("localSubtitledWorks") {}
            putJsonArray("withPlaylistStatus") {}
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        return with(json) {
            executeWithRetry(url) {
                client.newCall(authenticated(POST(url, body = body)))
                    .awaitSuccess()
                    .use { it.parseAs<WorksResponse>() }
            }.works
        }
    }

    /**
     * The editions of [workId] the backend actually holds, the Japanese original first.
     *
     * `language_editions` lists every edition DLSITE published, including ones this backend has no
     * copy of — RJ335418 advertises an English release whose `/api/work/1009566` answers 404, and a
     * switcher built on that list offered a chip that could not be opened. `other_language_editions_in_db`
     * is the list of editions that *are* in the database, so it filters the former down to the ones
     * worth showing. That field is a single object in an anonymous response and an array once a
     * token is attached, hence [editionIds] handling both shapes.
     *
     * Returns just the current edition for a single-language work, which is what keeps the row off
     * those pages.
     */
    suspend fun fetchEditions(workId: Long): List<WorkEdition> = withIOContext {
        try {
            val url = "$baseUrl/api/work/$workId"
            client.newCall(authenticated(GET(url))).awaitSuccess().use { response ->
                val body = json.parseToJsonElement(response.body.string()).jsonObject
                val inDb = editionIds(body["other_language_editions_in_db"])
                body["language_editions"]
                    ?.jsonArray
                    ?.mapNotNull { element ->
                        (element as? JsonObject)?.let { edition ->
                            val id = edition["workno"]?.jsonPrimitive?.contentOrNull?.let(::workNoToId)
                                ?: return@let null
                            WorkEdition(
                                id = id,
                                lang = editionLabel(edition["lang"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                                displayOrder = edition["display_order"]?.jsonPrimitive?.intOrNull ?: 0,
                                isCurrent = id == workId,
                            )
                        }
                    }
                    ?.filter { it.isCurrent || it.id in inDb }
                    ?.sortedBy { it.displayOrder }
                    .orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AUDIO_DIAG", "editions fail workId=$workId ${e::class.simpleName}: ${e.message}")
            emptyList()
        }
    }

    /** Ids from `other_language_editions_in_db`, which is an object or an array depending on auth. */
    private fun editionIds(element: JsonElement?): Set<Long> = when (element) {
        is JsonObject -> setOfNotNull(element["id"]?.jsonPrimitive?.longOrNull)
        is JsonArray -> element.mapNotNullTo(mutableSetOf()) {
            (it as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull
        }
        else -> emptySet()
    }

    /** `JPN` → 日语, `CHI_HANS,CHI_HANT` → 中文, `ENG` → 英语. */
    private fun editionLabel(lang: String): String = when {
        lang.contains("JPN") -> "日语"
        lang.contains("CHI") -> "中文"
        lang.contains("ENG") -> "英语"
        else -> lang.substringBefore(',').ifBlank { lang }
    }

    /** `RJ234821` → 234821, `RJ01009566` → 1009566. */
    private fun workNoToId(workNo: String): Long? =
        workNo.dropWhile { it.isLetter() }.toLongOrNull()

    /** The primary edition's id, the one the recommender holds vectors for. */
    private suspend fun siblingEditionId(workId: Long): Long? =
        fetchEditions(workId)
            .filter { !it.isCurrent }
            .minByOrNull { it.displayOrder }
            ?.id

    /**
     * Account collection rows, the same list the website's "Favourites" section renders.
     *
     * @param progress One state to narrow the list to, or null for every row. Null is what the
     * site's "my review" tab asks for, and it is the only way to see a row whose state was cleared:
     * the endpoint rejects an empty `filter` and answers such rows only in the unfiltered list.
     */
    suspend fun fetchAccountWorks(progress: AudioAccountProgress?, page: Int): WorksResponse = withIOContext {
        val filter = progress?.let { "&filter=${URLEncoder.encode(it.wireValue, "UTF-8")}" }.orEmpty()
        val url = "$baseUrl/api/review?order=updated_at&sort=desc&page=$page&pageSize=$ACCOUNT_PAGE_SIZE$filter"
        with(json) {
            executeWithRetry(url) {
                client.newCall(authenticated(GET(url, cache = CacheControl.FORCE_NETWORK)))
                    .awaitSuccess()
                    .use { it.parseAs() }
            }
        }
    }

    /**
     * Deletes one account row, which is the website's delete action in the review editor.
     *
     * Clearing the state instead leaves the row in place: it keeps showing up in the site's list
     * with no state at all, which is what made an uncollect look like it never reached the account.
     */
    suspend fun deleteAccountReview(workId: Long) = withIOContext {
        val url = "$baseUrl/api/review?work_id=$workId"
        executeWithRetry(url) {
            client.newCall(authenticated(DELETE(url))).awaitSuccess().close()
        }
    }

    /** Updates only the account-level listening state, preserving rating and review text. */
    suspend fun updateAccountProgress(workId: Long, progress: AudioAccountProgress?) = withIOContext {
        val url = "$baseUrl/api/review"
        val body = buildAccountProgressBody(workId, progress).toRequestBody(JSON_MEDIA_TYPE)
        executeWithRetry(url) {
            client.newCall(authenticated(PUT(url, body = body))).awaitSuccess().close()
        }
    }

    /** Downloads raw subtitle text (.lrc / .vtt / .srt / .ass) from a track download URL. */
    suspend fun fetchSubtitle(url: String, fallbackUrl: String? = null): String = withIOContext {
        var lastError: Exception? = null
        subtitleUrls(url, fallbackUrl).forEach { candidate ->
            try {
                return@withIOContext executeWithRetry(candidate) {
                    val request = GET(candidate, cache = CacheControl.FORCE_NETWORK).let { request ->
                        if (candidate.startsWith(baseUrl, ignoreCase = true)) authenticated(request) else request
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

    /**
     * [cache] overrides the ten minute freshness every other GET gets by default. A pull to
     * refresh passes [CacheControl.FORCE_NETWORK] so it is answered by the backend rather than
     * by the copy [AudioCacheInterceptor] put on disk five minutes ago.
     */
    private fun get(url: String, cache: CacheControl? = null): Request =
        if (cache == null) GET(url) else GET(url, cache = cache)

    private fun authenticated(request: Request): Request {
        return request.withAudioAuthorization(basePreferences.audioAuthToken.get())
    }

    /**
     * Forgets the stored session.
     *
     * Both halves go together: the browse page keys "signed in" on a non-blank token *and* a
     * non-blank name, so clearing only the token would leave a name with nothing behind it.
     */
    private fun clearSession() {
        basePreferences.audioAuthToken.set("")
        basePreferences.audioUsername.set("")
    }

    private fun seedParam(seed: Long?): String = seed?.let { "&seed=$it" }.orEmpty()

    /**
     * `&subtitle=1` narrows the list to works that ship subtitles.
     *
     * Measured against the live API: `subtitle=1` answers 8032 of 62897 works, `subtitle=0` and an
     * absent parameter answer all of them, and anything else (`true`, `2`) is rejected with a 400.
     * The same parameter narrows `/api/search/{keyword}` (病み: 47 → 2) and the dictionary feeds
     * (`/api/tags/1/works`: 3534 → 443), so one flag covers every list the browse page builds.
     */
    private fun subtitleParam(subtitlesOnly: Boolean): String = if (subtitlesOnly) "&subtitle=1" else ""

    /**
     * Retries transient failures (server 5xx, connection reset) a couple of times with a short
     * backoff. The backend sits behind Cloudflare and occasionally returns 503 under load, which
     * previously surfaced as an instant error.
     *
     * @param url Only used to label the timing log, which is how the loading work is measured.
     */
    private suspend fun <T> executeWithRetry(
        url: String,
        maxAttempts: Int = MAX_ATTEMPTS,
        block: suspend () -> T,
    ): T {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            var lastError: Exception? = null
            repeat(maxAttempts) { attempt ->
                try {
                    return block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is HttpException && e.code == 401) {
                        clearSession()
                    }
                    if (e is HttpException && e.code in 400..499) throw e
                    lastError = e
                    if (attempt < maxAttempts - 1) {
                        delay(RETRY_BACKOFF_MILLIS shl attempt)
                    }
                }
            }
            throw lastError ?: IOException("request failed")
        } finally {
            logcat { "kikoeru ${SystemClock.elapsedRealtime() - startedAt}ms $url" }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val DICTIONARY_ATTEMPTS = 2
        const val RETRY_BACKOFF_MILLIS = 400L

        /** Latency samples per host in the picker; the cheapest of them is shown. */
        const val LATENCY_SAMPLES = 2

        /** The account list is read in one pass; 100 is the largest page the endpoint accepts. */
        const val ACCOUNT_PAGE_SIZE = 100
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

/**
 * A sign-in or registration the backend refused, carrying its own wording so the dialog can say
 * 用户 xxx 已存在. rather than a generic failure line.
 *
 * [statusCode] is kept because the two refusals lead somewhere different: a 422 is a field the
 * user can fix in place, while a taken name means going back to the login half of the dialog.
 */
class AudioAuthException(val statusCode: Int, message: String?) :
    IOException(message?.takeIf { it.isNotBlank() } ?: "HTTP $statusCode")

/**
 * Pulls the reason out of a failed auth response. The backend answers two shapes:
 * `{"error":"用户 xxx 已存在."}` and `{"errors":[{"value":"ab","msg":"用户名长度至少为 5",…}]}`.
 *
 * Returns null for a body that carries neither, so the caller can fall back to its own wording
 * instead of showing an empty line.
 */
internal fun authErrorText(body: String): String? = runCatching {
    val root = Json.parseToJsonElement(body).jsonObject
    root["error"]?.jsonPrimitive?.contentOrNull
        ?: root["errors"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("msg")?.jsonPrimitive?.contentOrNull
}.getOrNull()?.takeIf { it.isNotBlank() }
