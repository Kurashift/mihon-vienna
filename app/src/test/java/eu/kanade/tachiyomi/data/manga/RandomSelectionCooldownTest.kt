package eu.kanade.tachiyomi.data.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class RandomSelectionCooldownTest {

    @Test
    fun `chapter cooldown only excludes the exact chapter`() {
        val cooldown = createCooldown()
        val chapters = listOf(
            Candidate(mangaId = 1, chapterId = 10),
            Candidate(mangaId = 1, chapterId = 11),
        )

        cooldown.rememberChapter(mangaId = 1, chapterId = 10)

        assertEquals(
            listOf(chapters[1]),
            cooldown.eligibleChapters(
                candidates = chapters,
                releaseOnExhaustion = false,
                mangaId = Candidate::mangaId,
                chapterId = Candidate::chapterId,
            ),
        )
    }

    @Test
    fun `manga selection cools the whole manga without cooling its chapters`() {
        val cooldown = createCooldown()

        assertEquals(1L, cooldown.pickManga(listOf(1, 2)))
        assertEquals(2L, cooldown.pickManga(listOf(1, 2)))
        assertEquals(
            listOf(Candidate(1, 10)),
            cooldown.eligibleChapters(
                candidates = listOf(Candidate(1, 10)),
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
    fun `chapter pool only releases cooldown for an explicit retry`() {
        val cooldown = createCooldown()
        val chapter = Candidate(1, 10)
        cooldown.rememberChapter(chapter.mangaId, chapter.chapterId)

        assertEquals(
            emptyList<Candidate>(),
            cooldown.eligibleChapters(
                candidates = listOf(chapter),
                releaseOnExhaustion = false,
                mangaId = Candidate::mangaId,
                chapterId = Candidate::chapterId,
            ),
        )
        assertEquals(
            listOf(chapter),
            cooldown.eligibleChapters(
                candidates = listOf(chapter),
                releaseOnExhaustion = true,
                mangaId = Candidate::mangaId,
                chapterId = Candidate::chapterId,
            ),
        )
        assertFalse(cooldown.isChapterCoolingDown(chapter.mangaId, chapter.chapterId))
    }

    @Test
    fun `legacy chapter entries remain compatible`() {
        val preference = InMemoryPreferenceStore().getString("random_selection_cooldown")
        preference.set("""[{"mangaId":1,"chapterId":10,"at":0}]""")
        val cooldown = RandomSelectionCooldown(preference, now = { 0L }, randomIndex = { 0 })

        assertTrue(cooldown.isChapterCoolingDown(mangaId = 1, chapterId = 10))
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
    fun `empty pool stays empty`() {
        val cooldown = createCooldown()

        assertNull(cooldown.pickManga(emptyList()))
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
