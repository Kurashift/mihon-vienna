package eu.kanade.tachiyomi.ui.library

import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChapterTranslatedNames
import tachiyomi.domain.chapter.model.ChapterTranslatedName
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySubscriptionTest {
    @Test
    fun `library has one database collector and releases it with the view model`() = withLibrary { fixture ->
        val state = fixture.awaitState { it.libraryData.favorites.size == 500 }
        assertEquals(500, state.displayedCategories.sumOf { state.getItemsForCategory(it).size })
        assertEquals(1, fixture.subscriptions.get())
        assertFalse(fixture.queryThreadIsMain)
    }

    @Test
    fun `progress and translated search update without restarting the library query`() = withLibrary { fixture ->
        fixture.awaitState { it.libraryData.favorites.size == 500 }
        fixture.rows.value = fixture.rows.value.map {
            if (it.id == 42L) it.copy(readCount = 3, bookmarkCount = 1, lastRead = 123) else it
        }
        fixture.awaitState { it.libraryData.favoritesById[42]?.libraryManga?.readCount == 3L }
        fixture.names.value = listOf(ChapterTranslatedName(42, "translated-chapter"))
        fixture.viewModel.search("translated-chapter")
        fixture.awaitState {
            it.appliedSearchQuery == "translated-chapter" &&
                it.libraryData.favorites.map { row -> row.id } == listOf(42L)
        }
        fixture.names.value = listOf(ChapterTranslatedName(43, "translated-chapter"))
        fixture.awaitState { it.libraryData.favorites.map { row -> row.id } == listOf(43L) }
        assertEquals(1, fixture.subscriptions.get())
    }

    @Test
    fun `membership changes reach rows and translation search`() = withLibrary { fixture ->
        fixture.awaitState { it.libraryData.favorites.size == 500 }
        val original = fixture.rows.value
        val changed = original.filterNot { it.id == 1L }.map {
            if (it.id == 2L) it.copy(manga = it.manga.copy(title = "renamed"), categories = listOf(1)) else it
        } + manga(4000)
        fixture.rows.value = changed
        fixture.awaitState {
            it.libraryData.favoritesById[1] == null && it.libraryData.favoritesById[4000] != null &&
                it.getItemsForCategoryId(1).any { row -> row.id == 2L }
        }
        fixture.names.value = listOf(ChapterTranslatedName(1, "new-entry"), ChapterTranslatedName(4000, "new-entry"))
        fixture.viewModel.search("new-entry")
        fixture.awaitState { it.libraryData.favorites.map { row -> row.id } == listOf(4000L) }
        fixture.viewModel.search(null)
        fixture.awaitState { it.appliedSearchQuery == null && it.libraryData.favorites.size == 500 }
        fixture.rows.value = emptyList()
        fixture.awaitState {
            it.libraryData.favorites.isEmpty() &&
                it.displayedCategories.all { category -> it.getItemsForCategory(category).isEmpty() }
        }
        fixture.rows.value = original
        fixture.awaitState { it.libraryData.favorites.size == 500 && it.libraryData.favoritesById[1] != null }
        assertEquals(1, fixture.subscriptions.get())
    }

    @Test
    fun `downloads and filters update without new subscriptions`() = withLibrary { fixture ->
        fixture.awaitState { it.libraryData.favorites.size == 500 }
        fixture.preferences.downloadBadge.set(true)
        fixture.downloads.set(9)
        fixture.cacheChanges.emit(Unit)
        fixture.awaitState {
            it.libraryData.favorites.all { row ->
                row.downloadCount == 9 &&
                    row.badges.downloadCount == 9
            }
        }
        fixture.preferences.filterUnread.set(TriState.ENABLED_NOT)
        fixture.rows.value = fixture.rows.value.map { if (it.id <= 201) it.copy(readCount = 6) else it }
        fixture.awaitState { it.libraryData.favorites.size == 201 && it.getItemsForCategoryId(0).size == 201 }
        fixture.preferences.filterUnread.set(TriState.DISABLED)
        fixture.awaitState { it.libraryData.favorites.size == 500 && it.getItemsForCategoryId(0).size == 500 }
        repeat(3) {
            fixture.viewModel.state.first()
        }
        assertEquals(1, fixture.subscriptions.get())
    }

    private fun withLibrary(block: suspend (Fixture) -> Unit) = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            val job = fixture.viewModel.viewModelScope.coroutineContext[Job]!!
            job.cancel()
            withTimeout(10_000) { job.join() }
            Dispatchers.resetMain()
        }
        assertEquals(0, fixture.activeSubscriptions.get())
    }

    private class Fixture {
        val rows = MutableStateFlow((1L..500L).map(::manga))
        val names = MutableStateFlow(emptyList<ChapterTranslatedName>())
        val cacheChanges = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val downloads = AtomicInteger(0)
        val subscriptions = AtomicInteger(0)
        val activeSubscriptions = AtomicInteger(0)

        @Volatile
        var queryThreadIsMain = false
        private val callerThread = Thread.currentThread()
        val preferences = mockk<LibraryPreferences>(relaxed = true).apply {
            every { downloadBadge } returns preference(false)
            every { unreadBadge } returns preference(true)
            every { localBadge } returns preference(true)
            every { languageBadge } returns preference(false)
            every { autoUpdateMangaRestrictions } returns preference(emptySet())
            every { filterDownloaded } returns preference(TriState.DISABLED)
            every { filterUnread } returns preference(TriState.DISABLED)
            every { filterStarted } returns preference(TriState.DISABLED)
            every { filterBookmarked } returns preference(TriState.DISABLED)
            every { filterCompleted } returns preference(TriState.DISABLED)
            every { filterIntervalCustom } returns preference(TriState.DISABLED)
            every { categoryTabs } returns preference(true)
            every { categoryNumberOfItems } returns preference(true)
            every { showContinueReadingButton } returns preference(false)
            every { lastUsedCategory } returns preference(0)
        }
        private val mangaRepository = mockk<MangaRepository> {
            every { getLibraryMangaAsFlow() } returns flow {
                subscriptions.incrementAndGet()
                activeSubscriptions.incrementAndGet()
                queryThreadIsMain = Thread.currentThread() == callerThread
                try {
                    emitAll(rows)
                } finally {
                    activeSubscriptions.decrementAndGet()
                }
            }
        }
        private val chapterRepository = mockk<ChapterRepository> {
            every { observeTranslatedNames(any()) } answers {
                val ids = firstArg<List<Long>>().toSet()
                names.map { entries -> entries.filter { it.mangaId in ids } }
            }
        }
        private val categories = mockk<GetCategories> {
            every { subscribe() } returns flowOf(
                listOf(
                    Category(0, "default", 0, LibrarySort.default.flag),
                    Category(1, "moved", 1, LibrarySort.default.flag),
                ),
            )
        }
        private val basePreferences = mockk<BasePreferences> {
            every { downloadedOnly } returns preference(false)
        }
        private val downloadManager = mockk<DownloadManager> {
            every { getDownloadCount(any()) } answers { downloads.get() }
        }.also { manager ->
            excludeRecords { manager.getDownloadCount(any()) }
        }
        private val cache = mockk<DownloadCache> {
            every { changes } returns cacheChanges
        }
        private val sources = mockk<SourceManager> {
            every { getOrStub(any()) } returns StubSource(1, "en", "source")
        }.also { manager ->
            excludeRecords { manager.getOrStub(any()) }
        }
        private val trackers = mockk<TrackerManager> {
            every { loggedInTrackersFlow() } returns flowOf(emptyList())
        }
        val viewModel = LibraryViewModel(
            context = mockk(),
            getLibraryManga = GetLibraryManga(mangaRepository),
            getCategories = categories,
            getTracksPerManga = mockk(relaxed = true),
            getNextChapters = mockk(),
            getBookmarkedChaptersByMangaId = mockk(),
            getChaptersByMangaId = mockk(),
            getChapterTranslatedNames = GetChapterTranslatedNames(chapterRepository),
            getHistory = mockk(),
            setReadStatus = mockk(),
            updateManga = mockk(),
            setMangaCategories = mockk(),
            preferences = basePreferences,
            libraryPreferences = preferences,
            coverCache = mockk(),
            sourceManager = sources,
            downloadManager = downloadManager,
            downloadCache = cache,
            trackerManager = trackers,
            randomSelectionCooldown = mockk(),
            undoPill = mockk(),
        )

        suspend fun awaitState(predicate: (LibraryViewModel.State) -> Boolean): LibraryViewModel.State =
            withTimeout(20_000) { viewModel.state.first { !it.isLoading && predicate(it) } }
    }

    companion object {
        private fun manga(id: Long) = LibraryManga(
            manga = Manga.create().copy(id = id, source = 1, title = "manga-$id", favorite = true),
            categories = listOf(0),
            totalChapters = 6,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )

        private inline fun <reified T> preference(initial: T): Preference<T> {
            val state = MutableStateFlow(initial)
            return mockk {
                every { get() } answers { state.value }
                every { changes() } returns state
                every { set(any()) } answers { state.value = firstArg() }
            }
        }
    }
}
