package eu.kanade.tachiyomi.ui.manga

import androidx.lifecycle.viewModelScope
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.local.LocalEntryDeletionService
import eu.kanade.tachiyomi.data.manga.GoodDoujinStore
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetMangaWithChapters

@OptIn(ExperimentalCoroutinesApi::class)
class MangaMissingNavigationTest {

    @Test
    fun `missing row still navigates away after a hidden subscriber reconnects`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val libraryPreferences = mockk<LibraryPreferences>(relaxed = true)
        every { libraryPreferences.swipeToStartAction.get() } returns
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark
        every { libraryPreferences.swipeToEndAction.get() } returns LibraryPreferences.ChapterSwipeAction.ToggleRead
        every { libraryPreferences.autoUpdateMangaRestrictions.get() } returns emptySet()
        val trackPreferences = mockk<TrackPreferences>(relaxed = true)
        every { trackPreferences.autoUpdateTrackOnMarkRead.get() } returns AutoTrackState.NEVER
        val readerPreferences = mockk<ReaderPreferences>(relaxed = true)
        every { readerPreferences.skipFiltered.get() } returns false
        every { readerPreferences.skipFiltered.changes() } returns emptyFlow()
        val manga = mockk<GetMangaWithChapters>()
        coEvery { manga.awaitMangaOrNull(42) } returns null
        coEvery { manga.subscribe(42, true) } returns emptyFlow()
        val available = mockk<GetAvailableScanlators>()
        every { available.subscribe(42) } returns emptyFlow()
        val excluded = mockk<GetExcludedScanlators>()
        every { excluded.subscribe(42) } returns emptyFlow()
        val downloads = mockk<DownloadManager>()
        every { downloads.queueState } returns MutableStateFlow(emptyList())
        every { downloads.statusFlow() } returns emptyFlow()
        every { downloads.progressFlow() } returns emptyFlow()
        val downloadCache = mockk<DownloadCache>()
        every { downloadCache.changes } returns MutableSharedFlow()
        val marks = mockk<MangaMarkStore>()
        every { marks.marks } returns MutableStateFlow(emptyList())
        val goodDoujins = mockk<GoodDoujinStore>()
        every { goodDoujins.marks } returns MutableStateFlow(emptyList())
        val deletionService = mockk<LocalEntryDeletionService>()
        every { deletionService.lastReport } returns MutableStateFlow(null)
        val viewModel = MangaViewModel(
            context = mockk(),
            mangaId = 42,
            isFromSource = false,
            libraryPreferences = libraryPreferences,
            basePreferences = mockk(relaxed = true),
            trackPreferences = trackPreferences,
            readerPreferences = readerPreferences,
            trackerManager = mockk(),
            trackChapter = mockk(),
            downloadManager = downloads,
            downloadCache = downloadCache,
            getMangaAndChapters = manga,
            getDuplicateLibraryManga = mockk(),
            getAvailableScanlators = available,
            getExcludedScanlators = excluded,
            setExcludedScanlators = mockk(),
            setMangaChapterFlags = mockk(),
            setMangaDefaultChapterFlags = mockk(),
            setReadStatus = mockk(),
            updateChapter = mockk(),
            updateManga = mockk(),
            getCategories = mockk(),
            getTracks = mockk(),
            getHistory = mockk(),
            removeHistory = mockk(),
            addTracks = mockk(),
            setMangaCategories = mockk(),
            mangaRepository = mockk(),
            mangaMarkStore = marks,
            goodDoujinStore = goodDoujins,
            undoPill = mockk(),
            randomSelectionCooldown = mockk(),
            localRandomScope = mockk(),
            filterChaptersForDownload = mockk(),
            updateMangaFromRemote = mockk(),
            deletionService = deletionService,
        )
        try {
            withContext(Dispatchers.IO) { withTimeout(5_000) { viewModel.mangaMissing.first() } }
            advanceTimeBy(5_000)
            runCurrent()

            val replayed = withTimeout(5_000) { viewModel.mangaMissing.first() }

            assertEquals(Unit, replayed)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
