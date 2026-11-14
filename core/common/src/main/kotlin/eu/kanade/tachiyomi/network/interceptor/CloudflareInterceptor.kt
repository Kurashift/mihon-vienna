package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.toWebViewHeaders
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    override fun shouldIntercept(response: Response): Boolean {
        // Check if Cloudflare anti-bot is on
        return if (response.code in ERROR_CODES && response.header("Server") in SERVER_CHECK) {
            val document = Jsoup.parse(
                response.peekBody(Long.MAX_VALUE).string(),
                response.request.url.toString(),
            )

            // solve with webview only on captcha, not on geo block
            document.getElementById("challenge-error-title") != null ||
                document.getElementById("challenge-error-text") != null
        } else {
            false
        }
    }

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
    ): Response {
        try {
            response.close()
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            val oldCookie = cookieManager.get(request.url)
                .firstOrNull { it.name == "cf_clearance" }
            resolveWithWebView(request, oldCookie)

            return chain.proceed(request)
        }
        // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
        // we don't crash the entire app
        catch (e: CloudflareBypassException) {
            throw IOException(context.stringResource(MR.strings.information_cloudflare_bypass_failure), e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, oldCookie: Cookie?) {
        // We need to lock this thread until the WebView finds the challenge solution url, because
        // OkHttp doesn't support asynchronous interceptors.
        val latch = CountDownLatch(1)

        var webview: WebView? = null

        var challengeFound = false
        val cloudflareBypassed = AtomicBoolean(false)

        val origRequestUrl = originalRequest.url.toString()
        val headers = originalRequest.headers.toWebViewHeaders()

        executor.execute {
            try {
                val view = createWebView(originalRequest)
                webview = view

                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        fun isCloudFlareBypassed(): Boolean {
                            return cookieManager.get(origRequestUrl.toHttpUrl())
                                .firstOrNull { it.name == "cf_clearance" }
                                .let { it != null && it != oldCookie }
                        }

                        if (isCloudFlareBypassed()) {
                            cloudflareBypassed.set(true)
                            latch.countDown()
                        }

                        if (url == origRequestUrl && !challengeFound) {
                            // The first request didn't return the challenge, abort.
                            latch.countDown()
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            if (errorResponse?.statusCode in ERROR_CODES) {
                                // Found the Cloudflare challenge page.
                                challengeFound = true
                            } else {
                                // Unlock thread, the challenge wasn't found.
                                latch.countDown()
                            }
                        }
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        logcat(LogPriority.ERROR) {
                            "WebView renderer gone during Cloudflare bypass: crashed=${detail.didCrash()}, " +
                                "priority=${detail.rendererPriorityAtExit()}"
                        }
                        if (webview === view) {
                            webview = null
                            cloudflareBypassed.set(false)
                            try {
                                view.destroy()
                            } finally {
                                latch.countDown()
                            }
                        }
                        return true
                    }
                }

                view.loadUrl(origRequestUrl, headers)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load Cloudflare challenge in WebView" }
                latch.countDown()
            }
        }

        try {
            latch.awaitFor30Seconds()
        } finally {
            executor.execute {
                val view = webview ?: return@execute
                webview = null
                try {
                    if (!cloudflareBypassed.get() && view.isOutdated()) {
                        context.toast(MR.strings.information_webview_outdated, Toast.LENGTH_LONG)
                    }
                    view.stopLoading()
                } finally {
                    view.destroy()
                }
            }
        }

        // Throw exception if we failed to bypass Cloudflare
        if (!cloudflareBypassed.get()) {
            throw CloudflareBypassException()
        }
    }
}

private val ERROR_CODES = listOf(403, 503)
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")

private class CloudflareBypassException : Exception()
