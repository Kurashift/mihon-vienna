package eu.kanade.presentation.webview

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class WebViewHeaderPolicy(initialUrl: String, private val headers: Map<String, String>) {
    private val host = initialUrl.toHttpUrlOrNull()?.host

    fun headersFor(url: String): Map<String, String> {
        return if (host != null && url.toHttpUrlOrNull()?.host == host) headers else emptyMap()
    }
}
