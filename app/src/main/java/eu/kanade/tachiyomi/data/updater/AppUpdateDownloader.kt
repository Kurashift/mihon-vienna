package eu.kanade.tachiyomi.data.updater

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.model.Release
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Fetches the release APK into the app's own cache so it can be handed to the system installer.
 *
 * The download goes through the app's normal client, so it follows the same DNS-over-HTTPS and
 * proxy setup as everything else. The client's two-minute call timeout is lifted for this call: an
 * APK is tens of megabytes, and a slow link is not an error — a stalled one is, and the read
 * timeout still catches that.
 *
 * The checksum is the release API's own `sha256` digest, compared before the file is returned. A
 * mirror or a proxy that rewrites the payload fails here instead of at install time, where the
 * only feedback Android gives is "应用未安装".
 */
class AppUpdateDownloader(
    private val context: Context,
    private val networkHelper: NetworkHelper = Injekt.get(),
) {

    suspend fun download(
        release: Release,
        onProgress: suspend (AppUpdateProgress) -> Unit,
    ): File = withIOContext {
        val directory = File(context.cacheDir, UPDATE_DIRECTORY).apply { mkdirs() }
        // One update at a time: a half-finished download from an earlier attempt must not be able
        // to pass for this one.
        directory.listFiles()?.forEach { it.delete() }

        val target = File(directory, "update-${release.version}.apk")
        val client = networkHelper.client.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        onProgress(AppUpdateProgress.Downloading(0, release.sizeBytes))

        try {
            client.newCall(Request.Builder().url(release.downloadLink).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var reportedAt = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - reportedAt >= PROGRESS_STEP_BYTES) {
                                reportedAt = downloaded
                                onProgress(AppUpdateProgress.Downloading(downloaded, total))
                            }
                        }
                        onProgress(AppUpdateProgress.Downloading(downloaded, total))
                    }
                }
            }
        } catch (e: Exception) {
            // Nothing half-downloaded is left behind: a cancelled or failed attempt must not sit in
            // the cache looking like a usable package.
            target.delete()
            throw e
        }

        release.sha256?.let { expected ->
            onProgress(AppUpdateProgress.Verifying)
            val actual = target.sha256()
            if (!actual.equals(expected, ignoreCase = true)) {
                target.delete()
                throw IOException("checksum mismatch: $actual")
            }
        }

        target
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val UPDATE_DIRECTORY = "updates"
        const val READ_TIMEOUT_SECONDS = 60L
        const val PROGRESS_STEP_BYTES = 256L * 1024
    }
}
