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
}
