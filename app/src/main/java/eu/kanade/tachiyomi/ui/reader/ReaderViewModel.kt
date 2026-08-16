package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.net.Uri
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.manga.GoodDoujinStore
import eu.kanade.tachiyomi.data.manga.MangaMark
import eu.kanade.tachiyomi.data.manga.RandomSelectionCooldown
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.ArchivePageLoader
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.core.archive.archiveReader
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaProgress
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.isLocal
import tachiyomi.source.local.io.Format
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.getValue
import kotlin.time.Clock

/**
 * Presenter used by the activity to perform background operations.
 */
class ReaderViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val imageSaver: ImageSaver = Injekt.get(),
    val readerPreferences: ReaderPreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val trackChapter: TrackChapter = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val goodDoujinStore: GoodDoujinStore = Injekt.get(),
    private val randomSelectionCooldown: RandomSelectionCooldown = Injekt.get(),
) : ViewModel() {

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    /**
     * Ids of the manga and chapter the reader was launched with, taken from the activity intent.
     */
    val mangaId = savedState.get<Long>("manga") ?: -1L
    private val initialChapterId = savedState.get<Long>("chapter") ?: -1L

    val hasValidArgs = mangaId != -1L && initialChapterId != -1L

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    val manga: Manga?
        get() = state.value.manga

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    /**
     * The visible page index of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
        set(value) {
            savedState["page_index"] = value
            field = value
        }

    /**
     * The chapter the saved [chapterPageIndex] belongs to, restored from process kill. It stays
     * fixed for the lifetime of this ViewModel so a page index saved for one chapter is never
     * applied to a different chapter.
     */
    private val restoredChapterId = savedState.get<Long>("chapter_id") ?: -1L

    /**
     * Whether the restored page index has been consumed for the currently loaded chapter, so the
     * process-death recovery is applied at most once and never races a chapter change.
     */
    private var restoredPageConsumed = false

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    private var loader: ChapterLoader? = null

    /**
     * The time the chapter was started reading
     */
    private var chapterReadStartTime: Long? = null

    private var chapterToDownload: Download? = null

    private val preloadMutex = Mutex()
    private val preloadingChapters = mutableSetOf<ReaderChapter>()
    private val randomTargetWarmupMutex = Mutex()
    private var preloadedJump = RandomJumpPreloadCache.take(mangaId, initialChapterId)
    private val chapterReadingSessions = ConcurrentHashMap<Long, ChapterReadingSession>()
    private val chapterProgressMutex = Mutex()
    private val chapterProgressSequence = AtomicLong()
    private val latestChapterProgressSequences = ConcurrentHashMap<Long, Long>()
    private val progressSession = ReaderProgressSession()
    private var lastSelectedChapterId: Long? = null

    private val unfilteredChapterList by lazy {
        val manga = manga!!
        runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = false) }
    }

    /**
     * Chapter list for the active manga. It's retrieved lazily and should be accessed for the first
     * time in a background thread to avoid blocking the UI.
     */
    private val chapterList by lazy {
        val manga = manga!!
        val chapters = preloadedJump?.chapters
            ?: runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true) }

        val selectedChapter = chapters.find { it.id == chapterId }
            ?: chapters.firstOrNull()
            ?: error("Chapter list is empty")

        val chaptersForReader = when {
            (readerPreferences.skipRead.get() || readerPreferences.skipFiltered.get()) -> {
                val filteredChapters = chapters.filterNot {
                    when {
                        readerPreferences.skipRead.get() && it.read -> true
                        readerPreferences.skipFiltered.get() -> {
                            (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !it.read) ||
                                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && it.read) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED &&
                                        !downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED &&
                                        downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (
                                    manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !it.bookmark
                                    ) ||
                                (
                                    manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && it.bookmark
                                    )
                        }
                        else -> false
                    }
                }

                if (filteredChapters.any { it.id == chapterId }) {
                    filteredChapters
                } else {
                    filteredChapters + listOf(selectedChapter)
                }
            }
            else -> chapters
        }

        chaptersForReader
            .sortedWith(getChapterSort(manga, sortDescending = false))
            .run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(selectedChapter)
                } else {
                    this
                }
            }
            .run {
                if (basePreferences.downloadedOnly.get()) {
                    filterDownloaded(manga)
                } else {
                    this
                }
            }
            .run {
                if (any { it.id == selectedChapter.id }) {
                    this
                } else {
                    (this + selectedChapter).sortedWith(getChapterSort(manga, sortDescending = false))
                }
            }
            .map { it.toDbChapter() }
            .map(::ReaderChapter)
    }

    private val incognitoMode: Boolean by lazy { getIncognitoState.await(manga?.source) }
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading.get()

    init {
        goodDoujinStore.marks
            .onEach { marks ->
                val currentChapterId = state.value.currentChapter?.chapter?.id
                mutableState.update {
                    it.copy(
                        goodDoujinMarked = currentChapterId != null &&
                            marks.any { mark -> mark.chapterId == currentChapterId },
                    )
                }
            }
            .launchIn(viewModelScope)

        // To save state
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { currentChapter ->
                // The page index to open at is decided in loadChapter; here we only track which
                // chapter is active for state restoration.
                chapterId = currentChapter.chapter.id!!
            }
            .launchIn(viewModelScope)

        if (hasValidArgs) {
            viewModelScope.launch { init() }
        }
    }

    override fun onCleared() {
        val currentChapters = state.value.viewerChapters
        if (currentChapters != null) {
            currentChapters.unref()
            chapterToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onActivityFinish() {
        deletePendingChapters()
    }

    /**
     * Initializes this presenter with the [mangaId] and [initialChapterId] the reader was launched
     * with. This method will fetch the manga from the database and initialize the initial chapter.
     * Failures are reported through [State.initError].
     */
    private suspend fun init() {
        withIOContext {
            try {
                val manga = preloadedJump?.manga
                    ?: getManga.await(mangaId)
                    ?: error("Requested manga of id $mangaId not found")
                sourceManager.isInitialized.first { it }
                mutableState.update { it.copy(manga = manga) }
                if (chapterId == -1L) chapterId = initialChapterId

                val context = Injekt.get<Application>()
                val source = sourceManager.getOrStub(manga.source)
                loader = ChapterLoader(context, downloadManager, downloadProvider, manga, source)

                val targetChapter = chapterList.firstOrNull { chapterId == it.chapter.id }
                    ?: chapterList.firstOrNull()
                    ?: error("Chapter list is empty")
                loadChapter(loader!!, targetChapter)
                preloadedJump = null
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                mutableState.update { it.copy(initError = e) }
            }
        }
    }

    /**
     * Loads the given [chapter] with this [loader] and updates the currently active chapters.
     * Callers must handle errors.
     */
    private suspend fun loadChapter(
        loader: ChapterLoader,
        chapter: ReaderChapter,
    ): ViewerChapters {
        loader.loadChapter(chapter)

        // Decide the page this chapter opens at once it is loaded. The restored page index is a
        // one-shot: it is applied only to the chapter it was saved for, and only once, so a later
        // chapter change can never reuse a stale index.
        if (!restoredPageConsumed && chapterPageIndex >= 0 && chapter.chapter.id == restoredChapterId) {
            restoredPageConsumed = true
            chapter.requestedPage = chapterPageIndex
        } else {
            chapter.requestedPage = chapter.resumePageIndex()
        }

        // Persist the total page count so the chapter list can show read progress. A completed
        // chapter is a terminal state: once its real page count is known, its stored progress is
        // normalized to the final page as well.
        chapter.pages?.size?.let { totalPages ->
            val previousTotalPages = chapter.chapter.total_pages
            val previousLastPageRead = chapter.chapter.last_page_read
            chapter.chapter.total_pages = totalPages
            if (chapter.chapter.read) {
                chapter.chapter.last_page_read = totalPages
            }
            val progressChanged = previousTotalPages != totalPages ||
                (chapter.chapter.read && previousLastPageRead != totalPages)
            chapter.chapter.id?.takeIf { progressChanged }?.let { chapterId ->
                updateChapter.await(
                    ChapterUpdate(
                        id = chapterId,
                        totalPages = totalPages.toLong(),
                        lastPageRead = totalPages.toLong().takeIf { chapter.chapter.read },
                    ),
                )
            }
        }

        val chapterPos = chapterList.indexOf(chapter)
        val newChapters = ViewerChapters(
            chapter,
            chapterList.getOrNull(chapterPos - 1),
            chapterList.getOrNull(chapterPos + 1),
        )

        withUIContext {
            mutableState.update {
                // Add new references first to avoid unnecessary recycling
                newChapters.ref()
                it.viewerChapters?.unref()

                chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)
                it.copy(
                    viewerChapters = newChapters,
                    goodDoujinMarked = goodDoujinStore.marks.value.any { mark ->
                        mark.chapterId == newChapters.currChapter.chapter.id
                    },
                )
            }
        }

        return newChapters
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter) {
        val loader = loader ?: return

        viewModelScope.launchIO {
            logcat { "Loading ${chapter.chapter.url}" }

            updateHistory()
            restartReadTimer()

            try {
                loadChapter(loader, chapter)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Called when the user is going to load the prev/next chapter through the toolbar buttons.
     */
    private suspend fun loadAdjacent(chapter: ReaderChapter) {
        val loader = loader ?: return

        logcat { "Loading adjacent ${chapter.chapter.url}" }

        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            withIOContext {
                loadChapter(loader, chapter)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            logcat(LogPriority.ERROR, e)
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    suspend fun preload(chapter: ReaderChapter) {
        val claimed = preloadMutex.withLock {
            chapter.state !is ReaderChapter.State.Loaded &&
                chapter.state != ReaderChapter.State.Loading &&
                preloadingChapters.add(chapter)
        }
        if (!claimed) return

        try {
            preloadChapter(chapter)
        } finally {
            preloadMutex.withLock {
                preloadingChapters.remove(chapter)
            }
        }
    }

    private suspend fun preloadChapter(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) {
            return
        }

        if (chapter.pageLoader?.isLocal == false) {
            val manga = manga ?: return
            val dbChapter = chapter.chapter
            val isDownloaded = downloadManager.isChapterDownloaded(
                dbChapter.name,
                dbChapter.scanlator,
                dbChapter.url,
                manga.title,
                manga.source,
                skipCache = true,
            )
            if (isDownloaded) {
                chapter.state = ReaderChapter.State.Wait
            }
        }

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        val loader = loader ?: return
        try {
            logcat { "Preloading ${chapter.chapter.url}" }
            loader.loadChapter(chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return
        }
        eventChannel.trySend(Event.ReloadViewerChapters)
    }

    fun onViewerLoaded(viewer: Viewer?) {
        mutableState.update {
            it.copy(viewer = viewer)
        }
    }

    /**
     * Called once the reader's first frame is aligned on [page] to record the in-memory session
     * position. Deliberately lighter than [onPageSelected]: it
     * must not trigger a chapter load, preload/download work, or the [Event.PageChanged] display
     * refresh, otherwise the reveal would flash on the first frame.
     */
    fun onInitialPageSelected(page: ReaderPage) {
        if (page is InsertPage) return
        val chapterId = page.chapter.chapter.id ?: return
        lastSelectedChapterId = chapterId
        chapterReadingSessions.putIfAbsent(
            chapterId,
            ChapterReadingSession(
                totalPages = page.chapter.pages?.size ?: return,
                entryDirection = ChapterEntryDirection.Direct,
                alreadyRead = page.chapter.chapter.read,
            ),
        )
        progressSession.recordInitial(chapterId, page.index)
        updateSessionPage(page.chapter, page)
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage) {
        // InsertPage doesn't change page progress
        if (page is InsertPage) {
            return
        }

        val selectedChapter = page.chapter
        val pages = selectedChapter.pages ?: return
        val selectedChapterId = selectedChapter.chapter.id ?: return
        val previousChapterId = lastSelectedChapterId

        if (previousChapterId != selectedChapterId) {
            progressSession.beginEntry(selectedChapterId)
            chapterReadingSessions[selectedChapterId] = ChapterReadingSession(
                totalPages = pages.size,
                entryDirection = getChapterEntryDirection(selectedChapterId, previousChapterId),
                alreadyRead = selectedChapter.chapter.read,
            )
        }
        lastSelectedChapterId = selectedChapterId

        // Track the current page in memory only. The actual DB write happens when the
        // scroll settles (see onScrollSettled) so transient page positions reported
        // while scrolling or loading images never corrupt the stored progress.
        updateSessionPage(selectedChapter, page)

        if (selectedChapter != getCurrentChapter()) {
            logcat { "Setting ${selectedChapter.chapter.url} as active" }
            loadNewChapter(selectedChapter)
        }

        val inDownloadRange = page.number.toDouble() / pages.size > 0.25
        if (inDownloadRange) {
            downloadNextChapters()
        }

        eventChannel.trySend(Event.PageChanged)
    }

    /**
     * Called by viewers once the scroll has settled on [page] (webtoon) or a page has been
     * fully displayed (pager). This is the only point where reading progress is persisted,
     * so intermediate positions during scrolling or layout churn are never written.
     */
    fun onScrollSettled(page: ReaderPage) {
        if (page is InsertPage) {
            return
        }
        val chapterId = page.chapter.chapter.id ?: return
        progressSession.recordSettled(chapterId, page.index)
        val sequence = chapterProgressSequence.incrementAndGet()
        latestChapterProgressSequences[chapterId] = sequence
        viewModelScope.launchNonCancellable {
            persistLatestChapterProgress(page.chapter, page.index, sequence)
        }
    }

    private fun downloadNextChapters() {
        if (downloadAheadAmount == 0) return
        val manga = manga ?: return

        // Only download ahead if current + next chapter is already downloaded too to avoid jank
        if (getCurrentChapter()?.pageLoader !is DownloadPageLoader) return
        val nextChapter = state.value.viewerChapters?.nextChapter?.chapter ?: return

        viewModelScope.launchIO {
            val isNextChapterDownloaded = downloadManager.isChapterDownloaded(
                nextChapter.name,
                nextChapter.scanlator,
                nextChapter.url,
                manga.title,
                manga.source,
            )
            if (!isNextChapterDownloaded) return@launchIO

            val chaptersToDownload = getNextChapters.await(manga.id, nextChapter.id!!).run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(nextChapter.toDomainChapter()!!)
                } else {
                    this
                }
            }.take(downloadAheadAmount)

            downloadManager.downloadChapters(
                manga,
                chaptersToDownload,
            )
        }
    }

    /**
     * Removes [currentChapter] from download queue
     * if setting is enabled and [currentChapter] is queued for download
     */
    private fun cancelQueuedDownloads(currentChapter: ReaderChapter): Download? {
        return downloadManager.getQueuedDownloadOrNull(currentChapter.chapter.id!!)?.also {
            downloadManager.cancelQueuedDownloads(listOf(it))
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots.get()
        if (removeAfterReadSlots == -1) return

        // Determine which chapter should be deleted and enqueue
        val currentChapterPosition = chapterList.indexOf(currentChapter)
        val chapterToDelete = chapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)

        // If chapter is completely read, no need to download it
        chapterToDownload = null

        if (chapterToDelete != null) {
            enqueueDeleteReadChapters(chapterToDelete)
        }
    }

    /**
     * Updates the in-memory reading position (page indicator, session resume position).
     * Does not touch the database.
     */
    private fun updateSessionPage(readerChapter: ReaderChapter, page: Page) {
        val pageIndex = page.index

        mutableState.update {
            it.copy(currentPage = pageIndex + 1)
        }
        readerChapter.requestedPage = pageIndex
        chapterPageIndex = pageIndex
    }

    private fun getChapterEntryDirection(
        selectedChapterId: Long,
        previousChapterId: Long?,
    ): ChapterEntryDirection {
        if (previousChapterId == null || selectedChapterId == previousChapterId) {
            return ChapterEntryDirection.Direct
        }
        val previousChapterIndex = chapterList.indexOfFirst { it.chapter.id == previousChapterId }
        val selectedChapterIndex = chapterList.indexOfFirst { it.chapter.id == selectedChapterId }
        if (previousChapterIndex == -1 || selectedChapterIndex == -1) {
            return ChapterEntryDirection.Direct
        }

        return if (selectedChapterIndex < previousChapterIndex) {
            ChapterEntryDirection.Backward
        } else {
            ChapterEntryDirection.Forward
        }
    }

    private suspend fun persistLatestChapterProgress(
        readerChapter: ReaderChapter,
        pageIndex: Int,
        sequence: Long,
        completingOnExit: Boolean = false,
    ) {
        val chapterId = readerChapter.chapter.id ?: return
        chapterProgressMutex.withLock {
            if (latestChapterProgressSequences[chapterId] != sequence) return@withLock
            updateChapterProgress(readerChapter, pageIndex, completingOnExit)
            latestChapterProgressSequences.remove(chapterId, sequence)
        }
    }

    /**
     * Persists the chapter progress (last read page and whether it's read)
     * if incognito mode isn't on. Only called once the reading position is stable.
     */
    private suspend fun updateChapterProgress(
        readerChapter: ReaderChapter,
        pageIndex: Int,
        completingOnExit: Boolean,
    ) {
        if (incognitoMode) return
        val page = readerChapter.pages?.getOrNull(pageIndex) ?: return
        if (page.status is Page.State.Error) return

        val lastIndex = readerChapter.pages?.lastIndex ?: return
        val chapterId = readerChapter.chapter.id ?: return
        val totalPages = lastIndex + 1
        if (readerChapter.chapter.read) {
            readerChapter.chapter.last_page_read = totalPages
            updateChapter.awaitReaderProgress(
                chapterId = chapterId,
                pageNumber = totalPages.toLong(),
                totalPages = totalPages.toLong(),
                completed = false,
            )
            return
        }

        val readingSession = chapterReadingSessions.getOrPut(chapterId) {
            ChapterReadingSession(
                totalPages = totalPages,
                entryDirection = ChapterEntryDirection.Direct,
                alreadyRead = false,
            )
        }
        val decision = if (completingOnExit) {
            readingSession.onExit(pageIndex)
        } else {
            readingSession.onSettled(pageIndex)
        } ?: return

        readerChapter.chapter.last_page_read = decision.pageIndex + 1
        if (decision.completed) {
            readerChapter.chapter.last_page_read = totalPages
            updateChapterProgressOnComplete(readerChapter)
        }

        updateChapter.awaitReaderProgress(
            chapterId = chapterId,
            pageNumber = readerChapter.chapter.last_page_read.toLong(),
            totalPages = totalPages.toLong(),
            completed = decision.completed,
        )
    }

    /**
     * Persists the current in-memory reading position of [readerChapter], used when leaving the
     * reader or when crossing into another chapter so the last position is never lost.
     */
    private suspend fun flushChapterProgress(readerChapter: ReaderChapter) {
        val chapterId = readerChapter.chapter.id ?: return
        val pageIndex = progressSession.getForExit(chapterId) ?: return
        val sequence = chapterProgressSequence.incrementAndGet()
        latestChapterProgressSequences[chapterId] = sequence
        persistLatestChapterProgress(
            readerChapter = readerChapter,
            pageIndex = pageIndex,
            sequence = sequence,
            completingOnExit = true,
        )
    }

    private suspend fun updateChapterProgressOnComplete(readerChapter: ReaderChapter) {
        readerChapter.chapter.read = true
        updateTrackChapterRead(readerChapter)
        deleteChapterIfNeeded(readerChapter)

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead) return

        val duplicateUnreadChapters = unfilteredChapterList
            .mapNotNull { chapter ->
                if (
                    !chapter.read &&
                    chapter.isRecognizedNumber &&
                    chapter.chapterNumber.toFloat() == readerChapter.chapter.chapter_number
                ) {
                    ChapterUpdate(
                        id = chapter.id,
                        read = true,
                        lastPageRead = chapter.totalPages.takeIf { it > 0 },
                    )
                } else {
                    null
                }
            }
        updateChapter.awaitAll(duplicateUnreadChapters)
    }

    fun restartReadTimer() {
        chapterReadStartTime = Clock.System.now().toEpochMilliseconds()
    }

    /**
     * Saves the chapter to the reading history if incognito mode isn't on. Merely opening a
     * chapter is enough to enter history — there is no minimum pages/stay-duration gate.
     */
    suspend fun updateHistory() {
        getCurrentChapter()?.let { readerChapter ->
            if (incognitoMode) return@let

            flushChapterProgress(readerChapter)

            val chapterId = readerChapter.chapter.id!!
            val endTime = Date()
            val sessionReadDuration = chapterReadStartTime?.let { endTime.time - it } ?: 0

            upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
            chapterReadStartTime = null
        }
    }

    /**
     * Called from the activity to load and set the next chapter as active.
     */
    suspend fun loadNextChapter() {
        val nextChapter = state.value.viewerChapters?.nextChapter ?: return
        loadAdjacent(nextChapter)
    }

    /**
     * Called from the activity to load and set the previous chapter as active.
     */
    suspend fun loadPreviousChapter() {
        val prevChapter = state.value.viewerChapters?.prevChapter ?: return
        loadAdjacent(prevChapter)
    }

    /**
     * Returns the currently active chapter.
     */
    private fun getCurrentChapter(): ReaderChapter? {
        return state.value.currentChapter
    }

    fun getSource() = manga?.source?.let { sourceManager.getOrStub(it) } as? HttpSource

    /**
     * Returns the id of the currently active chapter, or null when no chapter is loaded yet.
     */
    fun getCurrentChapterId(): Long? = state.value.currentChapter?.chapter?.id

    fun getCurrentPageIndex(): Int = (state.value.currentPage - 1).coerceAtLeast(0)

    /**
     * Picks from every eligible unread chapter in the current local-source reading filter.
     * Each chapter is an independent pool entry, so manga with more eligible chapters have
     * proportionally more entries instead of every manga receiving the same weight.
     */
    suspend fun getRandomInProgressTarget(): Pair<Long, Long>? {
        val target = findAndWarmRandomTarget(RandomJumpPool.IN_PROGRESS, allowCooldownReset = true) ?: return null
        RandomJumpPreloadCache.put(target.manga, target.chapters, target.chapter.id)
        return target.manga.id to target.chapter.id
    }

    private suspend fun findRandomInProgressTarget(allowCooldownReset: Boolean): RandomJumpTarget? {
        val currentManga = manga ?: return null
        if (!currentManga.isLocal()) return null
        val source = sourceManager.getOrStub(currentManga.source)
        if (source !is LocalSource) return null

        val readingFilter = readReadingFilter(currentManga.source)
        val base = withIOContext {
            runCatching {
                mangaRepository.getMangaProgressBySourceAsFlow(currentManga.source).first()
            }.getOrDefault(emptyList())
        }.filter {
            matchesReadingFilter(readingFilter, it.progress)
        }
        if (base.isEmpty()) return null

        val chaptersByMangaId = loadRandomPoolChapters(base.map { it.mangaId })
        if (chaptersByMangaId.isEmpty()) return null
        val currentChapterId = getCurrentChapterId()
        val candidates = chaptersByMangaId.values
            .asSequence()
            .flatten()
            .filter {
                it.isEligibleForRandomPool &&
                    it.id != currentChapterId
            }
            .toList()
        val available = randomSelectionCooldown.eligibleChapters(
            candidates = candidates,
            releaseOnExhaustion = allowCooldownReset,
            mangaId = Chapter::mangaId,
            chapterId = Chapter::id,
        )
        return resolveRandomJumpTarget(available, chaptersByMangaId)
    }

    /**
     * Reads the last-used reading filter for [sourceId] from preferences, matching the
     * browse list so a random jump stays inside the pool the user was browsing.
     */
    private fun readReadingFilter(sourceId: Long): BrowseSourceViewModel.ReadingFilter {
        val raw = Injekt.get<PreferenceStore>()
            .getString(READING_FILTER_PREF_PREFIX + sourceId, BrowseSourceViewModel.ReadingFilter.ALL.name)
            .get()
        return runCatching { BrowseSourceViewModel.ReadingFilter.valueOf(raw) }
            .getOrDefault(BrowseSourceViewModel.ReadingFilter.ALL)
    }

    private fun matchesReadingFilter(
        filter: BrowseSourceViewModel.ReadingFilter,
        progress: MangaProgress,
    ): Boolean = when (filter) {
        BrowseSourceViewModel.ReadingFilter.ALL -> true
        BrowseSourceViewModel.ReadingFilter.UNREAD -> !progress.hasFinished
        BrowseSourceViewModel.ReadingFilter.IN_PROGRESS -> progress.hasBeenRead && !progress.hasFinished
        BrowseSourceViewModel.ReadingFilter.FINISHED -> progress.hasFinished
    }

    /**
     * Picks from every eligible unmarked chapter across the good-doujin manga. Chapters are
     * flattened into one pool instead of first choosing a manga and then one of its chapters.
     */
    suspend fun getRandomGoodDoujinTarget(): Pair<Long, Long>? {
        val target = findAndWarmRandomTarget(RandomJumpPool.GOOD_DOUJIN, allowCooldownReset = true) ?: return null
        RandomJumpPreloadCache.put(target.manga, target.chapters, target.chapter.id)
        return target.manga.id to target.chapter.id
    }

    private suspend fun findRandomGoodDoujinTarget(allowCooldownReset: Boolean): RandomJumpTarget? {
        if (manga == null) return null
        val marks = Injekt.get<GoodDoujinStore>().marks.value
        val markedChapterIds = marks
            .groupBy({ it.mangaId }, { it.chapterId })
            .mapValues { (_, chapterIds) -> chapterIds.toSet() }
        val mangaIds = markedChapterIds.keys.toList()
        if (mangaIds.isEmpty()) return null

        val chaptersByMangaId = loadRandomPoolChapters(mangaIds)
        if (chaptersByMangaId.isEmpty()) return null
        val currentChapterId = getCurrentChapterId()
        val candidates = chaptersByMangaId.values
            .asSequence()
            .flatten()
            .filter {
                    it.isEligibleForRandomPool &&
                    it.id !in markedChapterIds[it.mangaId].orEmpty() &&
                    it.id != currentChapterId
            }
            .toList()
        val available = randomSelectionCooldown.eligibleChapters(
            candidates = candidates,
            releaseOnExhaustion = allowCooldownReset,
            mangaId = Chapter::mangaId,
            chapterId = Chapter::id,
        )
        return resolveRandomJumpTarget(available, chaptersByMangaId)
    }

    private suspend fun findAndWarmRandomTarget(
        pool: RandomJumpPool,
        allowCooldownReset: Boolean,
    ): RandomJumpTarget? {
        val target = when (pool) {
            RandomJumpPool.IN_PROGRESS -> findRandomInProgressTarget(allowCooldownReset)
            RandomJumpPool.GOOD_DOUJIN -> findRandomGoodDoujinTarget(allowCooldownReset)
        }
        if (target?.localArchive != null) {
            randomTargetWarmupMutex.withLock {
                try {
                    warmRandomTarget(target)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to warm random manga target" }
                }
            }
        }
        return target
    }

    private suspend fun warmRandomTarget(target: RandomJumpTarget) {
        val archive = target.localArchive ?: return
        val context = Injekt.get<Application>()
        val pageLoader = ArchivePageLoader(archive.file, archive.file.archiveReader(context))
        try {
            pageLoader.getPages()
        } finally {
            pageLoader.recycle()
        }
    }

    private suspend fun loadRandomPoolChapters(mangaIds: List<Long>): Map<Long, List<Chapter>> {
        return withIOContext {
            getChaptersByMangaId.await(mangaIds, applyScanlatorFilter = true)
        }.groupBy { it.mangaId }
    }

    /** Resolves the first usable chapter from a shuffled flat pool. */
    private suspend fun resolveRandomJumpTarget(
        candidates: List<Chapter>,
        chaptersByMangaId: Map<Long, List<Chapter>>,
    ): RandomJumpTarget? {
        if (candidates.isEmpty()) return null

        val mangaCache = mutableMapOf<Long, Manga>()
        val missingMangaIds = mutableSetOf<Long>()
        for (chapter in candidates.shuffled()) {
            if (!chapter.isEligibleForRandomPool) continue
            if (chapter.mangaId in missingMangaIds) continue
            val targetManga = mangaCache[chapter.mangaId] ?: withIOContext {
                runCatching { mangaRepository.getMangaById(chapter.mangaId) }.getOrNull()
            }
            if (targetManga == null) {
                missingMangaIds += chapter.mangaId
                continue
            }
            mangaCache[chapter.mangaId] = targetManga
            val source = sourceManager.getOrStub(targetManga.source)
            val localArchive = if (source is LocalSource) {
                when (val format = runCatching { source.getFormat(chapter.toSChapter()) }.getOrNull()) {
                    is Format.Archive -> format
                    is Format.Directory, is Format.Epub -> null
                    null -> continue
                }
            } else {
                null
            }
            return RandomJumpTarget(
                manga = targetManga,
                chapters = chaptersByMangaId[chapter.mangaId].orEmpty(),
                chapter = chapter,
                localArchive = localArchive,
            )
        }
        return null
    }

    private enum class RandomJumpPool {
        IN_PROGRESS,
        GOOD_DOUJIN,
    }

    private data class RandomJumpTarget(
        val manga: Manga,
        val chapters: List<Chapter>,
        val chapter: Chapter,
        val localArchive: Format.Archive?,
    )

    fun rememberSkippedChapter(mangaId: Long, chapterId: Long) {
        randomSelectionCooldown.rememberChapter(mangaId, chapterId)
    }

    fun getChapterUrl(): String? {
        val sChapter = getCurrentChapter()?.chapter ?: return null
        val source = getSource() ?: return null

        return try {
            source.getChapterUrl(sChapter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    fun toggleCurrentChapterGoodDoujin() {
        val chapter = getCurrentChapter()?.chapter ?: return
        val manga = manga ?: return
        if (!manga.isLocal()) return
        viewModelScope.launch {
            goodDoujinStore.toggle(
                MangaMark(
                    mangaId = manga.id,
                    mangaTitle = manga.title,
                    chapterId = chapter.id ?: return@launch,
                    chapterName = chapter.name.orEmpty(),
                    markedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * Returns the viewer position used by this manga or the default one.
     */
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int {
        return readerPreferences.defaultReadingMode.get()
    }

    /**
     * Updates the viewer position for the open manga.
     */
    fun setMangaReadingMode(readingMode: ReadingMode) {
        val resolved = if (readingMode == ReadingMode.DEFAULT) ReadingMode.RIGHT_TO_LEFT else readingMode
        readerPreferences.defaultReadingMode.set(resolved.flagValue)
        val currChapters = state.value.viewerChapters
        if (currChapters != null) {
            // Save current page
            val currChapter = currChapters.currChapter
            currChapter.requestedPage = currChapter.resumePageIndex()

            mutableState.update {
                it.copy(viewerChapters = currChapters)
            }
            eventChannel.trySend(Event.ReloadViewer)
            eventChannel.trySend(Event.ReloadViewerChapters)
        }
    }

    /**
     * Returns the orientation type used by this manga or the default one.
     */
    fun getMangaOrientation(resolveDefault: Boolean = true): Int {
        return readerPreferences.defaultOrientationType.get()
    }

    /**
     * Updates the orientation type for the open manga.
     */
    fun setMangaOrientationType(orientation: ReaderOrientation) {
        val resolved = if (orientation == ReaderOrientation.DEFAULT) ReaderOrientation.FREE else orientation
        readerPreferences.defaultOrientationType.set(resolved.flagValue)
        val currChapters = state.value.viewerChapters
        if (currChapters != null) {
            // Save current page
            val currChapter = currChapters.currChapter
            currChapter.requestedPage = currChapter.resumePageIndex()

            mutableState.update {
                it.copy(viewerChapters = currChapters)
            }
            eventChannel.trySend(Event.SetOrientation(getMangaOrientation()))
            eventChannel.trySend(Event.ReloadViewerChapters)
        }
    }

    fun toggleCropBorders(): Boolean {
        val isPagerType = ReadingMode.isPagerType(getMangaReadingMode())
        return if (isPagerType) {
            readerPreferences.cropBorders.toggle()
        } else {
            readerPreferences.cropBordersWebtoon.toggle()
        }
    }

    /**
     * Generate a filename for the given [manga] and [page]
     */
    private fun generateFilename(
        manga: Manga,
        page: ReaderPage,
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.number}"
        return DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    fun showLoadingDialog() {
        mutableState.update { it.copy(dialog = Dialog.Loading) }
    }

    fun openReadingModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.ReadingModeSelect) }
    }

    fun openOrientationModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.OrientationModeSelect) }
    }

    fun openPageDialog(page: ReaderPage) {
        mutableState.update { it.copy(dialog = Dialog.PageActions(page)) }
    }

    fun openSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.Settings) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun setBrightnessOverlayValue(value: Int) {
        mutableState.update { it.copy(brightnessOverlayValue = value) }
    }

    /**
     * Saves the image of the selected page on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val filename = generateFilename(manga, page)

        // Pictures directory.
        val relativePath = if (readerPreferences.folderPerManga.get()) {
            DiskUtil.buildValidFilename(
                manga.title,
            )
        } else {
            ""
        }

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Pictures.create(relativePath),
                    ),
                )
                withUIContext {
                    notifier.onComplete(uri)
                    eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
                }
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    /**
     * Shares the image of the selected page and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(copyToClipboard: Boolean) {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val destDir = context.cacheImageDir

        val filename = generateFilename(manga, page)

        try {
            viewModelScope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, page))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /**
     * Sets the image of the selected page as cover and notifies the UI of the result.
     */
    fun setAsCover() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                manga.editCover(Injekt.get(), stream())
                if (manga.isLocal() || manga.favorite) {
                    SetAsCoverResult.Success
                } else {
                    SetAsCoverResult.AddToLibraryFirst
                }
            } catch (e: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error,
    }

    sealed interface SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult
        class Error(val error: Throwable) : SaveImageResult
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (incognitoMode) return
        if (!trackPreferences.autoUpdateTrack.get()) return

        val manga = manga ?: return
        val context = Injekt.get<Application>()

        viewModelScope.launchNonCancellable {
            trackChapter.await(context, manga.id, readerChapter.chapter.chapter_number.toDouble())
        }
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteReadChapters(chapter: ReaderChapter) {
        if (!chapter.chapter.read) return
        val manga = manga ?: return

        viewModelScope.launchNonCancellable {
            downloadManager.enqueueChaptersToDelete(listOf(chapter.chapter.toDomainChapter()!!), manga)
        }
    }

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingChapters()
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        val initError: Throwable? = null,
        val viewerChapters: ViewerChapters? = null,
        val goodDoujinMarked: Boolean = false,
        val isLoadingAdjacentChapter: Boolean = false,
        val currentPage: Int = -1,

        /**
         * Viewer used to display the pages (pager, webtoon, ...).
         */
        val viewer: Viewer? = null,
        val dialog: Dialog? = null,
        val menuVisible: Boolean = false,
        @IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        val totalPages: Int
            get() = currentChapter?.pages?.size ?: -1
    }

    sealed interface Dialog {
        data object Loading : Dialog
        data object Settings : Dialog
        data object ReadingModeSelect : Dialog
        data object OrientationModeSelect : Dialog
        data class PageActions(val page: ReaderPage) : Dialog
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event
        data object ReloadViewer : Event
        data object PageChanged : Event
        data class SetOrientation(val orientation: Int) : Event
        data class SetCoverResult(val result: SetAsCoverResult) : Event

        data class SavedImage(val result: SaveImageResult) : Event
        data class ShareImage(val uri: Uri, val page: ReaderPage) : Event
        data class CopyImage(val uri: Uri) : Event
    }
}

/** Preference key prefix for the browse list reading filter, keyed by source id. */
private const val READING_FILTER_PREF_PREFIX = "browse_reading_filter_"

private data class RandomJumpPreload(
    val manga: Manga,
    val chapters: List<Chapter>,
    val chapterId: Long,
    val cachedAt: Long,
)

private object RandomJumpPreloadCache {
    private var preload: RandomJumpPreload? = null

    @Synchronized
    fun put(manga: Manga, chapters: List<Chapter>, chapterId: Long) {
        preload = RandomJumpPreload(
            manga = manga,
            chapters = chapters,
            chapterId = chapterId,
            cachedAt = System.currentTimeMillis(),
        )
    }

    @Synchronized
    fun take(mangaId: Long, chapterId: Long): RandomJumpPreload? {
        val cached = preload ?: return null
        preload = null
        return cached.takeIf {
            it.manga.id == mangaId &&
                it.chapterId == chapterId &&
                System.currentTimeMillis() - it.cachedAt <= RANDOM_JUMP_PRELOAD_TTL_MILLIS
        }
    }
}

private const val RANDOM_JUMP_PRELOAD_TTL_MILLIS = 30_000L

internal val Chapter.isEligibleForRandomPool: Boolean
    get() = !read
