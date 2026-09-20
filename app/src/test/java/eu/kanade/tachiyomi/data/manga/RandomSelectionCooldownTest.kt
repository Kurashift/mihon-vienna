package eu.kanade.tachiyomi.data.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class RandomSelectionCooldownTest {

    @Test
    fun `a pick cools its whole work, not only the chapter it landed on`() {
        val cooldown = createCooldown()
        val chapters = listOf(
            Candidate(mangaId = 1, chapterId = 10),
            Candidate(mangaId = 1, chapterId = 11),
            Candidate(mangaId = 2, chapterId = 20),
        )

        assertEquals(1L, cooldown.pickManga(listOf(1, 2)))

        assertEquals(
            listOf(Candidate(2, 20)),
            cooldown.eligibleChapters(
                candidates = chapters,
                currentMangaId = null,
                releaseOnExhaustion = false,
                mangaId = Candidate::mangaId,
                chapterId = Candidate::chapterId,
            ),
        )
    }

    @Test
    fun `expired entries return to the pool`() {
        var now = 0L
        val cooldown = createCooldown { now }

        assertEquals(1L, cooldown.pickManga(listOf(1, 2)))
        now = 60 * 60 * 1000L + 1

        assertEquals(1L, cooldown.pickManga(listOf(1, 2)))
    }

    @Test
    fun `exhausted manga pool releases cooldown and retries once`() {
        val cooldown = createCooldown()

        assertEquals(1L, cooldown.pickManga(listOf(1)))
        assertEquals(1L, cooldown.pickManga(listOf(1)))
    }

    @Test
    fun `exhausted pool still excludes the most recent pick`() {
        val preference = InMemoryPreferenceStore().getString("random_selection_cooldown")
        // Whole pool cooled, with manga 1 as the most recent selection and first in the pool.
        preference.set("""[{"mangaId":2,"at":0},{"mangaId":1,"at":0}]""")
        val cooldown = RandomSelectionCooldown(preference, now = { 0L }, randomIndex = { 0 })

        assertEquals(2L, cooldown.pickManga(listOf(1, 2)))
    }

    @Test
    fun `chapter pool only releases cooldown for an explicit retry`() {
        val cooldown = createCooldown()
        val chapter = Candidate(1, 10)
        cooldown.rememberManga(chapter.mangaId)

        assertEquals(
            emptyList<Candidate>(),
            cooldown.eligibleChapters(
                candidates = listOf(chapter),
                currentMangaId = null,
                releaseOnExhaustion = false,
                mangaId = Candidate::mangaId,
                chapterId = Candidate::chapterId,
            ),
        )
        assertEquals(
            listOf(chapter),
            cooldown.eligibleChapters(
                candidates = listOf(chapter),
                currentMangaId = null,
                releaseOnExhaustion = true,
                mangaId = Candidate::mangaId,
                chapterId = Candidate::chapterId,
            ),
        )
    }

    @Test
    fun `legacy chapter entries remain compatible`() {
        val preference = InMemoryPreferenceStore().getString("random_selection_cooldown")
        preference.set("""[{"mangaId":1,"chapterId":10,"at":0}]""")
        val cooldown = RandomSelectionCooldown(preference, now = { 0L }, randomIndex = { 0 })

        // The chapter-level entry written by an earlier version still cools its work.
        assertEquals(2L, cooldown.pickManga(listOf(1, 2)))
    }

    @Test
    fun `current manga is excluded without clearing its cooldown`() {
        val cooldown = createCooldown()

        assertEquals(1L, cooldown.pickManga(listOf(1)))
        assertNull(cooldown.pickManga(listOf(1), currentMangaId = 1))
        assertEquals(2L, cooldown.pickManga(listOf(1, 2)))
    }

    @Test
    fun `chapters of the current work are dropped unless they are the whole pool`() {
        val cooldown = createCooldown()
        val sameWork = listOf(Candidate(1, 10), Candidate(1, 11))
        val mixed = sameWork + Candidate(2, 20)

        assertEquals(
            listOf(Candidate(2, 20)),
            cooldown.eligibleChapters(
                candidates = mixed,
                currentMangaId = 1,
                releaseOnExhaustion = true,
                mangaId = Candidate::mangaId,
                chapterId = Candidate::chapterId,
            ),
        )
        // Nothing else to offer: staying put beats refusing to jump at all.
        assertEquals(
            sameWork,
            cooldown.eligibleChapters(
                candidates = sameWork,
                currentMangaId = 1,
                releaseOnExhaustion = true,
                mangaId = Candidate::mangaId,
                chapterId = Candidate::chapterId,
            ),
        )
    }

    /**
     * The symptom a reader reported: with a small pool, the same work kept coming back every other
     * hop. The exhaustion branch kept `pool.size - 1` of them cooling and only reacted once the
     * pool ran completely dry, so the window grew until exactly one work was still drawable - and
     * `randomIndex(1)` is a constant, so that hop was not a pick at all but a foregone conclusion.
     *
     * The index is handed the candidate count here, which is the thing that was wrong: a hop drawn
     * against a count of 1 cannot be random. Every draw has to see at least two.
     */
    @Test
    fun `a small pool always offers a real choice, never one forced candidate`() {
        val preference = InMemoryPreferenceStore().getString("random_selection_cooldown")
        // The whole pool is cooling, with 3 as the most recent pick.
        preference.set("""[{"mangaId":1,"at":0},{"mangaId":2,"at":0},{"mangaId":3,"at":0}]""")
        val counts = mutableListOf<Int>()
        var draws = 0
        val cooldown = RandomSelectionCooldown(
            preference,
            now = { 0L },
            randomIndex = { size ->
                counts += size
                (draws++ * 3) % size
            },
        )
        val pool = listOf(1L, 2L, 3L, 4L, 5L)

        var current: Long? = null
        repeat(20) {
            current = cooldown.pickManga(pool, currentMangaId = current)
        }

        assertTrue(counts.all { it >= 2 }, "every hop must choose between at least two works: $counts")
    }

    @Test
    fun `a small shelf never repeats a work on consecutive picks`() {
        val preference = InMemoryPreferenceStore().getString("random_selection_cooldown")
        var draws = 0
        val cooldown = RandomSelectionCooldown(
            preference,
            now = { 0L },
            randomIndex = { size -> (draws++ * 7) % size },
        )
        val pool = listOf(1L, 2L, 3L, 4L, 5L)

        var current: Long? = null
        repeat(24) {
            val picked = cooldown.pickManga(pool, currentMangaId = current)!!
            assertTrue(picked != current, "picked $picked twice in a row from $pool")
            current = picked
        }
    }

    /**
     * Two works is the floor a hop cannot improve on: whatever is picked, the next hop has to be
     * the other one. It must still alternate rather than stall on a single work.
     */
    @Test
    fun `a two-work pool alternates instead of stalling`() {
        val cooldown = createCooldown()
        val pool = listOf(1L, 2L)

        assertEquals(1L, cooldown.pickManga(pool, currentMangaId = null))
        assertEquals(2L, cooldown.pickManga(pool, currentMangaId = 1L))
        assertEquals(1L, cooldown.pickManga(pool, currentMangaId = 2L))
    }

    @Test
    fun `empty pool stays empty`() {
        val cooldown = createCooldown()

        assertNull(cooldown.pickManga(emptyList()))
    }

    @Test
    fun `remembering a work twice does not duplicate it`() {
        val preference = InMemoryPreferenceStore().getString("random_selection_cooldown")
        val cooldown = RandomSelectionCooldown(preference, now = { 0L }, randomIndex = { 0 })

        cooldown.rememberManga(1)
        cooldown.rememberManga(1)

        assertTrue(preference.get().count { it == '{' } == 1)
    }

    private fun createCooldown(now: () -> Long = { 0L }): RandomSelectionCooldown {
        val preference = InMemoryPreferenceStore().getString("random_selection_cooldown")
        return RandomSelectionCooldown(
            preference = preference,
            now = now,
            randomIndex = { 0 },
        )
    }

    private data class Candidate(
        val mangaId: Long,
        val chapterId: Long,
    )
}
