package eu.kanade.presentation.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WebViewHeaderPolicyTest {

    private val headers = mapOf("Authorization" to "source-token", "Referer" to "https://source.example/")

    @Test
    fun `initial request and same host navigation keep source headers`() {
        val policy = WebViewHeaderPolicy("https://source.example/title", headers)

        assertSame(headers, policy.headersFor("https://source.example/title"))
        assertSame(headers, policy.headersFor("https://source.example/chapter?page=2#image"))
        assertSame(headers, policy.headersFor("https://SOURCE.EXAMPLE/chapter"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://other.example/chapter",
            "https://sub.source.example/chapter",
            "https://source.example.other.example/chapter",
            "https://source.example@other.example/chapter",
            "https://other.example/source.example",
        ],
    )
    fun `cross host navigation never receives source headers`(url: String) {
        val policy = WebViewHeaderPolicy("https://source.example/title", headers)

        assertTrue(policy.headersFor(url).isEmpty())
    }

    @Test
    fun `the initial host remains authoritative after leaving and returning`() {
        val policy = WebViewHeaderPolicy("https://source.example/title", headers)

        assertTrue(policy.headersFor("https://other.example/login").isEmpty())
        assertSame(headers, policy.headersFor("https://source.example/return"))
        assertTrue(policy.headersFor("https://other.example/next").isEmpty())
        assertEquals(2, headers.size)
    }

    @Test
    fun `host policy preserves upstream behavior across ports and web schemes`() {
        val policy = WebViewHeaderPolicy("https://source.example/title", headers)

        assertSame(headers, policy.headersFor("https://source.example:8443/chapter"))
        assertSame(headers, policy.headersFor("http://source.example/chapter"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "not a url", "/chapter", "javascript:alert(1)", "data:text/html,source.example",
            "intent://source.example", "ftp://source.example/chapter", "httpx://source.example/chapter",
        ],
    )
    fun `invalid and non web targets never receive source headers`(url: String) {
        val policy = WebViewHeaderPolicy("https://source.example/title", headers)

        assertTrue(policy.headersFor(url).isEmpty())
    }

    @Test
    fun `invalid initial urls fail closed even when the target is also invalid`() {
        val policy = WebViewHeaderPolicy("not a url", headers)

        assertTrue(policy.headersFor("not a url").isEmpty())
        assertTrue(policy.headersFor("https://source.example/title").isEmpty())
    }
}
