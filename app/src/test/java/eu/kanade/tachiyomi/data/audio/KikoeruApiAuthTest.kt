package eu.kanade.tachiyomi.data.audio

import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KikoeruApiAuthTest {

    @Test
    fun `bearer token is attached to authenticated request`() {
        val request = Request.Builder()
            .url("https://api.asmr-200.com/api/works")
            .build()
            .withAudioAuthorization("token-value")

        assertEquals("Bearer token-value", request.header("Authorization"))
    }

    @Test
    fun `blank token leaves request unauthenticated`() {
        val request = Request.Builder()
            .url("https://api.asmr-200.com/api/works")
            .build()
            .withAudioAuthorization("")

        assertNull(request.header("Authorization"))
    }

    @Test
    fun `subtitle download URL prefers its streaming counterpart`() {
        assertEquals(
            listOf(
                "https://raw.example/media/stream/work/track.lrc",
                "https://raw.example/media/download/work/track.lrc",
            ),
            subtitleUrls("https://raw.example/media/download/work/track.lrc"),
        )
    }

    @Test
    fun `subtitle URLs include a fallback download endpoint`() {
        assertEquals(
            listOf(
                "https://api.example/media/stream/1/2",
                "https://raw.example/media/stream/1/3",
                "https://raw.example/media/download/1/3",
            ),
            subtitleUrls(
                "https://api.example/media/stream/1/2",
                "https://raw.example/media/download/1/3",
            ),
        )
    }

    // The two shapes the backend refuses a sign-in or a registration with. Both carry a sentence
    // the dialog shows as-is, which is the whole reason the response is read by hand.
    @Test
    fun `auth error text reads a refused credential`() {
        assertEquals(
            "用户 dsh_probe_0913 已存在.",
            authErrorText("""{"error":"用户 dsh_probe_0913 已存在."}"""),
        )
    }

    @Test
    fun `auth error text reads a field validation failure`() {
        assertEquals(
            "用户名长度至少为 5",
            authErrorText(
                """{"errors":[{"value":"ab","msg":"用户名长度至少为 5","param":"name","location":"body"}]}""",
            ),
        )
    }

    @Test
    fun `auth error text ignores a body without a reason`() {
        assertNull(authErrorText("{}"))
        assertNull(authErrorText(""))
        assertNull(authErrorText("<html>cloudflare</html>"))
    }
}
