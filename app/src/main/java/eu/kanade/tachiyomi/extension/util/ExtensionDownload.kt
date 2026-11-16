package eu.kanade.tachiyomi.extension.util

import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

internal suspend fun downloadExtensionApk(client: OkHttpClient, url: String, target: File) = coroutineScope {
    val call = client.newCall(Request.Builder().url(url).build())
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        call.awaitSuccess().use { response ->
            runInterruptible {
                response.body.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    } finally {
        cancellation.cancel()
    }
}
