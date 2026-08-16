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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class AudioAccountSyncTest {

    @Test
    fun `account sync imports marked works without replacing local favourites`() = runTest {
        val api = mockk<KikoeruApi>()
        val preferences = authenticatedPreferences()
        val favoriteStore = mockk<AudioFavoriteStore>(relaxed = true)
        val markedWork = Work(id = 42, title = "Marked")
        coEvery { api.fetchAccountWorks(any(), 1) } answers {
            val works = if (firstArg<AudioAccountProgress>() == AudioAccountProgress.MARKED) {
                listOf(markedWork)
            } else {
                emptyList()
            }
            WorksResponse(
                works = works,
                pagination = Pagination(currentPage = 1, pageSize = 12, totalCount = works.size),
            )
        }

        val sync = AudioAccountSync(api, preferences, favoriteStore)
        sync.synchronize()
        sync.synchronize()

        verify(exactly = 1) { favoriteStore.merge(listOf(markedWork)) }
        coVerify(exactly = AudioAccountProgress.entries.size) { api.fetchAccountWorks(any(), 1) }
    }

    @Test
    fun `progress updates are deduplicated for the same account`() = runTest {
        val api = mockk<KikoeruApi>()
        coEvery { api.updateAccountProgress(any(), any()) } returns Unit
        val sync = AudioAccountSync(api, authenticatedPreferences(), mockk(relaxed = true))

        sync.updateProgress(7, AudioAccountProgress.LISTENING)
        sync.updateProgress(7, AudioAccountProgress.LISTENING)
        sync.updateProgress(7, AudioAccountProgress.LISTENED)

        coVerify(exactly = 1) { api.updateAccountProgress(7, AudioAccountProgress.LISTENING) }
        coVerify(exactly = 1) { api.updateAccountProgress(7, AudioAccountProgress.LISTENED) }
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
