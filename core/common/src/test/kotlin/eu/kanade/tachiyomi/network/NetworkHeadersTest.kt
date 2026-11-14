package eu.kanade.tachiyomi.network

import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NetworkHeadersTest {

    @ParameterizedTest
    @ValueSource(strings = ["Origin", "origin", "ORIGIN", "oRiGiN"])
    fun `origin is never forwarded to WebView`(name: String) {
        val headers = Headers.Builder()
            .add(name, "https://source.example")
            .add("Referer", "https://source.example/chapter")
            .build()

        assertEquals(mapOf("Referer" to "https://source.example/chapter"), headers.toWebViewHeaders())
        assertEquals("https://source.example", headers[name])
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Content-Length", "HOST", "Trailer", "TE", "Upgrade", "Cookie2", "Keep-Alive",
            "Transfer-Encoding", "Set-Cookie", "Proxy-Authorization", "PROXY-Connection", "pRoXy-Custom",
        ],
    )
    fun `chromium unsafe headers are filtered case insensitively`(name: String) {
        assertTrue(Headers.Builder().add(name, "value").build().toWebViewHeaders().isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["upgrade", "Upgrade", "keep-alive, Upgrade", "UPGRADE, keep-alive"])
    fun `connection upgrades are filtered including token lists`(value: String) {
        val headers = Headers.Builder().add("Connection", value).build().toWebViewHeaders()

        assertFalse(headers.containsKey("connection"))
    }

    @Test
    fun `safe source headers and empty values survive conversion`() {
        val headers = Headers.Builder()
            .add("Authorization", "Bearer source-token")
            .add("Auth", "source-key")
            .add("Cookie", "session=value")
            .add("User-Agent", "source-agent")
            .add("Referer", "https://source.example/")
            .add("Connection", "keep-alive")
            .add("X-Empty", "")
            .build()

        val converted = headers.toWebViewHeaders()

        assertEquals(headers.size, converted.size)
        headers.forEach { (name, value) -> assertEquals(value, converted[name]) }
        assertEquals("source-agent", converted["user-agent"])
    }

    @Test
    fun `repeated names retain the first value across casing variants`() {
        val headers = Headers.Builder()
            .add("X-Token", "first")
            .add("x-token", "second")
            .add("X-TOKEN", "third")
            .build()

        val converted = headers.toWebViewHeaders()

        assertEquals(1, converted.size)
        assertEquals("first", converted["x-token"])
        assertEquals(listOf("first", "second", "third"), headers.values("x-token"))
    }

    @Test
    fun `empty headers produce an empty map`() {
        assertTrue(Headers.Builder().build().toWebViewHeaders().isEmpty())
    }
}
