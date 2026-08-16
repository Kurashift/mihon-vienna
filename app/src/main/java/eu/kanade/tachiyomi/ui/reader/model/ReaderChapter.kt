package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import kotlinx.coroutines.flow.MutableStateFlow
import tachiyomi.core.common.util.system.logcat

data class ReaderChapter(val chapter: Chapter) {

    val stateFlow = MutableStateFlow<State>(State.Wait)
    var state: State
        get() = stateFlow.value
        set(value) {
            stateFlow.value = value
        }

    val pages: List<ReaderPage>?
        get() = (state as? State.Loaded)?.pages

    var pageLoader: PageLoader? = null

    var requestedPage: Int = 0

    private var references = 0

    constructor(chapter: tachiyomi.domain.chapter.model.Chapter) : this(chapter.toDbChapter())

    /**
     * The 0-based page index this chapter should open at, based on the stored reading progress.
     *
     * Chapters with stored progress resume at that page (fully-read chapters open at the last
     * page they finished on); unread chapters start at the first page.
     *
     * @param totalPages the number of pages of this chapter, defaults to the loaded page count.
     */
    fun resumePageIndex(totalPages: Int = pages?.size ?: 0): Int {
        val lastPageRead = chapter.last_page_read
        return when {
            chapter.read && totalPages > 0 -> totalPages - 1
            lastPageRead in 1..totalPages -> lastPageRead - 1
            else -> 0
        }
    }

    fun ref() {
        references++
    }

    fun unref() {
        references--
        if (references == 0) {
            if (pageLoader != null) {
                logcat { "Recycling chapter ${chapter.name}" }
            }
            pageLoader?.recycle()
            pageLoader = null
            state = State.Wait
        }
    }

    sealed interface State {
        data object Wait : State
        data object Loading : State
        data class Error(val error: Throwable) : State
        data class Loaded(val pages: List<ReaderPage>) : State
    }
}
