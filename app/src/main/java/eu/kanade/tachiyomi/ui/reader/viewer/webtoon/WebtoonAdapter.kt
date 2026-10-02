package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isVisible
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
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal

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
        val isLocalSource = viewer.activity.viewModel.manga?.isLocal() == true
        val prevHasMissingChapters = calculateChapterGap(
            chapters.currChapter,
            chapters.prevChapter,
            isLocalSource,
        ) > 0
        val nextHasMissingChapters = calculateChapterGap(
            chapters.nextChapter,
            chapters.currChapter,
            isLocalSource,
        ) > 0

        // Add previous chapter pages and transition.
        chapters.prevChapter?.pages?.let(newItems::addAll)

        // Skip transition page if the chapter is loaded & current page is not a transition page
        if (prevHasMissingChapters || forceTransition || chapters.prevChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
        } else {
            newItems.add(
                WebtoonChapterDivider(
                    fromChapterKey = chapters.prevChapter.dividerKey(),
                    toChapterKey = chapters.currChapter.dividerKey(),
                    fromLabel = chapters.prevChapter.dividerLabel(),
                    toLabel = chapters.currChapter.dividerLabel(),
                ),
            )
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
            newItems.add(
                WebtoonChapterDivider(
                    fromChapterKey = chapters.currChapter.dividerKey(),
                    toChapterKey = chapters.nextChapter.dividerKey(),
                    fromLabel = chapters.currChapter.dividerLabel(),
                    toLabel = chapters.nextChapter.dividerLabel(),
                ),
            )
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
            is WebtoonChapterDividerHolder -> holder.bind(item as WebtoonChapterDivider)
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
            // Dividers are identified by the chapters they separate, so a label update refreshes
            // them in place instead of removing and reinserting the separator.
            return when {
                oldItem is ReaderPage -> oldItem === newItem
                oldItem is ChapterTransition && newItem is ChapterTransition -> oldItem == newItem
                oldItem is WebtoonChapterDivider && newItem is WebtoonChapterDivider ->
                    oldItem.hasSameChapters(newItem)
                else -> false
            }
        }

        /**
         * Returns true if the contents of the items are the same. Transitions may change their
         * destination/loading state without changing identity, so always rebind them; pages keep
         * object identity so their contents never need re-comparing here. Dividers survive a
         * chapter reload but their labels can change, so they are compared by value.
         */
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldItems[oldItemPosition]
            return when (oldItem) {
                is ChapterTransition -> false
                else -> oldItem == newItems[newItemPosition]
            }
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

    /**
     * Builds the separator shown between two loaded chapters: the name of the chapter above, the
     * hairline, then the name of the chapter below, so reading straight through still tells the
     * reader where one chapter ends and the next begins.
     *
     * The two names are stacked so the hairline sits between them, mirroring the order the reader
     * scrolls through the chapters.
     */
    private fun createChapterDividerView(): ChapterDividerViews {
        val container = LinearLayout(readerThemedContext).apply {
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Pages have no gap of their own in this mode, so the separator carries the breathing
            // room that keeps the names off the artwork above and below it.
            setPadding(24.dpToPx, 4.dpToPx, 24.dpToPx, 4.dpToPx)
        }

        val onSurfaceColor = MaterialColors.getColor(
            container,
            com.google.android.material.R.attr.colorOnSurface,
            Color.GRAY,
        )

        val prevLabel = createDividerLabel(onSurfaceColor, alpha = 0.55f)
        val nextLabel = createDividerLabel(onSurfaceColor, alpha = 0.75f)

        val line = View(readerThemedContext).apply {
            setBackgroundColor(onSurfaceColor)
            alpha = 0.32f
        }

        val labelParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        val lineParams = LinearLayout.LayoutParams(MATCH_PARENT, 1.dpToPx.coerceAtLeast(1)).apply {
            setMargins(0, 3.dpToPx, 0, 3.dpToPx)
        }

        container.addView(prevLabel, labelParams)
        container.addView(line, lineParams)
        container.addView(nextLabel, labelParams)

        return ChapterDividerViews(container, prevLabel, nextLabel)
    }

    private fun createDividerLabel(color: Int, alpha: Float): AppCompatTextView {
        return AppCompatTextView(readerThemedContext).apply {
            maxWidth = 460.dpToPx
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DIVIDER_LABEL_TEXT_SP)
            setTextColor(color)
            includeFontPadding = false
            gravity = Gravity.CENTER_HORIZONTAL
            this.alpha = alpha
        }
    }

    /**
     * Name shown for a chapter on the separator, following the same translated-title display mode
     * as the reader top bar and the chapter list.
     */
    private fun ReaderChapter.dividerLabel(): String {
        val translatedName = translatedNameOrNull ?: return chapter.name
        return when (viewer.activity.viewModel.manga?.displayMode) {
            Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY,
            Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL,
            -> translatedName
            else -> chapter.name
        }
    }
}

internal data class WebtoonChapterDivider(
    val fromChapterKey: String,
    val toChapterKey: String,
    val fromLabel: String,
    val toLabel: String,
) {
    /**
     * Identity of the separator, independent of the labels: the same two chapters always share one
     * separator even when a translated title arrives later.
     */
    fun hasSameChapters(other: WebtoonChapterDivider): Boolean {
        return fromChapterKey == other.fromChapterKey && toChapterKey == other.toChapterKey
    }
}

private class ChapterDividerViews(
    val container: LinearLayout,
    val prevLabel: AppCompatTextView,
    val nextLabel: AppCompatTextView,
)

private class WebtoonChapterDividerHolder(
    private val views: ChapterDividerViews,
) : RecyclerView.ViewHolder(views.container) {

    fun bind(divider: WebtoonChapterDivider) {
        views.prevLabel.text = divider.fromLabel
        views.prevLabel.isVisible = divider.fromLabel.isNotEmpty()
        views.nextLabel.text = divider.toLabel
        views.nextLabel.isVisible = divider.toLabel.isNotEmpty()
    }
}

private fun ReaderChapter.dividerKey(): String {
    return "${chapter.manga_id}\u0000${chapter.id}\u0000${chapter.url}"
}

/**
 * Text size of the chapter names on the separator. Deliberately not tiny: these names are mostly
 * Chinese, and CJK glyphs become unreadable well before Latin text does.
 */
private const val DIVIDER_LABEL_TEXT_SP = 11f

/**
 * View holder type of a chapter page view.
 */
private const val PAGE_VIEW = 0

/**
 * View holder type of a chapter transition view.
 */
private const val TRANSITION_VIEW = 1
private const val CHAPTER_DIVIDER_VIEW = 2
