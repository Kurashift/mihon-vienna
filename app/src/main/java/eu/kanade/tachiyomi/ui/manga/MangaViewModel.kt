package eu.kanade.tachiyomi.ui.manga

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.util.fastAny
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.local.LocalEntryDeletionService
import eu.kanade.tachiyomi.data.manga.GoodDoujinStore
import eu.kanade.tachiyomi.data.manga.MangaMark
import eu.kanade.tachiyomi.data.manga.RandomSelectionCooldown
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.source.browse.LocalReadingFilter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.chapter.getFirstChapter
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.showSnackbarReplacing
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.viewmodel.StateViewModel
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.chapter.service.mergeVisibleChapterOrder
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaViewModel(
    private val context: Context,
    private val mangaId: Long,
    val randomCandidates: List<Long> = emptyList(),
    private val isFromSource: Boolean,
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    trackPreferences: TrackPreferences = Injekt.get(),
    readerPreferences: ReaderPreferences = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val trackChapter: TrackChapter = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val getMangaAndChapters: GetMangaWithChapters = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getAvailableScanlators: GetAvailableScanlators = Injekt.get(),
    private val getExcludedScanlators: GetExcludedScanlators = Injekt.get(),
    private val setExcludedScanlators: SetExcludedScanlators = Injekt.get(),
    private val setMangaChapterFlags: SetMangaChapterFlags = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
    private val removeHistory: RemoveHistory = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val goodDoujinStore: GoodDoujinStore = Injekt.get(),
    private val randomSelectionCooldown: RandomSelectionCooldown = Injekt.get(),
    private val filterChaptersForDownload: FilterChaptersForDownload = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
    private val deletionService: LocalEntryDeletionService = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateViewModel<MangaViewModel.State>(State.Loading) {

    private val _deleteCompleted = MutableSharedFlow<DeleteCompleted>(extraBufferCapacity = 1)
    val deleteCompleted: Flow<DeleteCompleted> = _deleteCompleted.asSharedFlow()

    /** Emitted when the row behind [mangaId] is already gone, so the screen can go back. */
    private val _mangaMissing = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val mangaMissing: Flow<Unit> = _mangaMissing.asSharedFlow()

    /** Outcome of a local file deletion, surfaced once so the screen can report and navigate. */
    data class DeleteCompleted(
        val deleted: Int,
        val failed: Int,
        val mangaDeleted: Boolean,
    )

    companion object {
        val MANGA_ID_KEY = CreationExtras.Key<Long>()

        val IS_FROM_SOURCE_KEY = CreationExtras.Key<Boolean>()

        val RANDOM_CANDIDATES_KEY = CreationExtras.Key<List<Long>>()

        val Factory = viewModelFactory {
            initializer {
                MangaViewModel(
                    context = Injekt.get<Application>(),
                    mangaId = get(MANGA_ID_KEY)!!,
                    isFromSource = get(IS_FROM_SOURCE_KEY)!!,
                    randomCandidates = get(RANDOM_CANDIDATES_KEY) ?: emptyList(),
                )
            }
        }
    }

    private val successState: State.Success?
        get() = state.value as? State.Success

    /**
     * Reads the row this screen is built on. A row that is gone is reported through
     * [mangaMissing] so the screen leaves instead of waiting for data that cannot arrive;
     * the manga flow itself never completes for a missing row.
     */
    private suspend fun awaitMangaOrReportMissing(id: Long): Manga? {
        val manga = getMangaAndChapters.awaitMangaOrNull(id)
        if (manga == null) _mangaMissing.tryEmit(Unit)
        return manga
    }

    val manga: Manga?
        get() = successState?.manga

    val source: Source?
        get() = successState?.source

    private val isFavorited: Boolean
        get() = manga?.favorite ?: false

    private val allChapters: List<ChapterList.Item>?
        get() = successState?.chapters

    private val filteredChapters: List<ChapterList.Item>?
        get() = successState?.processedChapters

    private val chapterReorderMutex = Mutex()
    val chapterSwipeStartAction = libraryPreferences.swipeToEndAction.get()
    val chapterSwipeEndAction = libraryPreferences.swipeToStartAction.get()
    var autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead.get()

    private val skipFiltered by readerPreferences.skipFiltered.asState(viewModelScope)

    val isUpdateIntervalEnabled =
        LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateMangaRestrictions.get()

    private val selectedPositions: Array<Int> = arrayOf(-1, -1) // first and last selected index in list
    private val selectedChapterIds: HashSet<Long> = HashSet()

    /**
     * Helper function to update the UI state only if it's currently in success state
     */
    private inline fun updateSuccessState(func: (State.Success) -> State.Success) {
        mutableState.update {
            when (it) {
                State.Loading -> it
                is State.Success -> func(it)
            }
        }
    }

    init {
        viewModelScope.launchIO {
            goodDoujinStore.marks
                .map { marks ->
                    marks.filterTo(mutableSetOf()) { it.mangaId == mangaId }.mapTo(mutableSetOf()) { it.chapterId }
                }
                .distinctUntilChanged()
                .collectLatest { chapterIds ->
                    updateSuccessState { it.copy(goodDoujinChapterIds = chapterIds) }
                }
        }

        viewModelScope.launchIO {
            combine(
                getMangaAndChapters.subscribe(mangaId, applyScanlatorFilter = true).distinctUntilChanged(),
                downloadCache.changes,
                downloadManager.queueState,
            ) { mangaAndChapters, _, _ -> mangaAndChapters }
                .collectLatest { (manga, chapters) ->
                    updateSuccessState {
                        it.copy(
                            manga = manga,
                            chapters = chapters.toChapterListItems(manga),
                        )
                    }
                }
        }

        viewModelScope.launchIO {
            getExcludedScanlators.subscribe(mangaId)
                .distinctUntilChanged()
                .collectLatest { excludedScanlators ->
                    updateSuccessState {
                        it.copy(excludedScanlators = excludedScanlators)
                    }
                }
        }

        viewModelScope.launchIO {
            getAvailableScanlators.subscribe(mangaId)
                .distinctUntilChanged()
                .collectLatest { availableScanlators ->
                    updateSuccessState {
                        it.copy(availableScanlators = availableScanlators)
                    }
                }
        }

        observeDownloads()

        viewModelScope.launchIO {
            // A local cleanup can drop the row between opening this entry and reading it here.
            val initialManga = getMangaAndChapters.awaitMangaOrNull(mangaId)
            if (initialManga == null) {
                _mangaMissing.tryEmit(Unit)
                return@launchIO
            }
            var manga = initialManga
            val isLocalManga = manga.isLocal()
            val needRefreshInfo = !manga.initialized && !isLocalManga

            val flagsChanged = if (!manga.favorite && !manga.initialized && manga.chapterFlags == 0L) {
                // A newly-created browse entry has no saved chapter settings yet. Existing
                // entries keep their own flags, including a manual order chosen before favoriting.
                setMangaDefaultChapterFlags.awaitIfChanged(manga)
            } else if (!manga.initialized && !manga.isLocal() && manga.sorting == Manga.CHAPTER_SORTING_SOURCE) {
                // Migrate untouched cloud manga to the cloud default once, on first open only,
                // so a user who later chooses "sort by source" isn't silently reset on every visit.
                setMangaChapterFlags.awaitSetSortingAndDirection(
                    manga,
                    libraryPreferences.sortCloudChapterBySourceOrNumber.get(),
                    libraryPreferences.sortCloudChapterByAscendingOrDescending.get(),
                )
            } else {
                false
            }

            if (flagsChanged) {
                manga = awaitMangaOrReportMissing(mangaId) ?: return@launchIO
            }

            // The translated-title shell is a local-library display preference, not per-manga
            // content. Keep every local detail page on the same mode without eagerly rewriting
            // the whole library when the user changes it.
            if (isLocalManga && setMangaDefaultChapterFlags.awaitDisplayModeIfChanged(manga)) {
                manga = awaitMangaOrReportMissing(mangaId) ?: return@launchIO
            }

            val chapters = getMangaAndChapters.awaitChapters(mangaId, applyScanlatorFilter = true)
                .toChapterListItems(manga)
            // Cloud manga retain their existing first-load behavior. Local manga use a cheap
            // filename-only check below and run a full sync only when that folder actually changed.
            val needRefreshChapter = chapters.isEmpty() && !isLocalManga

            // Show what we have earlier
            mutableState.update {
                State.Success(
                    manga = manga,
                    source = Injekt.get<SourceManager>().getOrStub(manga.source),
                    isFromSource = isFromSource,
                    chapters = chapters,
                    goodDoujinChapterIds = goodDoujinStore.marks.value
                        .filterTo(mutableSetOf()) { it.mangaId == mangaId }
                        .mapTo(mutableSetOf()) { it.chapterId },
                    availableScanlators = getAvailableScanlators.await(mangaId),
                    excludedScanlators = getExcludedScanlators.await(mangaId),
                    isRefreshingData = needRefreshInfo || needRefreshChapter,
                    dialog = null,
                )
            }

            // Start observe tracking since it only needs mangaId
            observeTrackers()

            if (isLocalManga && viewModelScope.isActive) {
                val localSource = Injekt.get<SourceManager>().getOrStub(manga.source) as? LocalSource
                val allChapterUrls = getMangaAndChapters
                    .awaitChapters(mangaId, applyScanlatorFilter = false)
                    .map(Chapter::url)
                val localChapterFilesChanged = localSource?.hasChapterFileChanges(
                    mangaUrl = manga.url,
                    existingChapterUrls = allChapterUrls,
                ) == true
                if (localChapterFilesChanged) {
                    fetchAllFromSource(
                        manualFetch = false,
                        fetchDetails = false,
                        fetchChapters = true,
                    )
                }
            } else if ((needRefreshInfo || needRefreshChapter) && viewModelScope.isActive) {
                fetchAllFromSource(
                    manualFetch = false,
                    fetchDetails = needRefreshInfo,
                    fetchChapters = needRefreshChapter,
                )
            }

            // Initial loading finished
            updateSuccessState { it.copy(isRefreshingData = false) }
        }
    }

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        viewModelScope.launch {
            updateSuccessState { it.copy(isRefreshingData = true) }
            try {
                fetchAllFromSource(
                    manualFetch = manualFetch,
                    fetchDetails = true,
                    fetchChapters = true,
                )
            } finally {
                updateSuccessState { it.copy(isRefreshingData = false) }
            }
        }
    }

    private suspend fun fetchAllFromSource(
        manualFetch: Boolean,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ) {
        val state = successState ?: return
        try {
            withUIContext {
                val update = updateMangaFromRemote(
                    source = state.source,
                    manga = state.manga,
                    fetchDetails = fetchDetails,
                    fetchChapters = fetchChapters,
                    manualFetch = manualFetch,
                )
                    .getOrThrow()

                if (manualFetch) {
                    downloadNewChapters(update.newChapters)
                }
            }
        } catch (_: CancellationException) {
            // ignore
        } catch (e: Exception) {
            val message = if (e is NoChaptersException) {
                context.stringResource(MR.strings.no_chapters_error)
            } else {
                logcat(LogPriority.ERROR, e)
                with(context) { e.formattedMessage }
            }

            viewModelScope.launch {
                snackbarHostState.showSnackbarReplacing(message = message)
            }
        }
    }

    // Manga info - start

    fun toggleFavorite() {
        toggleFavorite(
            onRemoved = {
                viewModelScope.launch {
                    if (!hasDownloads()) return@launch
                    val result = snackbarHostState.showSnackbarReplacing(
                        message = context.stringResource(MR.strings.delete_downloads_for_manga),
                        actionLabel = context.stringResource(MR.strings.action_delete),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        deleteDownloads()
                    }
                }
            },
        )
    }

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     */
    fun toggleFavorite(
        onRemoved: () -> Unit,
        checkDuplicate: Boolean = true,
    ) {
        val state = successState ?: return
        viewModelScope.launchIO {
            val manga = state.manga

            if (isFavorited) {
                // Remove from library
                if (updateManga.awaitUpdateFavorite(manga.id, false)) {
                    // Remove covers and update last modified in db
                    if (manga.removeCovers() != manga) {
                        updateManga.awaitUpdateCoverLastModified(manga.id)
                    }
                    withUIContext { onRemoved() }
                }
            } else {
                // Add to library
                // First, check if duplicate exists if callback is provided
                if (checkDuplicate) {
                    val duplicates = getDuplicateLibraryManga(manga)

                    if (duplicates.isNotEmpty()) {
                        updateSuccessState { it.copy(dialog = Dialog.DuplicateManga(manga, duplicates)) }
                        return@launchIO
                    }
                }

                // Now check if user previously set categories, when available
                val categories = getCategories()
                val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
                val defaultCategory = categories.find { it.id == defaultCategoryId }
                when {
                    // Default category set
                    defaultCategory != null -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launchIO
                        moveMangaToCategory(defaultCategory)
                    }

                    // Automatic 'Default' or no categories
                    defaultCategoryId == 0L || categories.isEmpty() -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launchIO
                        moveMangaToCategory(null)
                    }

                    // Choose a category
                    else -> showChangeCategoryDialog()
                }

                // Finally match with enhanced tracking when available
                addTracks.bindEnhancedTrackers(manga, state.source)
            }
        }
    }

    fun showChangeCategoryDialog() {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            val categories = getCategories()
            val selection = getMangaCategoryIds(manga)
            updateSuccessState { successState ->
                successState.copy(
                    dialog = Dialog.ChangeCategory(
                        manga = manga,
                        initialSelection = categories.mapAsCheckboxState { it.id in selection },
                    ),
                )
            }
        }
    }

    fun showSetFetchIntervalDialog() {
        val manga = successState?.manga ?: return
        updateSuccessState {
            it.copy(dialog = Dialog.SetFetchInterval(manga))
        }
    }

    fun setFetchInterval(manga: Manga, interval: Int) {
        viewModelScope.launchIO {
            if (
                updateManga.awaitUpdateFetchInterval(
                    // Custom intervals are negative
                    manga.copy(fetchInterval = -interval),
                )
            ) {
                val updatedManga = awaitMangaOrReportMissing(manga.id) ?: return@launchIO
                updateSuccessState { it.copy(manga = updatedManga) }
            }
        }
    }

    /**
     * Returns true if the manga has any downloads.
     */
    private fun hasDownloads(): Boolean {
        val manga = successState?.manga ?: return false
        return downloadManager.getDownloadCount(manga) > 0
    }

    /**
     * Deletes all the downloads for the manga.
     */
    private fun deleteDownloads() {
        val state = successState ?: return
        downloadManager.deleteManga(state.manga, state.source)
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return getCategories.await(manga.id)
            .map { it.id }
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        moveMangaToCategory(categories)
        if (manga.favorite) return

        viewModelScope.launchIO {
            updateManga.awaitUpdateFavorite(manga.id, true)
        }
    }

    /**
     * Move the given manga to categories.
     *
     * @param categories the selected categories.
     */
    private fun moveMangaToCategories(categories: List<Category>) {
        val categoryIds = categories.map { it.id }
        moveMangaToCategory(categoryIds)
    }

    private fun moveMangaToCategory(categoryIds: List<Long>) {
        viewModelScope.launchIO {
            setMangaCategories.await(mangaId, categoryIds)
        }
    }

    /**
     * Move the given manga to the category.
     *
     * @param category the selected category, or null for default category.
     */
    private fun moveMangaToCategory(category: Category?) {
        moveMangaToCategories(listOfNotNull(category))
    }

    // Manga info - end

    // Chapters list - start

    private fun observeDownloads() {
        viewModelScope.launchIO {
            downloadManager.statusFlow()
                .filter { it.manga.id == successState?.manga?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }

        viewModelScope.launchIO {
            downloadManager.progressFlow()
                .filter { it.manga.id == successState?.manga?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }
    }

    private fun updateDownloadState(download: Download) {
        updateSuccessState { successState ->
            val modifiedIndex = successState.chapters.indexOfFirst { it.id == download.chapter.id }
            if (modifiedIndex < 0) return@updateSuccessState successState

            val newChapters = successState.chapters.toMutableList().apply {
                val item = removeAt(modifiedIndex)
                    .copy(downloadState = download.status, downloadProgress = download.progress)
                add(modifiedIndex, item)
            }
            successState.copy(chapters = newChapters)
        }
    }

    private fun List<Chapter>.toChapterListItems(manga: Manga): List<ChapterList.Item> {
        val isLocal = manga.isLocal()
        return map { chapter ->
            val activeDownload = if (isLocal) {
                null
            } else {
                downloadManager.getQueuedDownloadOrNull(chapter.id)
            }
            val downloaded = if (isLocal) {
                true
            } else {
                downloadManager.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    manga.title,
                    manga.source,
                )
            }
            val downloadState = when {
                activeDownload != null -> activeDownload.status
                downloaded -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }

            ChapterList.Item(
                chapter = chapter,
                downloadState = downloadState,
                downloadProgress = activeDownload?.progress ?: 0,
                selected = chapter.id in selectedChapterIds,
            )
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    fun chapterSwipe(chapterItem: ChapterList.Item, swipeAction: LibraryPreferences.ChapterSwipeAction) {
        viewModelScope.launch {
            executeChapterSwipeAction(chapterItem, swipeAction)
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    private fun executeChapterSwipeAction(
        chapterItem: ChapterList.Item,
        swipeAction: LibraryPreferences.ChapterSwipeAction,
    ) {
        val chapter = chapterItem.chapter
        when (swipeAction) {
            // A swipe acts on the row under the finger, not on the selection, so it leaves the
            // selection and its bottom bar alone. Only the bar's own buttons dismiss it.
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                markChaptersRead(listOf(chapter), !chapter.read, clearSelection = false)
            }
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                bookmarkChapters(listOf(chapter), !chapter.bookmark, clearSelection = false)
            }
            LibraryPreferences.ChapterSwipeAction.AddToGoodDoujin -> toggleGoodDoujin(chapter)
            LibraryPreferences.ChapterSwipeAction.Download -> {
                val downloadAction: ChapterDownloadAction = when (chapterItem.downloadState) {
                    Download.State.ERROR,
                    Download.State.NOT_DOWNLOADED,
                    -> ChapterDownloadAction.START_NOW
                    Download.State.QUEUE,
                    Download.State.DOWNLOADING,
                    -> ChapterDownloadAction.CANCEL
                    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                }
                runChapterDownloadActions(
                    items = listOf(chapterItem),
                    action = downloadAction,
                )
            }
            LibraryPreferences.ChapterSwipeAction.Disabled -> throw IllegalStateException()
        }
    }

    /**
     * Swipe action: add or remove the chapter from the good doujin list.
     */
    private fun toggleGoodDoujin(chapter: Chapter) {
        val state = successState ?: return
        val manga = state.manga
        if (!manga.isLocal()) return
        viewModelScope.launch {
            val added = goodDoujinStore.toggle(
                MangaMark(
                    mangaId = manga.id,
                    mangaTitle = manga.title,
                    chapterId = chapter.id,
                    chapterName = chapter.name,
                    markedAt = System.currentTimeMillis(),
                ),
            )
            snackbarHostState.showSnackbarReplacing(
                context.stringResource(
                    if (added) MR.strings.good_doujin_added else MR.strings.good_doujin_removed,
                ),
            )
        }
    }

    /**
     * Returns the chapter to continue reading: the next unread chapter, or - when everything
     * has been read - the first chapter so "continue" restarts from the beginning.
     */
    suspend fun getNextUnreadChapter(): Chapter? {
        val successState = successState ?: return null
        val history = getHistory.await(successState.manga.id)
        return successState.chapters.getNextUnread(
            successState.manga,
            history,
        )
            ?: successState.chapters.getFirstChapter(successState.manga)
    }

    /**
     * Picks a random local manga (preferring one different from the current manga) and
     * returns its id, or null when the current manga isn't local or there's nothing to pick.
     */
    suspend fun getRandomLocalMangaId(): Long? {
        val state = successState ?: return null
        val manga = state.manga
        if (!manga.isLocal()) return null

        // Prefer the filtered list the user came from (local source browse page), so
        // random keeps opening manga within the same search/filter result.
        if (randomCandidates.isNotEmpty()) {
            randomSelectionCooldown.pickManga(randomCandidates, manga.id)?.let { return it }
        }

        // Fallback: pick from the whole local library (e.g. opened from home/updates), where
        // there is no browse list to inherit. Query the database directly instead of rescanning
        // the archive folders so the button stays responsive even with hundreds of local series.
        //
        // The reading filter is still honoured so "Unread" stays unread whichever screen the
        // user started from. Only rows with chapters come back, so a manga whose files were
        // deleted leaves no row and cannot be opened into an empty details page.
        val ids = withIOContext {
            runCatching { mangaRepository.getMangaProgressBySource(LocalSource.ID) }
                .getOrDefault(emptyList())
                .let { progress ->
                    LocalReadingFilter.randomPickIds(
                        progress,
                        LocalReadingFilter.read(LocalSource.ID),
                    )
                }
        }
        return randomSelectionCooldown.pickManga(ids, manga.id)
    }

    internal suspend fun getRandomGoodDoujinManga(): RandomGoodDoujinResult {
        val currentMangaId = successState?.manga?.id ?: return RandomGoodDoujinResult(false, null)
        val markedMangaIds = goodDoujinStore.marks.value
            .map { it.mangaId }
            .distinct()
        return RandomGoodDoujinResult(
            hasEntries = markedMangaIds.isNotEmpty(),
            mangaId = randomSelectionCooldown.pickManga(markedMangaIds, currentMangaId),
        )
    }

    private fun getUnreadChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> !chapter.read && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun getUnreadChaptersSorted(): List<Chapter> {
        val manga = successState?.manga ?: return emptyList()
        val chaptersSorted = getUnreadChapters().sortedWith(getChapterSort(manga))
        return if (manga.sortDescending()) chaptersSorted.reversed() else chaptersSorted
    }

    private fun getBookmarkedChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> chapter.bookmark && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun startDownload(
        chapters: List<Chapter>,
        startNow: Boolean,
    ) {
        val successState = successState ?: return

        viewModelScope.launchNonCancellable {
            if (startNow) {
                val chapterId = chapters.singleOrNull()?.id ?: return@launchNonCancellable
                downloadManager.startDownloadNow(chapterId)
            } else {
                downloadChapters(chapters)
            }

            if (!isFavorited && !successState.hasPromptedToAddBefore) {
                updateSuccessState { state ->
                    state.copy(hasPromptedToAddBefore = true)
                }
                val result = snackbarHostState.showSnackbarReplacing(
                    message = context.stringResource(MR.strings.snack_add_to_library),
                    actionLabel = context.stringResource(MR.strings.action_add),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed && !isFavorited) {
                    toggleFavorite()
                }
            }
        }
    }

    fun runChapterDownloadActions(
        items: List<ChapterList.Item>,
        action: ChapterDownloadAction,
    ) {
        when (action) {
            ChapterDownloadAction.START -> {
                startDownload(items.map { it.chapter }, false)
                if (items.any { it.downloadState == Download.State.ERROR }) {
                    downloadManager.startDownloads()
                }
            }
            ChapterDownloadAction.START_NOW -> {
                val chapter = items.singleOrNull()?.chapter ?: return
                startDownload(listOf(chapter), true)
            }
            ChapterDownloadAction.CANCEL -> {
                val chapterId = items.singleOrNull()?.id ?: return
                cancelDownload(chapterId)
            }
            ChapterDownloadAction.DELETE -> {
                deleteChapters(items.map { it.chapter })
            }
        }
    }

    fun runDownloadAction(action: DownloadAction) {
        val chaptersToDownload = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> getUnreadChaptersSorted().take(1)
            DownloadAction.NEXT_5_CHAPTERS -> getUnreadChaptersSorted().take(5)
            DownloadAction.NEXT_10_CHAPTERS -> getUnreadChaptersSorted().take(10)
            DownloadAction.NEXT_25_CHAPTERS -> getUnreadChaptersSorted().take(25)
            DownloadAction.UNREAD_CHAPTERS -> getUnreadChapters()
            DownloadAction.BOOKMARKED_CHAPTERS -> getBookmarkedChapters()
        }
        if (chaptersToDownload.isNotEmpty()) {
            startDownload(chaptersToDownload, false)
        }
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        downloadManager.cancelQueuedDownloads(listOf(activeDownload))
        updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
    }

    // The before/after read entries used to live here and rebuild the chapter order themselves.
    // They reversed the list whenever the manga carried the descending flag, which is a bit that
    // has nothing to do with the sort basis: a manual custom order ignores it, so those manga
    // ended up with both ranges backwards. The entries now cut the already-ordered list where it
    // is displayed, in MangaScreen, and hand the result to markChaptersRead below.

    /**
     * Persists a manual chapter order after the user drags chapters on the detail page.
     * Switches the manga to the custom chapter sort and rewrites each listed chapter's
     * custom_order so the new order survives reloads.
     */
    fun reorderChapters(orderedIds: List<Long>) {
        if (orderedIds.isEmpty()) return
        if (successState == null) return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(Dispatchers.IO + NonCancellable) {
                chapterReorderMutex.withLock {
                    val manga = getMangaAndChapters.awaitMangaOrNull(mangaId)
                        ?: return@withContext
                    val currentIds = getMangaAndChapters.awaitChapters(mangaId, applyScanlatorFilter = false)
                        .sortedWith(getChapterSort(manga))
                        .map { it.id }
                    val finalIds = mergeVisibleChapterOrder(currentIds, orderedIds)

                    // Save the selected mode before rewriting every chapter position. On large
                    // local series this prevents leaving the screen mid-save from retaining the
                    // previous alphabetical/source sort mode.
                    if (manga.sorting != Manga.CHAPTER_SORTING_CUSTOM || manga.sortDescending()) {
                        setMangaChapterFlags.awaitSetSortingAndDirection(
                            manga,
                            Manga.CHAPTER_SORTING_CUSTOM,
                            Manga.CHAPTER_SORT_ASC,
                        )
                    }
                    updateChapter.awaitAll(
                        finalIds.mapIndexed { index, id ->
                            ChapterUpdate(id = id, customOrder = (index + 1).toLong())
                        },
                    )
                }
            }
        }
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param chapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     * @param clearSelection whether the selection is over once the change is applied. The
     * bottom bar drops the selection after its own action; a swipe on a single row does not.
     */
    fun markChaptersRead(chapters: List<Chapter>, read: Boolean, clearSelection: Boolean = true) {
        if (clearSelection) toggleAllSelection(false)
        if (chapters.isEmpty()) return
        viewModelScope.launchIO {
            setReadStatus.await(
                read = read,
                chapters = chapters.toTypedArray(),
            )

            if (!read || successState?.hasLoggedInTrackers == false || autoTrackState == AutoTrackState.NEVER) {
                return@launchIO
            }

            refreshTrackers()

            val tracks = getTracks.await(mangaId)
            val maxChapterNumber = chapters.maxOf { it.chapterNumber }
            val shouldPromptTrackingUpdate = tracks.any { track -> maxChapterNumber > track.lastChapterRead }

            if (!shouldPromptTrackingUpdate) return@launchIO
            if (autoTrackState == AutoTrackState.ALWAYS) {
                trackChapter.await(context, mangaId, maxChapterNumber)
                withUIContext {
                    context.toast(context.stringResource(MR.strings.trackers_updated_summary, maxChapterNumber.toInt()))
                }
                return@launchIO
            }

            val result = snackbarHostState.showSnackbarReplacing(
                message = context.stringResource(MR.strings.confirm_tracker_update, maxChapterNumber.toInt()),
                actionLabel = context.stringResource(MR.strings.action_ok),
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )

            if (result == SnackbarResult.ActionPerformed) {
                trackChapter.await(context, mangaId, maxChapterNumber)
            }
        }
    }

    private suspend fun refreshTrackers(
        refreshTracks: RefreshTracks = Injekt.get(),
    ) {
        refreshTracks.await(mangaId)
            .filter { it.first != null }
            .forEach { (track, e) ->
                logcat(LogPriority.ERROR, e) {
                    "Failed to refresh track data mangaId=$mangaId for service ${track!!.id}"
                }
                withUIContext {
                    context.toast(
                        context.stringResource(
                            MR.strings.track_error,
                            track!!.name,
                            e.message ?: "",
                        ),
                    )
                }
            }
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapters the list of chapters to download.
     */
    private fun downloadChapters(chapters: List<Chapter>) {
        val manga = successState?.manga ?: return
        downloadManager.downloadChapters(manga, chapters)
        toggleAllSelection(false)
    }

    fun setGoodDoujinChapters(chapters: List<Chapter>, marked: Boolean) {
        val manga = successState?.manga ?: return
        if (!manga.isLocal()) return
        val marks = chapters.map { chapter ->
            MangaMark(
                mangaId = manga.id,
                mangaTitle = manga.title,
                chapterId = chapter.id,
                chapterName = chapter.name,
                markedAt = System.currentTimeMillis(),
            )
        }
        viewModelScope.launch {
            goodDoujinStore.setAll(marks, marked)
            toggleAllSelection(false)
        }
    }

    /**
     * Deletes the given list of chapter.
     *
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<Chapter>) {
        viewModelScope.launchNonCancellable {
            try {
                successState?.let { state ->
                    downloadManager.deleteChapters(
                        chapters,
                        state.manga,
                        state.source,
                    )
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Erases the local files behind the given chapters and drops their database rows.
     */
    fun deleteLocalChapters(chapters: List<Chapter>) {
        val state = successState ?: return
        viewModelScope.launchNonCancellable {
            dismissDialog()
            val entries = chapters.map { chapter ->
                LocalEntryDeletionService.ChapterTarget(
                    id = chapter.id,
                    mangaId = state.manga.id,
                    mangaTitle = state.manga.title,
                    name = chapter.name,
                )
            }
            val result = deletionService.deleteChapters(entries)
            toggleAllSelection(false)
            _deleteCompleted.emit(
                DeleteCompleted(
                    deleted = result.deleted,
                    failed = result.failed.size,
                    // Deleting every chapter empties the directory, which takes the manga row
                    // with it. The screen has nothing left to show then, so it has to leave.
                    mangaDeleted = state.manga.id in result.deletedMangaIds,
                ),
            )
        }
    }

    /**
     * Erases the whole manga directory. The screen is expected to leave afterwards since the
     * manga no longer exists.
     */
    fun deleteLocalManga() {
        val state = successState ?: return
        viewModelScope.launchNonCancellable {
            dismissDialog()
            val result = deletionService.deleteManga(
                LocalEntryDeletionService.MangaEntry(
                    id = state.manga.id,
                    url = state.manga.url,
                    title = state.manga.title,
                    manga = state.manga,
                ),
            )
            _deleteCompleted.emit(
                DeleteCompleted(
                    deleted = result.deleted,
                    failed = result.failed.size,
                    mangaDeleted = state.manga.id in result.deletedMangaIds,
                ),
            )
        }
    }

    private fun downloadNewChapters(chapters: List<Chapter>) {
        viewModelScope.launchNonCancellable {
            val manga = successState?.manga ?: return@launchNonCancellable
            val chaptersToDownload = filterChaptersForDownload.await(manga, chapters)

            if (chaptersToDownload.isNotEmpty()) {
                downloadChapters(chaptersToDownload)
            }
        }
    }

    /**
     * Sets the read filter and requests an UI update.
     * @param state whether to display only unread chapters or all chapters.
     */
    fun setUnreadFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_UNREAD
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_READ
        }
        viewModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetUnreadFilter(manga, flag)
        }
    }

    /**
     * Sets the download filter and requests an UI update.
     * @param state whether to display only downloaded chapters or all chapters.
     */
    fun setDownloadedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
        }

        viewModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetDownloadedFilter(manga, flag)
        }
    }

    fun setBookmarkedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
        }

        viewModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetBookmarkFilter(manga, flag)
        }
    }

    /**
     * Sets the active display mode.
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Long) {
        val manga = successState?.manga ?: return

        viewModelScope.launchNonCancellable {
            if (manga.isLocal()) {
                libraryPreferences.localChapterDisplayMode.set(mode)
            }
            setMangaChapterFlags.awaitSetDisplayMode(manga, mode)
        }
    }

    /**
     * Sets the sorting method and requests an UI update.
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Long) {
        val manga = successState?.manga ?: return

        viewModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, sort)
        }
    }

    fun exportChapterTitles(uri: Uri, format: ChapterTitleTranslationFormat, onlyUntranslated: Boolean) {
        val state = successState ?: return
        val content = ChapterTitleTranslationCodec.encode(
            manga = state.manga,
            chapters = state.chapters.map { it.chapter },
            format = format,
            onlyUntranslated = onlyUntranslated,
            exportInstanceId = basePreferences.installationId.get().takeIf(String::isNotBlank),
        )
        viewModelScope.launchIO {
            runCatching {
                context.contentResolver.openOutputStream(uri, "wt")
                    ?.bufferedWriter(Charsets.UTF_8)
                    ?.use { it.write(content) }
                    ?: error("Unable to open translation export file")
            }.onSuccess {
                withUIContext {
                    snackbarHostState.showSnackbarReplacing(
                        context.stringResource(MR.strings.chapter_title_translation_exported),
                    )
                }
            }.onFailure { error ->
                logcat(LogPriority.ERROR, error)
                withUIContext {
                    snackbarHostState.showSnackbarReplacing(
                        context.stringResource(MR.strings.chapter_title_translation_export_failed),
                    )
                }
            }
        }
    }

    fun importChapterTitles(uri: Uri) {
        val chapters = successState?.chapters?.map { it.chapter } ?: return
        viewModelScope.launchIO {
            runCatching {
                val content = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: error("Unable to open translation import file")
                ChapterTitleTranslationCodec.planImport(
                    document = ChapterTitleTranslationCodec.decode(content),
                    currentChapters = chapters,
                    currentInstanceId = basePreferences.installationId.get().takeIf(String::isNotBlank),
                )
            }.onSuccess { plan ->
                if (plan.updates.isNotEmpty()) {
                    updateChapter.awaitAll(plan.updates)
                }
                withUIContext {
                    snackbarHostState.showSnackbarReplacing(
                        context.stringResource(
                            MR.strings.chapter_title_translation_imported,
                            plan.updates.size,
                            plan.ignoredCount,
                        ),
                    )
                }
            }.onFailure { error ->
                logcat(LogPriority.ERROR, error)
                withUIContext {
                    snackbarHostState.showSnackbarReplacing(
                        context.stringResource(MR.strings.chapter_title_translation_import_failed),
                    )
                }
            }
        }
    }

    fun updateChapterTranslatedTitle(chapter: Chapter, translatedTitle: String) {
        viewModelScope.launchIO {
            updateChapter.await(
                ChapterUpdate(
                    id = chapter.id,
                    translatedName = translatedTitle.trim(),
                ),
            )
        }
    }

    /**
     * @param clearSelection whether the selection is over once the change is applied. The
     * bottom bar drops the selection after its own action; a swipe on a single row does not.
     */
    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean, clearSelection: Boolean = true) {
        viewModelScope.launchIO {
            chapters
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(id = it.id, bookmark = bookmarked) }
                .let { updateChapter.awaitAll(it) }
        }
        if (clearSelection) toggleAllSelection(false)
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val manga = successState?.manga ?: return
        viewModelScope.launchNonCancellable {
            libraryPreferences.setChapterSettingsDefault(manga)
            if (applyToExisting) {
                setMangaDefaultChapterFlags.awaitAll()
            }
            snackbarHostState.showSnackbarReplacing(
                message = context.stringResource(MR.strings.chapter_settings_updated),
            )
        }
    }

    fun resetToDefaultSettings() {
        val manga = successState?.manga ?: return
        viewModelScope.launchNonCancellable {
            setMangaDefaultChapterFlags.await(manga)
        }
    }

    fun toggleSelection(
        item: ChapterList.Item,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        updateSuccessState { successState ->
            val newChapters = successState.processedChapters.toMutableList().apply {
                val selectedIndex = successState.processedChapters.indexOfFirst { it.id == item.chapter.id }
                if (selectedIndex < 0) return@apply

                val selectedItem = get(selectedIndex)
                if ((selectedItem.selected && selected) || (!selectedItem.selected && !selected)) return@apply

                val firstSelection = none { it.selected }
                set(selectedIndex, selectedItem.copy(selected = selected))
                selectedChapterIds.addOrRemove(item.id, selected)

                if (selected && fromLongPress) {
                    if (firstSelection) {
                        selectedPositions[0] = selectedIndex
                        selectedPositions[1] = selectedIndex
                    } else {
                        // Try to select the items in-between when possible
                        val range: IntRange
                        if (selectedIndex < selectedPositions[0]) {
                            range = selectedIndex + 1..<selectedPositions[0]
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            range = (selectedPositions[1] + 1)..<selectedIndex
                            selectedPositions[1] = selectedIndex
                        } else {
                            // Just select itself
                            range = IntRange.EMPTY
                        }

                        range.forEach {
                            val inbetweenItem = get(it)
                            if (!inbetweenItem.selected) {
                                selectedChapterIds.add(inbetweenItem.id)
                                set(it, inbetweenItem.copy(selected = true))
                            }
                        }
                    }
                } else if (!fromLongPress) {
                    if (!selected) {
                        if (selectedIndex == selectedPositions[0]) {
                            selectedPositions[0] = indexOfFirst { it.selected }
                        } else if (selectedIndex == selectedPositions[1]) {
                            selectedPositions[1] = indexOfLast { it.selected }
                        }
                    } else {
                        if (selectedIndex < selectedPositions[0]) {
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            selectedPositions[1] = selectedIndex
                        }
                    }
                }
            }
            successState.copy(chapters = newChapters)
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        updateSuccessState { successState ->
            val newChapters = successState.chapters.map {
                selectedChapterIds.addOrRemove(it.id, selected)
                it.copy(selected = selected)
            }
            selectedPositions[0] = -1
            selectedPositions[1] = -1
            successState.copy(chapters = newChapters)
        }
    }

    fun invertSelection() {
        updateSuccessState { successState ->
            val newChapters = successState.chapters.map {
                selectedChapterIds.addOrRemove(it.id, !it.selected)
                it.copy(selected = !it.selected)
            }
            selectedPositions[0] = -1
            selectedPositions[1] = -1
            successState.copy(chapters = newChapters)
        }
    }

    // Chapters list - end

    // Track sheet - start

    private fun observeTrackers() {
        val manga = successState?.manga ?: return

        viewModelScope.launchIO {
            combine(
                getTracks.subscribe(manga.id).catch { logcat(LogPriority.ERROR, it) },
                trackerManager.loggedInTrackersFlow(),
            ) { mangaTracks, loggedInTrackers ->
                // Show only if the service supports this manga's source
                val supportedTrackers = loggedInTrackers.filter { (it as? EnhancedTracker)?.accept(source!!) ?: true }
                val supportedTrackerIds = supportedTrackers.map { it.id }.toHashSet()
                val supportedTrackerTracks = mangaTracks.filter { it.trackerId in supportedTrackerIds }
                supportedTrackerTracks.size to supportedTrackers.isNotEmpty()
            }
                .distinctUntilChanged()
                .collectLatest { (trackingCount, hasLoggedInTrackers) ->
                    updateSuccessState {
                        it.copy(
                            trackingCount = trackingCount,
                            hasLoggedInTrackers = hasLoggedInTrackers,
                        )
                    }
                }
        }
    }

    // Track sheet - end

    sealed interface Dialog {
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class DeleteChapters(val chapters: List<Chapter>) : Dialog
        data class DuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class SetFetchInterval(val manga: Manga) : Dialog
        data object SettingsSheet : Dialog
        data object TrackSheet : Dialog
        data object ClearHistory : Dialog
        data object FullCover : Dialog
        data class DeleteLocalChapters(val chapters: List<Chapter>) : Dialog
        data class DeleteLocalManga(val manga: Manga) : Dialog
    }

    fun dismissDialog() {
        updateSuccessState { it.copy(dialog = null) }
    }

    fun showDeleteChapterDialog(chapters: List<Chapter>) {
        updateSuccessState { it.copy(dialog = Dialog.DeleteChapters(chapters)) }
    }

    fun showDeleteLocalChaptersDialog(chapters: List<Chapter>) {
        updateSuccessState { it.copy(dialog = Dialog.DeleteLocalChapters(chapters)) }
    }

    fun showDeleteLocalMangaDialog() {
        val manga = successState?.manga ?: return
        updateSuccessState { it.copy(dialog = Dialog.DeleteLocalManga(manga)) }
    }

    fun showSettingsDialog() {
        updateSuccessState { it.copy(dialog = Dialog.SettingsSheet) }
    }

    fun showClearHistoryDialog() {
        updateSuccessState { it.copy(dialog = Dialog.ClearHistory) }
    }

    fun clearHistory() {
        viewModelScope.launchIO {
            removeHistory.await(mangaId)
            setReadStatus.await(mangaId, read = false)
            viewModelScope.launch {
                snackbarHostState.showSnackbarReplacing(
                    context.stringResource(MR.strings.clear_reading_history_completed),
                )
            }
        }
    }

    fun showTrackDialog() {
        updateSuccessState { it.copy(dialog = Dialog.TrackSheet) }
    }

    fun showCoverDialog() {
        updateSuccessState { it.copy(dialog = Dialog.FullCover) }
    }

    fun showMigrateDialog(duplicate: Manga) {
        val manga = successState?.manga ?: return
        updateSuccessState { it.copy(dialog = Dialog.Migrate(target = manga, current = duplicate)) }
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        viewModelScope.launchIO {
            setExcludedScanlators.await(mangaId, excludedScanlators)
        }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val manga: Manga,
            val source: Source,
            val isFromSource: Boolean,
            val chapters: List<ChapterList.Item>,
            val goodDoujinChapterIds: Set<Long>,
            val availableScanlators: Set<String>,
            val excludedScanlators: Set<String>,
            val trackingCount: Int = 0,
            val hasLoggedInTrackers: Boolean = false,
            val isRefreshingData: Boolean = false,
            val dialog: Dialog? = null,
            val hasPromptedToAddBefore: Boolean = false,
        ) : State {
            val processedChapters by lazy {
                chapters.applyFilters(manga).toList()
            }

            val isAnySelected by lazy {
                chapters.fastAny { it.selected }
            }

            val chapterListItems by lazy {
                processedChapters
            }

            val scanlatorFilterActive: Boolean
                get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()

            val filterActive: Boolean
                get() = scanlatorFilterActive || manga.chaptersFiltered()

            /**
             * Applies the view filters to the list of chapters obtained from the database.
             * @return an observable of the list of chapters filtered and sorted.
             */
            private fun List<ChapterList.Item>.applyFilters(
                manga: Manga,
            ): Sequence<ChapterList.Item> {
                val isLocalManga = manga.isLocal()
                val unreadFilter = manga.unreadFilter
                val downloadedFilter = manga.downloadedFilter
                val bookmarkedFilter = manga.bookmarkedFilter
                return asSequence()
                    .filter { (chapter) -> applyFilter(unreadFilter) { !chapter.read } }
                    .filter { (chapter) -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
                    .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalManga } }
                    .sortedWith { (chapter1), (chapter2) -> getChapterSort(manga).invoke(chapter1, chapter2) }
            }
        }
    }
}

internal data class RandomGoodDoujinResult(
    val hasEntries: Boolean,
    val mangaId: Long?,
)

@Immutable
sealed class ChapterList {
    @Immutable
    data class Item(
        val chapter: Chapter,
        val downloadState: Download.State,
        val downloadProgress: Int,
        val selected: Boolean = false,
    ) : ChapterList() {
        val id = chapter.id
        val isDownloaded = downloadState == Download.State.DOWNLOADED
    }
}
