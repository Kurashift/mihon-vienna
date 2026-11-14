package eu.kanade.tachiyomi.network

import okhttp3.Headers
import okhttp3.logging.HttpLoggingInterceptor
import java.util.Locale
import java.util.TreeMap

fun Headers.toWebViewHeaders(): Map<String, String> {
    val result = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
    for ((name, value) in this) {
        val normalizedName = name.lowercase(Locale.ROOT)
        if (normalizedName in UNSAFE_WEBVIEW_HEADERS || normalizedName.startsWith("proxy-")) continue
        if (normalizedName == "connection" && value.split(',').any { it.trim().equals("upgrade", true) }) continue
        result.putIfAbsent(name, value)
    }
    return result
}

internal fun createHttpLoggingInterceptor(
    logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
): HttpLoggingInterceptor {
    return HttpLoggingInterceptor(logger).apply {
        level = HttpLoggingInterceptor.Level.HEADERS
        redactHeader("Authorization")
        redactHeader("Auth")
    }
}

private val UNSAFE_WEBVIEW_HEADERS = setOf(
    "content-length",
    "host",
    "trailer",
    "te",
    "upgrade",
    "cookie2",
    "keep-alive",
    "transfer-encoding",
    "set-cookie",
    "origin",
)
