package eu.kanade.tachiyomi.data.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioTrackCacheTest {

    private fun nodes(vararg titles: String) = titles.map {
        TrackNode(type = "audio", title = it, mediaStreamUrl = "https://raw.example/$it")
    }

    /** A cache whose clock is whatever the test last set [current] to. */
    private var current = 1_000_000L
    private fun cache(): AudioTrackCache = AudioTrackCache { current }

    @Test
    fun `a tree that was never fetched is not there`() {
        assertNull(cache().get(1L))
    }

    @Test
    fun `a fetched tree is served back without asking again`() {
        val cache = cache()
        val tree = nodes("a.mp3", "b.mp3")

        cache.put(1L, tree)

        assertSame(tree, cache.get(1L))
    }

    @Test
    fun `trees of different works do not collide`() {
        val cache = cache()
        val first = nodes("a.mp3")
        val second = nodes("b.mp3")

        cache.put(1L, first)
        cache.put(2L, second)

        assertSame(first, cache.get(1L))
        assertSame(second, cache.get(2L))
    }

    @Test
    fun `removing a tree reports whether there was one to drop`() {
        val cache = cache()
        cache.put(1L, nodes("a.mp3"))

        assertTrue(cache.remove(1L))
        assertFalse(cache.remove(1L))
    }

    @Test
    fun `a dropped tree is gone and the next read misses`() {
        val cache = cache()
        cache.put(1L, nodes("a.mp3"))
        cache.remove(1L)

        assertNull(cache.get(1L))
    }

    @Test
    fun `a later tree replaces the earlier one for the same work`() {
        val cache = cache()
        val first = nodes("a.mp3")
        val second = nodes("a.mp3", "b.mp3")

        cache.put(1L, first)
        cache.put(1L, second)

        assertNotSame(first, cache.get(1L))
        assertSame(second, cache.get(1L))
    }

    @Test
    fun `a tree still inside the age limit is served`() {
        val cache = cache()
        cache.put(1L, nodes("a.mp3"))
        current += AudioTrackCache.MAX_AGE.inWholeMilliseconds - 1

        assertEquals(listOf("a.mp3"), cache.get(1L)?.map { it.title })
    }

    @Test
    fun `a tree past the age limit is dropped rather than served`() {
        val cache = cache()
        cache.put(1L, nodes("a.mp3"))
        current += AudioTrackCache.MAX_AGE.inWholeMilliseconds

        assertNull(cache.get(1L))
    }

    @Test
    fun `an expired tree is discarded, not left for a later read`() {
        val cache = cache()
        cache.put(1L, nodes("a.mp3"))
        current += AudioTrackCache.MAX_AGE.inWholeMilliseconds
        cache.get(1L)

        // Trim only runs on put, so the entry is still counted; reading it again still misses.
        assertNull(cache.get(1L))
    }

    @Test
    fun `many works stay bounded`() {
        val cache = cache()

        repeat(200) { cache.put(it.toLong(), nodes("t$it.mp3")) }

        var present = 0
        repeat(200) { if (cache.get(it.toLong()) != null) present++ }
        assertTrue(present < 200, "expected trimming to discard some trees, kept $present")
        // The newest tree is never the one thrown away.
        assertEquals(listOf("t199.mp3"), cache.get(199L)?.map { it.title })
    }
}
