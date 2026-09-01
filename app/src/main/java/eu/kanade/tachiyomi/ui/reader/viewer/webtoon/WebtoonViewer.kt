package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.animation.ObjectAnimator
import android.graphics.PointF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewTreeObserver
import androidx.core.app.ActivityCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [RecyclerView].
 */
class WebtoonViewer(val activity: ReaderActivity, val isContinuous: Boolean = true) : Viewer {

    val downloadManager: DownloadManager by injectLazy()

    private val scope = MainScope()

    /**
     * Recycler view used by this viewer.
     */
    val recycler = WebtoonRecyclerView(activity)

    /**
     * Frame containing the recycler view.
     */
    private val frame = WebtoonFrame(activity)

    /**
     * Distance to scroll when the user taps on one side of the recycler view.
     */
    private val scrollDistance = activity.resources.displayMetrics.heightPixels * 3 / 4

    /**
     * Layout manager of the recycler view.
     */
    private val layoutManager = WebtoonLayoutManager(activity, scrollDistance)

    /**
     * Configuration used by this viewer, like allow taps, or crop image borders.
     */
    val config = WebtoonConfig(scope)

    /**
     * Adapter of the recycler view.
     */
    private val adapter = WebtoonAdapter(this)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentPage: Any? = null

    /**
     * State of the reader's first-frame alignment. The frame starts transparent and
     * non-interactive, then is shown only after the target image has decoded and settled.
     */
    private var positioning: InitialPositioning = InitialPositioning.NotStarted

    /**
     * Set true while the explicit initial target remains authoritative. Late image/layout scroll
     * callbacks cannot replace it with a center-derived page; the lock is released by the first
     * real user navigation.
     */
    private var suppressInitialScrollCallbacks = false

    /**
     * True while an adapter refresh is restoring the same visible item to the same screen offset.
     * Scroll callbacks caused by that layout pass must not select a page or switch chapters.
     */
    private var restoringViewportAnchor = false

    private var viewportAnchorGeneration = 0

    /**
     * Height of the target page observed on the previous alignment pass, used to detect that the
     * decoded height has settled before revealing. -1 means no prior observation yet.
     */
    private var previousInitialPageHeight = -1

    private var stableInitialLayoutFrames = 0

    private var initialAnchorPageIdentity: PageIdentity? = null
    private var initialAnchorPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var initialGesturePageIdentity: PageIdentity? = null
    private var lastScrollDirection = 0

    private val threshold: Int =
        Injekt.get<ReaderPreferences>()
            .readerHideThreshold
            .get()
            .threshold

    init {
        recycler.setItemViewCacheSize(RECYCLER_VIEW_CACHE_SIZE)
        recycler.doubleTapAnimDuration = config.doubleTapAnimDuration
        // Laid out but transparent and non-interactive until the resumed page is aligned to its
        // final height, so the page image can decode before anything shows and there is no jump.
        recycler.isVisible = true
        frame.isEnabled = false
        recycler.isEnabled = false
        recycler.alpha = 0f
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.isFocusable = false
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    // Nothing may update progress until the initial frame is revealed.
                    if (positioning != InitialPositioning.Ready) return
                    if (recycler.isZoomAnimating) return

                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        viewportAnchorGeneration++
                        restoringViewportAnchor = false
                        releaseInitialPageAnchor()
                    }
                    if (restoringViewportAnchor) return
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        // Ignore programmatic and late layout settling until the user deliberately
                        // navigates away from the explicit initial target.
                        if (suppressInitialScrollCallbacks) return
                        val candidate = currentVisibleItem(lastScrollDirection)
                        val item = constrainToScrollDirection(candidate, lastScrollDirection)
                        val userInitiated = lastScrollDirection != 0
                        selectCurrentItem(item, userInitiated = userInitiated)
                        if (item is ReaderPage) {
                            activity.onScrollSettled(item, userInitiated = userInitiated)
                        }
                        lastScrollDirection = 0
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (positioning != InitialPositioning.Ready) return
                    if (recycler.isZoomAnimating) return
                    if (suppressInitialScrollCallbacks) return
                    if (restoringViewportAnchor) return
                    if (dy != 0) {
                        val direction = when {
                            recycler.scrollGestureDirection != 0 -> recycler.scrollGestureDirection
                            lastScrollDirection != 0 -> lastScrollDirection
                            recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE -> dy
                            else -> 0
                        }
                        if (direction != 0) {
                            lastScrollDirection = direction
                            onScrolled(scrollDelta = direction)
                        }
                    }

                    if ((dy > threshold || dy < -threshold) && activity.viewModel.state.value.menuVisible) {
                        activity.hideMenu()
                    }
                    if (dy < 0) {
                        val firstIndex = layoutManager.findFirstVisibleItemPosition()
                        val firstItem = adapter.items.getOrNull(firstIndex)
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            activity.requestPreloadChapter(firstItem.to)
                        }
                    }
                    val lastIndex = layoutManager.findLastEndVisibleItemPosition()
                    val lastItem = adapter.items.getOrNull(lastIndex)
                    if (lastItem is ChapterTransition.Next && lastItem.to == null) {
                        activity.showMenu()
                    }
                }
            },
        )
        recycler.tapListener = tap@{ event ->
            // Right after a swipe jump the activity is re-created; ignore taps that
            // are just residue of the jump so the menu can't pop open by accident.
            if (activity.isSwipeJumpTapSuppressed()) return@tap
            // While the reader menu is open, a tap anywhere just closes it instead of scrolling.
            if (activity.viewModel.state.value.menuVisible) {
                activity.hideMenu()
                return@tap
            }
            // Normalize by the view's layout size. In landscape webtoon the recycler is
            // scaled and translated, so raw screen coordinates would fall outside the
            // navigation zones. The event already carries local, unscaled content
            // coordinates, which map to [0, 1] for both axes and every scale.
            val pos = PointF(
                event.x / recycler.width,
                event.y / recycler.height,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
                NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
            }
        }
        recycler.longTapListener = f@{ event ->
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        activity.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }
        recycler.horizontalSwipeListener = { leftSwipe ->
            activity.onHorizontalSwipe(leftSwipe)
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.themeChangedListener = {
            ActivityCompat.recreate(activity)
        }

        config.doubleTapZoomChangedListener = {
            frame.doubleTapZoom = it
        }

        config.zoomPropertyChangedListener = {
            frame.zoomOutDisabled = it
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
        val nextChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter

        // Allow preload for
        // 1. Going between pages of same chapter
        // 2. Next chapter page
        return when (page.chapter) {
            (currentPage as? ReaderPage)?.chapter -> true
            nextChapter -> true
            else -> false
        }
    }

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return frame
    }

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    override fun destroy() {
        super.destroy()
        releaseInitialPageAnchor(clearSelectionGuard = true)
        scope.cancel()
    }

    /**
     * Called from the RecyclerView listener when a [page] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onPageSelected(
        page: ReaderPage,
        allowPreload: Boolean,
        userInitiated: Boolean,
    ) {
        val pages = page.chapter.pages ?: return
        logcat { "onPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page, userInitiated)

        if (allowPreload) {
            requestAdjacentPreload(page)
        }
    }

    /**
     * Starts loading an adjacent chapter while the reader is still several pages away from its
     * separator. Initial opening calls this only after the first frame is visible and stable.
     */
    private fun requestAdjacentPreload(page: ReaderPage) {
        val pages = page.chapter.pages ?: return
        if (page.chapter != adapter.currentChapter) return

        if (page.index < ADJACENT_PRELOAD_PAGE_DISTANCE) {
            val previousChapter = (adapter.items.firstOrNull() as? ChapterTransition.Prev)?.to
            if (previousChapter != null) {
                activity.requestPreloadChapter(previousChapter)
            }
        }

        if (pages.lastIndex - page.index < ADJACENT_PRELOAD_PAGE_DISTANCE) {
            val lastItem = adapter.items.lastOrNull()
            val nextChapter = (lastItem as? ChapterTransition.Next)?.to
                ?: (lastItem as? ReaderPage)?.chapter
            if (nextChapter != null && nextChapter != page.chapter) {
                activity.requestPreloadChapter(nextChapter)
            }
        }
    }

    /**
     * Called from the RecyclerView listener when a [transition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active. On the very first call it also
     * selects the target page and starts the initial positioning (waiting for that page's image).
     */
    override fun setChapters(chapters: ViewerChapters) {
        val viewportAnchor = if (positioning == InitialPositioning.Ready) captureViewportAnchor() else null
        if (viewportAnchor != null) {
            restoringViewportAnchor = true
        }
        // Continuous reading drops a separator as soon as its adjacent chapter is ready. Keep it
        // only while the user is actually resting on that separator so an adapter refresh cannot
        // remove content from under their finger. Pager modes still honor the global preference.
        val forceTransition = currentPage is ChapterTransition
        val activeItem = currentPage
        adapter.setChapters(chapters, forceTransition)
        currentPage = findMatchingAdapterItem(activeItem) ?: activeItem
        if (viewportAnchor != null) {
            restoreViewportAnchor(viewportAnchor)
        }
        if (positioning == InitialPositioning.NotStarted) {
            val pages = chapters.currChapter.pages ?: return
            val targetPage = pages[min(chapters.currChapter.requestedPage, pages.lastIndex)]
            val position = adapter.items.indexOf(targetPage)
            if (position == -1) {
                positioning = InitialPositioning.Ready
                recycler.alpha = 1f
                frame.isEnabled = true
                recycler.isEnabled = true
                activity.onViewerContentReady()
                return
            }
            positioning = InitialPositioning.Waiting(targetPage)
            layoutManager.scrollToPositionWithOffset(position, 0)

            scope.launch {
                // Keep fast opens silent, but reveal the loading indicator before a genuinely slow
                // decode or missing layout callback can make the previous reader look frozen.
                delay(INITIAL_LOADING_REVEAL_DELAY_MILLIS)
                if (positioning != InitialPositioning.Ready) {
                    activity.onViewerLoadingDelayed()
                }
            }
            scope.launch {
                // A very slow or missing decode callback must not leave the reader transparent
                // forever. The fallback still runs the normal measured alignment; it never reveals
                // the page directly at an unchecked offset.
                delay(INITIAL_REVEAL_WATCHDOG_MILLIS)
                forceInitialAlignment(targetPage)
            }
        }
    }

    /**
     * Captures a real laid-out item and its measured top before pages are inserted or removed.
     * The active item is preferred so chapter preloading cannot move what the user is looking at.
     */
    private fun captureViewportAnchor(): ViewportAnchor? {
        val activePosition = adapterPositionOf(currentPage)
        val activeView = activePosition
            .takeIf { it != -1 }
            ?.let(layoutManager::findViewByPosition)
        if (activeView != null) {
            return ViewportAnchor(currentPage!!, activeView.top)
        }

        var fallback: ViewportAnchor? = null
        for (index in 0 until recycler.childCount) {
            val child = recycler.getChildAt(index)
            val position = recycler.getChildAdapterPosition(child)
            val item = adapter.items.getOrNull(position) ?: continue
            val anchor = ViewportAnchor(item, child.top)
            if (item is ReaderPage) return anchor
            if (fallback == null) fallback = anchor
        }
        return fallback
    }

    /**
     * Restores a captured item to its exact measured offset in the first layout containing the
     * updated chapter list. A final pixel correction handles decorations and late layout offsets.
     */
    private fun restoreViewportAnchor(anchor: ViewportAnchor) {
        val position = adapterPositionOf(anchor.item)
        if (position == -1) {
            restoringViewportAnchor = false
            return
        }

        val generation = ++viewportAnchorGeneration
        recycler.doOnNextLayout {
            if (generation != viewportAnchorGeneration) return@doOnNextLayout

            val restoredPosition = adapterPositionOf(anchor.item)
            val restoredView = restoredPosition
                .takeIf { it != -1 }
                ?.let(layoutManager::findViewByPosition)
            if (restoredView != null) {
                val correction = restoredView.top - anchor.top
                if (correction != 0) {
                    recycler.scrollBy(0, correction)
                }
            }

            recycler.postOnAnimation {
                if (generation == viewportAnchorGeneration) {
                    restoringViewportAnchor = false
                }
            }
        }
        layoutManager.scrollToPositionWithOffset(position, anchor.top)
    }

    /** Restarts the measured alignment flow if a decode or layout callback was lost. */
    private fun forceInitialAlignment(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        val holder = position
            .takeIf { it != -1 }
            ?.let { recycler.findViewHolderForAdapterPosition(it) as? WebtoonPageHolder }
        if (holder?.isImageLayoutReady != true) {
            activity.onViewerLoadingDelayed()
            return
        }
        when (positioning) {
            InitialPositioning.Waiting(page) -> alignInitialPage(page)
            InitialPositioning.Aligning(page) -> alignInitialPagePass(
                page,
                initialPageRemainingPasses = MAX_INITIAL_ALIGN_PASSES,
            )
            else -> Unit
        }
    }

    /**
     * Invoked once a page's image has actually decoded. If it is the page the reader is waiting on,
     * aligns the reader to that page's final height and reveals it.
     */
    fun onPageImageLoaded(page: ReaderPage) {
        val waiting = positioning as? InitialPositioning.Waiting ?: return
        if (waiting.page == page) {
            alignInitialPage(page)
        }
    }

    /**
     * Aligns the resumed page to its resting offset (top for the first page, bottom for the last,
     * centered otherwise) and reveals the reader once the alignment layout has settled, so the
     * frame shown is already correct. Idempotent: only the first call for a given Waiting page
     * takes effect, so the decode callback and the timeout cannot double-fire.
     *
     * Runs a bounded number of layout passes: each pass re-reads the target page's current height
     * and layout top, recomputes the desired offset, and only reveals once the height has
     * stabilized and the page sits within one pixel of its target. This converges on the page's
     * final decoded height even when the first pass still observes a stale placeholder height.
     */
    private fun alignInitialPage(page: ReaderPage) {
        if (positioning != InitialPositioning.Waiting(page)) return
        positioning = InitialPositioning.Aligning(page)
        previousInitialPageHeight = -1
        stableInitialLayoutFrames = 0
        alignInitialPagePass(page, initialPageRemainingPasses = MAX_INITIAL_ALIGN_PASSES)
    }

    /**
     * One alignment pass. Waits for the next layout, then either reveals (when height settled and
     * the page is within tolerance of its target top) or corrects the measured top delta and
     * recurses. The pixel correction runs after layout so LinearLayoutManager cannot replace the
     * requested anchor while filling the chapter transition at the end of its layout pass.
     */
    private fun alignInitialPagePass(page: ReaderPage, initialPageRemainingPasses: Int) {
        if (positioning != InitialPositioning.Aligning(page)) return
        val exhausted = initialPageRemainingPasses <= 0
        if (exhausted) {
            finishInitialAlignment(page)
            return
        }

        recycler.doOnNextLayout {
            if (positioning != InitialPositioning.Aligning(page)) return@doOnNextLayout

            val alignedPosition = adapter.items.indexOf(page)
            val holder = alignedPosition
                .takeIf { it != -1 }
                ?.let { recycler.findViewHolderForAdapterPosition(it) }
            val pageHeight = holder?.itemView?.height ?: 0
            val holderTop: Int? = holder?.itemView?.top

            if (holder != null && recycler.ensureInitialLandscapeScale(holder.itemView)) {
                previousInitialPageHeight = -1
                stableInitialLayoutFrames = 0
                recycler.postOnAnimation {
                    alignInitialPagePass(page, initialPageRemainingPasses - 1)
                }
                return@doOnNextLayout
            }

            val desiredTop = targetTop(page, pageHeight)

            val heightStable = pageHeight > 0 &&
                previousInitialPageHeight == pageHeight
            val positionStable = holderTop != null && abs(holderTop - desiredTop) <= 1

            if (heightStable && positionStable) {
                stableInitialLayoutFrames++
                if (stableInitialLayoutFrames >= REQUIRED_INITIAL_STABLE_FRAMES) {
                    revealNow(page)
                    return@doOnNextLayout
                }
            } else {
                stableInitialLayoutFrames = 0
            }

            previousInitialPageHeight = pageHeight

            recycler.postOnAnimation {
                if (positioning != InitialPositioning.Aligning(page)) return@postOnAnimation

                val currentPosition = adapter.items.indexOf(page)
                val currentHolder = currentPosition
                    .takeIf { it != -1 }
                    ?.let { recycler.findViewHolderForAdapterPosition(it) }
                if (currentHolder == null) {
                    layoutManager.scrollToPositionWithOffset(currentPosition.coerceAtLeast(0), 0)
                } else {
                    val currentHeight = currentHolder.itemView.height
                    val correction = currentHolder.itemView.top - targetTop(page, currentHeight)
                    if (correction != 0) {
                        recycler.scrollBy(0, correction)
                    }
                }
                alignInitialPagePass(page, initialPageRemainingPasses - 1)
            }
        }
        recycler.requestLayout()
    }

    /**
     * Applies one last correction from the currently measured holder, then reveals on the next
     * display frame. This is only reached when the bounded cross-frame stability loop is exhausted.
     */
    private fun finishInitialAlignment(page: ReaderPage) {
        if (positioning != InitialPositioning.Aligning(page)) return
        val position = adapter.items.indexOf(page)
        val holder = position
            .takeIf { it != -1 }
            ?.let { recycler.findViewHolderForAdapterPosition(it) }
        if (holder != null) {
            val correction = holder.itemView.top - targetTop(page, holder.itemView.height)
            if (correction != 0) {
                recycler.scrollBy(0, correction)
            }
        }
        recycler.postOnAnimation {
            revealNow(page, acceptClampedPosition = true)
        }
    }

    /**
     * The viewport-top distance the page should rest at: top-aligned for the first page,
     * bottom-aligned for the last, centered otherwise. A short last page shifts down by a positive
     * offset so its bottom sits on the viewport bottom.
     */
    private fun targetTop(page: ReaderPage, pageHeight: Int): Int {
        val pages = page.chapter.pages ?: return recycler.paddingTop
        val viewportTop = recycler.paddingTop
        val viewportBottom = recycler.height - recycler.paddingBottom
        return WebtoonPageSelection.resolveInitialPageTop(
            pageIndex = page.index,
            lastPageIndex = pages.lastIndex,
            pageExtent = pageHeight,
            viewportStart = viewportTop,
            viewportEnd = viewportBottom,
        )
    }

    /**
     * Keeps the explicit resume page at its resting offset while late image measurements settle.
     * A displaced frame is cancelled and corrected before it can reach the display.
     */
    private fun keepInitialPageAnchored(page: ReaderPage) {
        initialAnchorPageIdentity = page.pageIdentity()
        if (initialAnchorPreDrawListener != null) return

        val listener = ViewTreeObserver.OnPreDrawListener {
            val anchorIdentity = initialAnchorPageIdentity
            if (!suppressInitialScrollCallbacks || anchorIdentity == null) {
                return@OnPreDrawListener true
            }

            val position = adapter.items.indexOfFirst { item ->
                item is ReaderPage && item.pageIdentity() == anchorIdentity
            }
            val anchorPage = adapter.items.getOrNull(position) as? ReaderPage
                ?: return@OnPreDrawListener true
            val holder = recycler.findViewHolderForAdapterPosition(position)
            if (holder == null) {
                layoutManager.scrollToPositionWithOffset(position, 0)
                return@OnPreDrawListener false
            }

            val pageHeight = holder.itemView.height
            if (pageHeight <= 0) return@OnPreDrawListener false
            val correction = holder.itemView.top - targetTop(anchorPage, pageHeight)
            if (abs(correction) <= 1) {
                true
            } else {
                recycler.scrollBy(0, correction)
                false
            }
        }
        initialAnchorPreDrawListener = listener
        recycler.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun releaseInitialPageAnchor(clearSelectionGuard: Boolean = false) {
        suppressInitialScrollCallbacks = false
        initialAnchorPageIdentity = null
        if (clearSelectionGuard) {
            initialGesturePageIdentity = null
        }
        initialAnchorPreDrawListener?.let { listener ->
            recycler.viewTreeObserver
                .takeIf { it.isAlive }
                ?.removeOnPreDrawListener(listener)
        }
        initialAnchorPreDrawListener = null
    }

    /**
     * Marks the reader ready, makes it visible and interactive, and reports the resumed page and
     * first-frame readiness. No animation is applied: the view was laid out transparent and is
     * simply shown once aligned, so the reveal itself never flashes.
     */
    private fun revealNow(page: ReaderPage, acceptClampedPosition: Boolean = false) {
        if (positioning != InitialPositioning.Aligning(page)) return
        if (activity.isFinishing || activity.isDestroyed) return

        val position = adapter.items.indexOf(page)
        val holder = position
            .takeIf { it != -1 }
            ?.let { recycler.findViewHolderForAdapterPosition(it) }
        val pageHeight = holder?.itemView?.height ?: 0
        val desiredTop = targetTop(page, pageHeight)
        val correction = holder?.itemView?.top?.minus(desiredTop)
        val positionIsReady = WebtoonPageSelection.isInitialPositionReady(
            pageExtent = pageHeight,
            currentStart = holder?.itemView?.top,
            targetStart = desiredTop,
            acceptClampedPosition = acceptClampedPosition,
        )
        if (!positionIsReady) {
            if (correction != null && correction != 0) {
                recycler.scrollBy(0, correction)
            } else if (position != -1) {
                layoutManager.scrollToPositionWithOffset(position, desiredTop)
            }
            stableInitialLayoutFrames = 0
            previousInitialPageHeight = pageHeight
            recycler.postOnAnimation {
                alignInitialPagePass(page, initialPageRemainingPasses = MAX_INITIAL_ALIGN_PASSES)
            }
            return
        }

        positioning = InitialPositioning.Ready
        currentPage = page
        initialGesturePageIdentity = page.pageIdentity()
        suppressInitialScrollCallbacks = true
        keepInitialPageAnchored(page)
        activity.hideLoadingIndicator()
        recycler.alpha = 1f
        frame.isEnabled = true
        recycler.isEnabled = true
        activity.onInitialPageSelected(page)
        activity.onViewerContentReady()
        recycler.postOnAnimation {
            val activePage = findMatchingAdapterItem(page) as? ReaderPage
            if (positioning == InitialPositioning.Ready && activePage != null &&
                sameAdapterItem(currentPage, activePage)
            ) {
                requestAdjacentPreload(activePage)
            }
        }
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            viewportAnchorGeneration++
            restoringViewportAnchor = false
            releaseInitialPageAnchor(clearSelectionGuard = true)
            layoutManager.scrollToPositionWithOffset(position, 0)
            // Explicit page navigation is a settled position, so persist progress right away.
            currentPage = page
            activity.onPageSelected(page)
            activity.onScrollSettled(page)
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    fun onScrolled(pos: Int? = null, scrollDelta: Int = 0) {
        val candidate = when {
            pos != null -> adapter.items.getOrNull(pos)
            else -> currentVisibleItem(scrollDelta)
        }
        val item = if (pos == null) {
            constrainToScrollDirection(candidate, scrollDelta)
        } else {
            candidate
        }
        selectCurrentItem(item, userInitiated = true)
    }

    private fun constrainToScrollDirection(candidate: Any?, scrollDelta: Int): Any? {
        if (candidate == null || scrollDelta == 0) return candidate
        val currentPosition = adapterPositionOf(currentPage)
        val candidatePosition = adapterPositionOf(candidate)
        val selectedPosition = WebtoonPageSelection.resolveDirectionalPosition(
            currentPosition = currentPosition,
            candidatePosition = candidatePosition,
            scrollDelta = scrollDelta,
        )
        return if (selectedPosition == currentPosition) currentPage else candidate
    }

    private fun selectCurrentItem(item: Any?, userInitiated: Boolean = false) {
        if (item == null) return
        if (sameAdapterItem(currentPage, item)) {
            currentPage = item
            return
        }
        if (item !is ReaderPage || item.pageIdentity() != initialGesturePageIdentity) {
            initialGesturePageIdentity = null
        }
        val allowPreload = checkAllowPreload(item as? ReaderPage)
        currentPage = item
        when (item) {
            is ReaderPage -> onPageSelected(item, allowPreload, userInitiated)
            is ChapterTransition -> onTransitionSelected(item)
        }
    }

    /**
     * Returns a boundary page when it is resting at its chapter edge, otherwise the item covering
     * the viewport center. The visibility threshold keeps short landscape pages authoritative at
     * the chapter boundary without letting a barely visible adjacent chapter steal selection.
     */
    private fun currentVisibleItem(scrollDelta: Int = 0): Any? {
        guardedInitialGesturePage()?.let { return it }

        val pages = adapter.currentChapter?.pages
        if (!pages.isNullOrEmpty()) {
            val viewportTop = recycler.paddingTop
            val viewportBottom = recycler.height - recycler.paddingBottom
            val firstPage = pages.first()
            val firstPosition = adapter.items.indexOf(firstPage)
            val firstView = firstPosition
                .takeIf { it != -1 }
                ?.let(layoutManager::findViewByPosition)
            val leadingTransitionReached = adapter.items.getOrNull(firstPosition - 1) is ChapterTransition.Prev &&
                layoutManager.findViewByPosition(firstPosition - 1)?.let { transitionView ->
                    layoutManager.getDecoratedTop(transitionView) <= viewportTop
                } == true
            if (
                firstView != null &&
                WebtoonPageSelection.isStartBoundaryActive(
                    pageStart = layoutManager.getDecoratedTop(firstView),
                    pageEnd = layoutManager.getDecoratedBottom(firstView),
                    viewportStart = viewportTop,
                    viewportEnd = viewportBottom,
                    contentStartReached = !recycler.canScrollVertically(-1) || leadingTransitionReached,
                )
            ) {
                return firstPage
            }

            val lastPage = pages.last()
            val lastPosition = adapter.items.indexOf(lastPage)
            val lastView = lastPosition
                .takeIf { it != -1 }
                ?.let(layoutManager::findViewByPosition)
            val trailingTransitionReached = adapter.items.getOrNull(lastPosition + 1) is ChapterTransition.Next &&
                layoutManager.findViewByPosition(lastPosition + 1)?.let { transitionView ->
                    layoutManager.getDecoratedBottom(transitionView) >= viewportBottom
                } == true
            if (
                lastView != null &&
                WebtoonPageSelection.isEndBoundaryActive(
                    pageStart = layoutManager.getDecoratedTop(lastView),
                    pageEnd = layoutManager.getDecoratedBottom(lastView),
                    viewportStart = viewportTop,
                    viewportEnd = viewportBottom,
                    contentEndReached = trailingTransitionReached,
                )
            ) {
                return lastPage
            }
        }

        val centerPosition = layoutManager.findCenteredVisibleItemPosition()
        val centerItem = adapter.items.getOrNull(centerPosition)
        if (centerItem is WebtoonChapterDivider) {
            return readerPageBesideDivider(centerPosition, scrollDelta)
                ?: (currentPage as? ReaderPage)
        }
        if (scrollDelta <= 0 || centerItem !is ReaderPage) {
            return centerItem ?: currentPage
        }

        val currentPosition = adapterPositionOf(currentPage)
        if (centerPosition <= currentPosition) return centerItem

        val visiblePages = buildList {
            for (index in 0 until recycler.childCount) {
                val child = recycler.getChildAt(index)
                val position = recycler.getChildAdapterPosition(child)
                if (adapter.items.getOrNull(position) !is ReaderPage) continue
                add(
                    WebtoonPageSelection.VisiblePage(
                        position = position,
                        start = layoutManager.getDecoratedTop(child),
                        end = layoutManager.getDecoratedBottom(child),
                    ),
                )
            }
        }
        val selectedPosition = WebtoonPageSelection.resolveForwardPage(
            currentPosition = currentPosition,
            centerPosition = centerPosition,
            pages = visiblePages,
            viewportStart = recycler.paddingTop,
            viewportEnd = recycler.height - recycler.paddingBottom,
        )
        return adapter.items.getOrNull(selectedPosition) ?: currentPage
    }

    /**
     * Keeps the explicitly opened page authoritative through the beginning of the first gesture.
     * This is identity-based so an adjacent-chapter refresh cannot make the page disappear from
     * selection merely because its ReaderPage object was replaced.
     */
    private fun guardedInitialGesturePage(): ReaderPage? {
        val identity = initialGesturePageIdentity ?: return null
        val position = adapter.items.indexOfFirst { item ->
            item is ReaderPage && item.pageIdentity() == identity
        }
        val page = adapter.items.getOrNull(position) as? ReaderPage
        val view = position.takeIf { it >= 0 }?.let(layoutManager::findViewByPosition)
        if (
            page == null ||
            view == null ||
            !WebtoonPageSelection.isPageActive(
                pageStart = layoutManager.getDecoratedTop(view),
                pageEnd = layoutManager.getDecoratedBottom(view),
                viewportStart = recycler.paddingTop,
                viewportEnd = recycler.height - recycler.paddingBottom,
            )
        ) {
            initialGesturePageIdentity = null
            return null
        }
        return page
    }

    private fun adapterPositionOf(item: Any?): Int {
        if (item == null) return -1
        return when (item) {
            is ReaderPage -> {
                val identity = item.pageIdentity()
                adapter.items.indexOfFirst { candidate ->
                    candidate is ReaderPage && candidate.pageIdentity() == identity
                }
            }
            // A separator is the same one as long as it separates the same chapters; its labels
            // may have been refreshed since the reader last looked at it.
            is WebtoonChapterDivider -> {
                adapter.items.indexOfFirst { candidate ->
                    candidate is WebtoonChapterDivider && candidate.hasSameChapters(item)
                }
            }
            else -> adapter.items.indexOf(item)
        }
    }

    private fun findMatchingAdapterItem(item: Any?): Any? {
        val position = adapterPositionOf(item)
        return adapter.items.getOrNull(position)
    }

    private fun sameAdapterItem(first: Any?, second: Any): Boolean {
        return if (first is ReaderPage && second is ReaderPage) {
            first.pageIdentity() == second.pageIdentity()
        } else {
            first == second
        }
    }

    private fun ReaderPage.pageIdentity(): PageIdentity {
        return PageIdentity(
            mangaId = chapter.chapter.manga_id,
            chapterId = chapter.chapter.id,
            chapterUrl = chapter.chapter.url,
            pageIndex = index,
        )
    }

    private fun readerPageBesideDivider(position: Int, scrollDelta: Int): ReaderPage? {
        val preferredRange = if (scrollDelta < 0) {
            (position - 1) downTo 0
        } else {
            (position + 1) until adapter.items.size
        }
        preferredRange.forEach { index ->
            (adapter.items[index] as? ReaderPage)?.let { return it }
        }

        val fallbackRange = if (scrollDelta < 0) {
            (position + 1) until adapter.items.size
        } else {
            (position - 1) downTo 0
        }
        fallbackRange.forEach { index ->
            (adapter.items[index] as? ReaderPage)?.let { return it }
        }
        return null
    }

    /**
     * Scrolls up by [scrollDistance].
     */
    private fun scrollUp() {
        releaseInitialPageAnchor()
        lastScrollDirection = -1
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, -scrollDistance)
        } else {
            recycler.scrollBy(0, -scrollDistance)
            lastScrollDirection = 0
        }
    }

    /**
     * Scrolls down by [scrollDistance].
     */
    private fun scrollDown() {
        releaseInitialPageAnchor()
        lastScrollDirection = 1
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, scrollDistance)
        } else {
            recycler.scrollBy(0, scrollDistance)
            lastScrollDirection = 0
        }
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollDown() else scrollUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollUp() else scrollDown()
                }
            }
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> if (isUp) scrollUp()
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> if (isUp) scrollDown()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    /**
     * Notifies adapter of changes around the current page to trigger a relayout in the recycler.
     * Used when an image configuration is changed.
     */
    private fun refreshAdapter() {
        val position = layoutManager.findLastEndVisibleItemPosition()
        adapter.refresh()
        adapter.notifyItemRangeChanged(
            max(0, position - 3),
            min(position + 3, adapter.itemCount - 1),
        )
    }

    /**
     * Lifecycle of the reader's first-frame alignment.
     */
    private sealed interface InitialPositioning {
        data object NotStarted : InitialPositioning
        data class Waiting(val page: ReaderPage) : InitialPositioning
        data class Aligning(val page: ReaderPage) : InitialPositioning
        data object Ready : InitialPositioning
    }

    private data class ViewportAnchor(
        val item: Any,
        val top: Int,
    )

    private data class PageIdentity(
        val mangaId: Long?,
        val chapterId: Long?,
        val chapterUrl: String,
        val pageIndex: Int,
    )
}

// Double the cache size to reduce rebinds/recycles incurred by the extra layout space on scroll direction changes
private const val RECYCLER_VIEW_CACHE_SIZE = 4

// Shows the existing loading UI without exposing an unaligned page when initial decoding is slow
private const val INITIAL_LOADING_REVEAL_DELAY_MILLIS = 300L

// Restarts initial alignment if an image or layout callback is lost
private const val INITIAL_REVEAL_WATCHDOG_MILLIS = 5_000L

// Maximum passes before a final correction; reveal still requires a geometry check
private const val MAX_INITIAL_ALIGN_PASSES = 8
private const val REQUIRED_INITIAL_STABLE_FRAMES = 2
private const val ADJACENT_PRELOAD_PAGE_DISTANCE = 5
