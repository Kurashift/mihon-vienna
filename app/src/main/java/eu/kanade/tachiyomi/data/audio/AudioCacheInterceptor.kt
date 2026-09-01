package eu.kanade.tachiyomi.data.audio

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Forces cacheable freshness headers onto the audio backend's responses.
 *
 * The backend sends no `Cache-Control`/`Expires`, and OkHttp refuses to store a response that
 * carries no freshness information at all — so without this interceptor the disk cache wired up
 * in `AppModule` would simply never fill. The `public` directive matters just as much: replies to
 * our `Authorization`-bearing requests are treated as user specific and skipped unless the
 * response explicitly says they may be shared.
 *
 * Installed as a *network* interceptor on purpose, so it only rewrites what comes off the wire and
 * never touches a response already served from cache.
 *
 * What ends up cached: `/api/works` and `/api/tracks/{id}`. Everything else opts out — the
 * category dictionaries to `AudioCategoryCache`, and the account feeds, searches and subtitle
 * downloads by asking for the network.
 */
object AudioCacheInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!request.url.host.equals(API_HOST, ignoreCase = true)) return response
        // The category dictionaries are owned by AudioCategoryCache: letting OkHttp keep a second
        // copy of the same multi-megabyte payload would only double the disk cost.
        if (request.url.encodedPath in UNCACHED_PATHS) return response
        // A request carrying `no-cache` refuses to read from the cache, so storing its response
        // would only fill the disk with entries that can never be served. What is left after this
        // check is exactly the public, order independent data: work pages and track trees.
        if (request.cacheControl.noCache) return response
        return response.newBuilder()
            .header("Cache-Control", "public, max-age=$MAX_AGE_SECONDS")
            .removeHeader("Pragma")
            .build()
    }

    private const val API_HOST = "api.asmr-200.com"

    /** How long a work list / track tree may be reused without hitting the network. */
    private const val MAX_AGE_SECONDS = 300L

    private val UNCACHED_PATHS = setOf("/api/tags/", "/api/vas/", "/api/circles/")
}
