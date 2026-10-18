package eu.kanade.tachiyomi.data.audio

import eu.kanade.domain.base.BasePreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class AudioAccountSyncTest {

    @Test
    fun `account sync imports marked works without replacing local favourites`() = runTest {
        val api = mockk<KikoeruApi>()
        val preferences = authenticatedPreferences()
        val favoriteStore = mockk<AudioFavoriteStore>(relaxed = true)
        val markedWork = Work(id = 42, title = "Marked", progress = "marked")
        coEvery { api.fetchAccountWorks(null, 1) } returns WorksResponse(
            works = listOf(markedWork, Work(id = 7, title = "Listening", progress = "listening")),
            pagination = Pagination(currentPage = 1, pageSize = 100, totalCount = 2),
        )

        val sync = AudioAccountSync(api, preferences, favoriteStore)
        sync.synchronize()
        sync.synchronize()

        verify(exactly = 1) { favoriteStore.merge(listOf(markedWork)) }
        // One unfiltered pass carries every state, so a second synchronize() is a no-op and the
        // five per-state lists the website asks for are never requested.
        coVerify(exactly = 1) { api.fetchAccountWorks(null, 1) }
        assertEquals(AudioAccountProgress.MARKED, sync.progressOf(42))
        assertEquals(AudioAccountProgress.LISTENING, sync.progressOf(7))
        assertEquals(2, sync.works().size)
    }

    @Test
    fun `marking puts the work in the favourites list without waiting for a sync`() = runTest {
        val api = mockk<KikoeruApi>()
        coEvery { api.updateAccountProgress(42, AudioAccountProgress.MARKED) } returns Unit
        val sync = AudioAccountSync(api, authenticatedPreferences(), mockk(relaxed = true))

        assertTrue(sync.setMarked(Work(id = 42, title = "Fresh"), marked = true))

        // The list reads accountWorks, so a mark that only touched knownProgress stayed invisible
        // until the tab was reopened and had waited for a round trip.
        assertEquals(listOf(42L), sync.markedWorks().map { it.id })
        assertTrue(sync.isMarked(42))
    }

    @Test
    fun `bulk uncollect drops every row in one pass`() = runTest {
        val api = mockk<KikoeruApi>()
        coEvery { api.deleteAccountReview(any()) } returns Unit
        coEvery { api.fetchAccountWorks(null, 1) } returns WorksResponse(
            works = listOf(
                Work(id = 1, progress = "marked"),
                Work(id = 2, progress = "marked"),
                Work(id = 3, progress = "marked"),
            ),
            pagination = Pagination(currentPage = 1, pageSize = 100, totalCount = 3),
        )
        val sync = AudioAccountSync(api, authenticatedPreferences(), mockk(relaxed = true))
        sync.synchronize()
        val revisions = sync.revision.value

        assertEquals(2, sync.uncollectAll(listOf(1, 2)))

        coVerify(exactly = 1) { api.deleteAccountReview(1) }
        coVerify(exactly = 1) { api.deleteAccountReview(2) }
        coVerify(exactly = 0) { api.deleteAccountReview(3) }
        assertEquals(listOf(3L), sync.markedWorks().map { it.id })
        // One repaint for the whole batch: a bump per row is what made a bulk removal look like it
        // was working through the selection item by item.
        assertEquals(revisions + 1, sync.revision.value)
    }

    @Test
    fun `uncollecting deletes the account row instead of clearing its state`() = runTest {
        val api = mockk<KikoeruApi>()
        coEvery { api.deleteAccountReview(42) } returns Unit
        val sync = AudioAccountSync(api, authenticatedPreferences(), mockk(relaxed = true))

        assertTrue(sync.setMarked(Work(id = 42), marked = false))

        coVerify(exactly = 1) { api.deleteAccountReview(42) }
        coVerify(exactly = 0) { api.updateAccountProgress(any(), any()) }
        assertFalse(sync.isMarked(42))
    }

    @Test
    fun `wire values map to their state and anything else to none`() {
        assertEquals(AudioAccountProgress.MARKED, AudioAccountProgress.fromWire("marked"))
        assertEquals(AudioAccountProgress.POSTPONED, AudioAccountProgress.fromWire("postponed"))
        assertNull(AudioAccountProgress.fromWire(null))
        assertNull(AudioAccountProgress.fromWire(""))
        assertNull(AudioAccountProgress.fromWire("unknown"))
    }

    @Test
    fun `only collected rows are favourites`() = runTest {
        val api = mockk<KikoeruApi>()
        val favoriteStore = mockk<AudioFavoriteStore>(relaxed = true)
        val marked = Work(id = 1, title = "Marked", progress = "marked")
        coEvery { api.fetchAccountWorks(null, 1) } returns WorksResponse(
            works = listOf(
                marked,
                Work(id = 2, title = "Listening", progress = "listening"),
                Work(id = 3, title = "Rated only", progress = null),
            ),
            pagination = Pagination(currentPage = 1, pageSize = 100, totalCount = 3),
        )
        val sync = AudioAccountSync(api, authenticatedPreferences(), favoriteStore)

        sync.synchronize()

        // The account keeps a row for anything rated or played, so the tab cannot show every row.
        assertEquals(3, sync.works().size)
        assertEquals(listOf(1L), sync.markedWorks().map { it.id })
        verify(exactly = 1) { favoriteStore.merge(listOf(marked)) }
    }

    @Test
    fun `review progress body preserves work id and supports clearing state`() {
        val marked = Json.parseToJsonElement(
            buildAccountProgressBody(9, AudioAccountProgress.MARKED),
        ).jsonObject
        val cleared = Json.parseToJsonElement(buildAccountProgressBody(9, null)).jsonObject

        assertEquals(9, marked.getValue("work_id").jsonPrimitive.content.toInt())
        assertEquals("marked", marked.getValue("progress").jsonPrimitive.content)
        assertEquals("null", cleared.getValue("progress").toString())
    }

    @Test
    fun `account pagination stops at total count`() {
        val firstPage = WorksResponse(
            works = listOf(Work(id = 1)),
            pagination = Pagination(currentPage = 1, pageSize = 1, totalCount = 2),
        )
        val lastPage = WorksResponse(
            works = listOf(Work(id = 2)),
            pagination = Pagination(currentPage = 2, pageSize = 1, totalCount = 2),
        )

        assertTrue(hasNextAccountPage(firstPage, loadedCount = 1))
        assertFalse(hasNextAccountPage(lastPage, loadedCount = 2))
    }

    private fun authenticatedPreferences(): BasePreferences {
        val preferences = mockk<BasePreferences>()
        val token = mockk<Preference<String>>()
        val username = mockk<Preference<String>>()
        every { token.get() } returns "token"
        every { username.get() } returns "user"
        every { preferences.audioAuthToken } returns token
        every { preferences.audioUsername } returns username
        return preferences
    }
}
