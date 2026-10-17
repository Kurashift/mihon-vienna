package eu.kanade.tachiyomi.data.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AudioBackendTest {

    @Test
    fun `a stored url resolves back to its entry`() {
        assertEquals(AudioBackend.ASMR_300, AudioBackend.fromUrl("https://api.asmr-300.com"))
        assertEquals(AudioBackend.ASMR_ONE, AudioBackend.fromUrl("https://API.ASMR.ONE"))
    }

    @Test
    fun `an url no longer offered does not resolve`() {
        assertNull(AudioBackend.fromUrl("https://api.example.com"))
        assertNull(AudioBackend.fromUrl(""))
    }

    @Test
    fun `the picker orders measured hosts first and leaves the rest in list order`() {
        val latency = mapOf(
            AudioBackend.ASMR_300.apiBaseUrl to 180L,
            AudioBackend.ASMR_100.apiBaseUrl to 90L,
            // Unreachable and never measured both have no number, and both belong after the hosts
            // that answered; between themselves they keep the publisher's order.
            AudioBackend.ASMR_ONE.apiBaseUrl to null,
        )

        assertEquals(
            listOf(
                AudioBackend.ASMR_100,
                AudioBackend.ASMR_300,
                AudioBackend.ASMR_ONE,
                AudioBackend.ASMR_200,
            ),
            AudioBackend.sortedByLatency(latency),
        )
    }

    @Test
    fun `the default host is the one the section shipped with`() {
        assertEquals("https://api.asmr-200.com", AudioBackend.DEFAULT.apiBaseUrl)
    }
}
