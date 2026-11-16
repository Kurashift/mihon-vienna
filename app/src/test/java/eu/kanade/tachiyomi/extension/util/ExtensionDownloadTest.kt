package eu.kanade.tachiyomi.extension.util

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ExtensionDownloadTest {

    @TempDir
    lateinit var directory: File

    @Test
    fun `download writes the complete successful response`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 3)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
            exchange.close()
        }
        server.start()
        try {
            val target = File(directory, "download.apk")
            downloadExtensionApk(OkHttpClient(), "http://127.0.0.1:${server.address.port}/", target)
            assertEquals(listOf<Byte>(1, 2, 3), target.readBytes().toList())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `failed response never overwrites the download target`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            val target = File(directory, "download.apk").apply { writeText("unchanged") }
            assertThrows(HttpException::class.java) {
                runBlocking {
                    downloadExtensionApk(OkHttpClient(), "http://127.0.0.1:${server.address.port}/", target)
                }
            }
            assertEquals("unchanged", target.readText())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `cancel interrupts a stalled network body instead of waiting for read timeout`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.createContext("/") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 1_000_000)
                exchange.responseBody.write(1)
                exchange.responseBody.flush()
                started.complete(Unit)
                release.await()
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val target = File(directory, "download.apk")
            val job = launch(Dispatchers.IO) {
                downloadExtensionApk(OkHttpClient(), "http://127.0.0.1:${server.address.port}/", target)
            }
            withTimeout(5_000) {
                started.await()
                job.cancelAndJoin()
            }
            assertTrue(job.isCancelled)
        } finally {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
