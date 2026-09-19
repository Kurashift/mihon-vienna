package eu.kanade.tachiyomi.ui.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.shouldDisplayChapterNumber
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HistoryViewModel(
    private val addTracks: AddTracks = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val historyRepository: HistoryRepository = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateViewModel<HistoryViewModel.State>(State()) {

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            state.map { it.searchQuery }
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    combine(
                        getHistory.subscribe(query ?: "").distinctUntilChanged(),
                        libraryPreferences.localChapterDisplayMode.changes(),
                    ) { history, localChapterDisplayMode ->
                        history.toHistoryUiModels(localChapterDisplayMode)
                    }
                        .catch { error ->
                            logcat(LogPriority.ERROR, error)
                            _events.send(Event.InternalError)
                        }
                        .flowOn(Dispatchers.IO)
                }
                .collect { newList -> mutableState.update { it.copy(list = newList) } }
        }
    }

    private fun List<HistoryWithRelations>.toHistoryUiModels(
        localChapterDisplayMode: Long,
    ): List<HistoryUiModel> {
        return map { HistoryUiModel.Item(it.hideLocalChapterNumber(localChapterDisplayMode)) }
            .insertSeparators { before, after ->
                val beforeDate = before?.item?.readAt?.time?.toLocalDate()
                val afterDate = after?.item?.readAt?.time?.toLocalDate()
                when {
                    beforeDate != afterDate && afterDate != null -> HistoryUiModel.Header(afterDate)
                    // Return null to avoid adding a separator between two items.
                    else -> null
                }
            }
    }

    /**
     * Local chapters only get a chapter number because the file name was parsed for one, which
     * turns names like "vol1-17.5" into a meaningless "第 1.17 篇". The chapter list already hides
     * that number unless local chapters were explicitly set to display it, so history must follow
     * the same rule instead of always printing whatever was parsed out of the file name.
     */
    private fun HistoryWithRelations.hideLocalChapterNumber(
        localChapterDisplayMode: Long,
    ): HistoryWithRelations {
        val showChapterNumber = shouldDisplayChapterNumber(
            sourceId = coverData.sourceId,
            localDisplayMode = localChapterDisplayMode,
        )
        return if (showChapterNumber) this else copy(chapterNumber = -1.0)
    }

    /**
     * Opens the chapter referenced by the most recent history entry, at the page it was last left.
     *
     * This intentionally does not advance to the next chapter: the history tab should resume
     * exactly where the user left off.
     */
    suspend fun getNextChapter(): Chapter? {
        return withIOContext { getHistoryLastReadChapter() }
    }

    /**
     * Opens the chapter referenced by [chapterId], at the page it was last left.
     */
    fun getNextChapterForManga(chapterId: Long) {
        viewModelScope.launchIO {
            sendChapterEvent(getChapter.await(chapterId))
        }
    }

    private suspend fun getHistoryLastReadChapter(): Chapter? {
        val history = historyRepository.getLastHistory() ?: return null
        return getChapter.await(history.chapterId)
    }

    private suspend fun sendChapterEvent(chapter: Chapter?) {
        _events.send(Event.OpenChapter(chapter))
    }

    fun toggleSelection(historyId: Long, selected: Boolean) {
        mutableState.update {
            it.copy(selection = if (selected) it.selection + historyId else it.selection - historyId)
        }
    }

    fun toggleAllSelection() {
        mutableState.update {
            it.copy(
                selection = it.list.orEmpty()
                    .filterIsInstance<HistoryUiModel.Item>()
                    .mapTo(mutableSetOf()) { uiModel -> uiModel.item.id },
            )
        }
    }

    fun invertSelection() {
        mutableState.update { state ->
            val all = state.list.orEmpty()
                .filterIsInstance<HistoryUiModel.Item>()
                .mapTo(mutableSetOf()) { uiModel -> uiModel.item.id }
            state.copy(selection = all - state.selection)
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = emptySet()) }
    }

    // ---- 多选加入书架 ----

    /**
     * 多选底栏的「添加到书架」：把选中历史背后的作品加入书架，已在书架的跳过。
     * 单部作品保留旧长按菜单的重复检查；批量逐部问一遍就太吵，与浏览源的批量加书架一致。
     */
    fun addSelectionToLibrary() {
        viewModelScope.launchIO {
            val mangas = selectedMangas().filterNot { it.favorite }
            if (mangas.isEmpty()) {
                clearSelection()
                return@launchIO
            }
            if (mangas.size == 1) {
                val manga = mangas.first()
                val duplicates = getDuplicateLibraryManga(manga)
                if (duplicates.isNotEmpty()) {
                    setDialog(Dialog.DuplicateManga(manga, duplicates))
                    return@launchIO
                }
            }
            decideCategoryAndAdd(mangas)
        }
    }

    /** 重复弹窗确认后，对单部作品继续走默认分类判断。 */
    fun addFavorite(manga: Manga) {
        decideCategoryAndAdd(listOf(manga))
    }

    fun showMigrateDialog(target: Manga, current: Manga) {
        mutableState.update { it.copy(dialog = Dialog.Migrate(target = target, current = current)) }
    }

    private fun decideCategoryAndAdd(mangas: List<Manga>) {
        viewModelScope.launchIO {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
            val defaultCategory = categories.find { it.id == defaultCategoryId }
            when {
                // 设置了默认分类：全部直接进默认分类
                defaultCategory != null -> addToLibrary(mangas, listOf(defaultCategory.id))

                // 自动「默认」或没有分类：不归入任何分类
                defaultCategoryId == 0L || categories.isEmpty() -> addToLibrary(mangas, emptyList())

                // 需要挑选分类：单部弹该作品的分类选择，批量弹一次共用选择
                mangas.size == 1 -> setDialog(
                    Dialog.ChangeCategory(mangas.first(), categories.mapAsCheckboxState { false }),
                )
                else -> setDialog(Dialog.ChangeCategoryBatch(categories.mapAsCheckboxState { false }))
            }
        }
    }

    /** 分类弹窗（单部）确认。 */
    fun addMangaToLibraryInCategories(manga: Manga, categoryIds: List<Long>) {
        addToLibrary(listOf(manga), categoryIds)
    }

    /** 分类弹窗（批量）确认：对当前选中背后的作品统一应用所选分类。 */
    fun addSelectionToLibraryInCategories(categoryIds: List<Long>) {
        viewModelScope.launchIO {
            val mangas = selectedMangas().filterNot { it.favorite }
            if (mangas.isEmpty()) {
                clearSelection()
                return@launchIO
            }
            addToLibrary(mangas, categoryIds)
        }
    }

    private fun addToLibrary(mangas: List<Manga>, categoryIds: List<Long>) {
        viewModelScope.launchNonCancellable {
            mangas.forEach { manga ->
                updateManga.awaitUpdateFavorite(manga.id, true)
                setMangaCategories.await(manga.id, categoryIds)
                addTracks.bindEnhancedTrackers(manga, sourceManager.getOrStub(manga.source))
            }
            clearSelection()
        }
    }

    /** 选中历史条目背后的作品，同一条目的多条历史归并为一部。 */
    private suspend fun selectedMangas(): List<Manga> {
        val ids = state.value.selection
        if (ids.isEmpty()) return emptyList()
        val mangaIds = state.value.list.orEmpty()
            .filterIsInstance<HistoryUiModel.Item>()
            .filter { it.item.id in ids }
            .map { it.item.mangaId }
            .distinct()
        return mangaIds.mapNotNull { getManga.await(it) }
    }

    /** 用户自建分类，不含系统分类。 */
    private suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    fun deleteSelection() {
        val historyIds = state.value.selection
        if (historyIds.isEmpty()) return
        clearSelection()
        deleteHistoryInternal(historyIds)
    }

    fun deleteHistory(historyId: Long) {
        deleteHistoryInternal(setOf(historyId))
    }

    // 历史的"删除"只是 last_read = 0，行本身和 time_read 都留在原处；撤销走 upsertHistory。
    // upsert 对时长是累加语义（time_read = time_read + 参数），载荷里必须传 0 才不会把
    // 阅读时长翻倍，只把删除前的 readAt 写回去，行就原样恢复。
    private fun deleteHistoryInternal(historyIds: Set<Long>) {
        viewModelScope.launchIO {
            val undoUpdates = state.value.list.orEmpty()
                .filterIsInstance<HistoryUiModel.Item>()
                .filter { it.item.id in historyIds }
                .mapNotNull { entry ->
                    entry.item.readAt?.let { readAt -> HistoryUpdate(entry.item.chapterId, readAt, 0L) }
                }
            historyRepository.resetHistoryByIds(historyIds.toList())
            _events.send(Event.HistoryDeleted(undoUpdates))
        }
    }

    fun restoreHistory(updates: List<HistoryUpdate>) {
        viewModelScope.launchIO {
            updates.forEach { historyRepository.upsertHistory(it) }
        }
    }

    fun removeAllHistory() {
        viewModelScope.launchIO {
            val result = historyRepository.deleteAllHistory()
            if (!result) return@launchIO
            _events.send(Event.HistoryCleared)
        }
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val list: List<HistoryUiModel>? = null,
        val selection: Set<Long> = emptySet(),
        val dialog: Dialog? = null,
    )

    sealed interface Dialog {
        data object DeleteAll : Dialog
        data class DuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class ChangeCategoryBatch(val initialSelection: List<CheckboxState<Category>>) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }

    sealed interface Event {
        data class OpenChapter(val chapter: Chapter?) : Event
        data object InternalError : Event
        data object HistoryCleared : Event
        data class HistoryDeleted(val updates: List<HistoryUpdate>) : Event
    }
}
