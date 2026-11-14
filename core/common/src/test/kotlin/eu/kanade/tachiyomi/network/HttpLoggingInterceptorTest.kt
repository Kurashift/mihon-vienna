package eu.kanade.tachiyomi.network

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class HttpLoggingInterceptorTest {

    @ParameterizedTest
    @ValueSource(strings = ["Authorization", "authorization", "AUTHORIZATION", "Auth", "aUtH"])
    fun `verbose request and response logs redact credentials without changing traffic`(name: String) {
        val logs = mutableListOf<String>()
        val logger = createHttpLoggingInterceptor { logs.add(it) }
        val request = Request.Builder()
            .url("https://source.example/chapter")
            .header(name, "request-secret")
            .header("X-Request-Trace", "request-visible")
            .build()
        val responseHeaders = Headers.Builder()
            .add(name, "response-secret")
            .add("X-Response-Trace", "response-visible")
            .build()
        val client = OkHttpClient.Builder()
            .addInterceptor(logger)
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .headers(responseHeaders)
                    .body("response".toResponseBody())
                    .build()
            }
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals("request-secret", response.request.header(name))
            assertEquals("response-secret", response.header(name))
            assertEquals(request.headers, response.request.headers)
            assertEquals(responseHeaders, response.headers)
        }

        val output = logs.joinToString("\n")
        assertFalse(output.contains("request-secret"))
        assertFalse(output.contains("response-secret"))
        assertEquals(2, logs.count { it.startsWith("$name:") })
        assertTrue(output.contains("request-visible"))
        assertTrue(output.contains("response-visible"))
    }

    @Test
    fun `verbose logging retains headers level`() {
        assertEquals(HttpLoggingInterceptor.Level.HEADERS, createHttpLoggingInterceptor {}.level)
    }
}
