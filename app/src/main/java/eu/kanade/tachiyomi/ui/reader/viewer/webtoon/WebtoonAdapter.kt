package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.util.system.dpToPx

/**
 * RecyclerView Adapter used by this [viewer] to where [ViewerChapters] updates are posted.
 */
class WebtoonAdapter(val viewer: WebtoonViewer) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /**
     * List of currently set items.
     */
    var items: List<Any> = emptyList()
        private set

    var currentChapter: ReaderChapter? = null

    /**
     * Context that has been wrapped to use the correct theme values based on the
     * current app theme and reader background color
     */
    private var readerThemedContext = viewer.activity.createReaderThemeContext()

    /**
     * Updates this adapter with the given [chapters]. It handles setting a few pages of the
     * next/previous chapter to allow seamless transitions.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean) {
        val newItems = mutableListOf<Any>()

        // Forces chapter transition if there is missing chapters
        val prevHasMissingChapters = calculateChapterGap(chapters.currChapter, chapters.prevChapter) > 0
        val nextHasMissingChapters = calculateChapterGap(chapters.nextChapter, chapters.currChapter) > 0

        // Add previous chapter pages and transition.
        chapters.prevChapter?.pages?.let(newItems::addAll)

        // Skip transition page if the chapter is loaded & current page is not a transition page
        if (prevHasMissingChapters || forceTransition || chapters.prevChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
        } else {
            newItems.add(WebtoonChapterDivider(chapters.prevChapter.dividerKey(), chapters.currChapter.dividerKey()))
        }

        // Add current chapter.
        val currPages = chapters.currChapter.pages
        if (currPages != null) {
            newItems.addAll(currPages)
        }

        currentChapter = chapters.currChapter

        // Add next chapter transition and pages.
        if (nextHasMissingChapters || forceTransition || chapters.nextChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Next(chapters.currChapter, chapters.nextChapter))
        } else {
            newItems.add(WebtoonChapterDivider(chapters.currChapter.dividerKey(), chapters.nextChapter.dividerKey()))
        }

        chapters.nextChapter?.pages?.let(newItems::addAll)

        updateItems(newItems)
    }

    private fun updateItems(newItems: List<Any>) {
        val result = DiffUtil.calculateDiff(Callback(items, newItems))
        items = newItems
        result.dispatchUpdatesTo(this)
    }

    fun refresh() {
        readerThemedContext = viewer.activity.createReaderThemeContext()
    }

    /**
     * Returns the amount of items of the adapter.
     */
    override fun getItemCount(): Int {
        return items.size
    }

    /**
     * Returns the view type for the item at the given [position].
     */
    override fun getItemViewType(position: Int): Int {
        return when (val item = items[position]) {
            is ReaderPage -> PAGE_VIEW
            is ChapterTransition -> TRANSITION_VIEW
            is WebtoonChapterDivider -> CHAPTER_DIVIDER_VIEW
            else -> error("Unknown view type for ${item.javaClass}")
        }
    }

    /**
     * Creates a new view holder for an item with the given [viewType].
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            PAGE_VIEW -> {
                val view = ReaderPageImageView(readerThemedContext, isWebtoon = true)
                WebtoonPageHolder(view, viewer)
            }
            TRANSITION_VIEW -> {
                val view = LinearLayout(readerThemedContext)
                WebtoonTransitionHolder(view, viewer)
            }
            CHAPTER_DIVIDER_VIEW -> WebtoonChapterDividerHolder(createChapterDividerView())
            else -> error("Unknown view type")
        }
    }

    /**
     * Binds an existing view [holder] with the item at the given [position].
     */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is WebtoonPageHolder -> holder.bind(item as ReaderPage)
            is WebtoonTransitionHolder -> holder.bind(item as ChapterTransition)
            is WebtoonChapterDividerHolder -> Unit
        }
    }

    /**
     * Recycles an existing view [holder] before adding it to the view pool.
     */
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is WebtoonPageHolder -> holder.recycle()
            is WebtoonTransitionHolder -> holder.recycle()
        }
    }

    /**
     * Diff util callback used to dispatch delta updates instead of full dataset changes.
     */
    private class Callback(
        private val oldItems: List<Any>,
        private val newItems: List<Any>,
    ) : DiffUtil.Callback() {

        /**
         * Returns true if these two items are the same.
         */
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldItems[oldItemPosition]
            val newItem = newItems[newItemPosition]

            // Pages keep object identity; transitions are identified by type and endpoints, so a
            // Prev is never matched to a Next even when their chapters line up in reverse.
            return when {
                oldItem is ReaderPage -> oldItem === newItem
                oldItem is ChapterTransition && newItem is ChapterTransition -> oldItem == newItem
                oldItem is WebtoonChapterDivider && newItem is WebtoonChapterDivider -> oldItem == newItem
                else -> false
            }
        }

        /**
         * Returns true if the contents of the items are the same. Transitions may change their
         * destination/loading state without changing identity, so always rebind them; pages keep
         * object identity so their contents never need re-comparing here.
         */
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldItems[oldItemPosition] !is ChapterTransition
        }

        /**
         * Returns the size of the old list.
         */
        override fun getOldListSize(): Int {
            return oldItems.size
        }

        /**
         * Returns the size of the new list.
         */
        override fun getNewListSize(): Int {
            return newItems.size
        }
    }

    private fun createChapterDividerView(): View {
        val container = FrameLayout(readerThemedContext).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 13.dpToPx)
        }
        val line = View(readerThemedContext).apply {
            setBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOnSurface,
                    Color.GRAY,
                ),
            )
            alpha = 0.32f
        }
        container.addView(
            line,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dpToPx.coerceAtLeast(1)).apply {
                gravity = Gravity.CENTER
                marginStart = 24.dpToPx
                marginEnd = 24.dpToPx
            },
        )
        return container
    }
}

internal data class WebtoonChapterDivider(
    val fromChapterKey: String,
    val toChapterKey: String,
)

private class WebtoonChapterDividerHolder(view: View) : RecyclerView.ViewHolder(view)

private fun ReaderChapter.dividerKey(): String {
    return "${chapter.manga_id}\u0000${chapter.id}\u0000${chapter.url}"
}

/**
 * View holder type of a chapter page view.
 */
private const val PAGE_VIEW = 0

/**
 * View holder type of a chapter transition view.
 */
private const val TRANSITION_VIEW = 1
private const val CHAPTER_DIVIDER_VIEW = 2
