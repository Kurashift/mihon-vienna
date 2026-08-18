package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.viewer.GestureDetectorWithLongTap
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderSwipeGesture
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Implementation of a [RecyclerView] used by the webtoon reader.
 */
class WebtoonRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : RecyclerView(context, attrs, defStyle) {

    private var isZooming = false
    val isZoomAnimating
        get() = isZooming
    private var atLastPosition = false
    private var atFirstPosition = false
    private var halfWidth = 0
    private var halfHeight = 0
    var originalHeight = 0
        private set
    private var heightSet = false
    private var landscapeTargetScale: Float? = null
    private var landscapeInitialScaleApplied = false
    private var firstVisibleItemPosition = 0
    private var lastVisibleItemPosition = 0
    private var currentScale = DEFAULT_RATE
    var zoomOutDisabled = false
        set(value) {
            field = value
            if (value && currentScale < DEFAULT_RATE) {
                zoom(currentScale, DEFAULT_RATE, x, 0f, y, 0f)
            }
        }
    private val minRate
        get() = if (zoomOutDisabled) {
            DEFAULT_RATE
        } else {
            minOf(MIN_RATE, landscapeTargetScale ?: MIN_RATE)
        }

    private val listener = GestureListener()
    private val detector = Detector()

    var doubleTapZoom = true
    var doubleTapAnimDuration = DEFAULT_DOUBLE_TAP_ANIM_DURATION

    var tapListener: ((MotionEvent) -> Unit)? = null
    var longTapListener: ((MotionEvent) -> Boolean)? = null

    private var isManuallyScrolling = false
    private var tapDuringManualScroll = false

    /**
     * Invoked when the user performs a clear horizontal swipe while not zoomed in,
     * used to jump to a random reader target.
     */
    var horizontalSwipeListener: ((leftSwipe: Boolean) -> Unit)? = null

    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeAxis = ReaderSwipeGesture.Axis.UNDECIDED
    private var pendingHorizontalSwipe: Boolean? = null
    private var suppressTap = false
    private val directionTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val horizontalSwipeThreshold = (64 * resources.displayMetrics.density).toInt()
    private var verticalGestureAnchorY = 0f
    var scrollGestureDirection = 0
        private set

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (handleHorizontalSwipe(e)) return true
        return super.dispatchTouchEvent(e)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        halfWidth = MeasureSpec.getSize(widthSpec) / 2
        halfHeight = MeasureSpec.getSize(heightSpec) / 2
        if (!heightSet) {
            originalHeight = MeasureSpec.getSize(heightSpec)
            heightSet = true
        }
        super.onMeasure(widthSpec, heightSpec)
    }

    /**
     * Keeps the layout height in sync with the current scale: when zoomed out the view
     * must grow so the scaled-down content still fills the viewport.
     */
    private fun updateLayoutForScale() {
        layoutParams.height = layoutHeightForScale(currentScale)
        halfHeight = layoutParams.height / 2
        pivotX = halfWidth.toFloat()
        pivotY = halfHeight.toFloat()
    }

    private fun layoutHeightForScale(scale: Float): Int {
        return if (scale < DEFAULT_RATE) {
            (originalHeight / scale).toInt()
        } else {
            originalHeight
        }
    }

    private fun steadyTranslationY(scale: Float): Float {
        return originalHeight / 2f - layoutHeightForScale(scale) / 2f
    }

    private fun landscapeDoubleTapDuration(): Long {
        return (
            doubleTapAnimDuration * context.animatorDurationScale
            ).toLong().coerceAtLeast(1L)
    }

    /**
     * Applies the initial landscape scale once the resumed page has its final measured height.
     * The reference page fills the viewport height while every page keeps the same strip width.
     */
    fun ensureInitialLandscapeScale(referenceView: View): Boolean {
        if (landscapeInitialScaleApplied || !isLandscapeWebtoon() || referenceView.height <= 0) {
            return false
        }

        landscapeInitialScaleApplied = true
        val targetScale = landscapeScaleFor(referenceView, WebtoonLandscapeZoom.FULL_HEIGHT_FRACTION)
        landscapeTargetScale = targetScale
        setLandscapeScaleImmediately(targetScale)
        return true
    }

    private fun setLandscapeScaleImmediately(scale: Float) {
        currentScale = scale
        setScaleRate(scale)
        updateLayoutForScale()
        x = 0f
        y = steadyTranslationY(scale)
        requestLayout()
    }

    fun isLandscapeWebtoon(): Boolean {
        return originalHeight > 0 && width > originalHeight && !zoomOutDisabled
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        trackVerticalScrollDirection(e)
        detector.onTouchEvent(e)
        return super.onTouchEvent(e)
    }

    private fun trackVerticalScrollDirection(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                verticalGestureAnchorY = event.y
                scrollGestureDirection = 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1 || detector.isDoubleTapping) return
                val deltaY = event.y - verticalGestureAnchorY
                if (abs(deltaY) > directionTouchSlop) {
                    scrollGestureDirection = if (deltaY > 0f) -1 else 1
                    verticalGestureAnchorY = event.y
                }
            }
            MotionEvent.ACTION_CANCEL -> scrollGestureDirection = 0
        }
    }

    private fun handleHorizontalSwipe(e: MotionEvent): Boolean {
        if (horizontalSwipeListener == null) return false
        return when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapDuringManualScroll = isManuallyScrolling
                swipeDownX = e.x
                swipeDownY = e.y
                swipeAxis = ReaderSwipeGesture.Axis.UNDECIDED
                pendingHorizontalSwipe = null
                suppressTap = false
                false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                swipeAxis = ReaderSwipeGesture.Axis.READING
                false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - swipeDownX
                val dy = e.y - swipeDownY
                if (swipeAxis == ReaderSwipeGesture.Axis.UNDECIDED) {
                    swipeAxis = if (currentScale > 1f || e.pointerCount > 1) {
                        ReaderSwipeGesture.Axis.READING
                    } else {
                        ReaderSwipeGesture.resolveAxis(
                            randomDelta = dx,
                            readingDelta = dy,
                            touchSlop = directionTouchSlop,
                            dominanceRatio = HORIZONTAL_SWIPE_DIRECTION_RATIO,
                        )
                    }
                    if (swipeAxis == ReaderSwipeGesture.Axis.RANDOM) {
                        pendingHorizontalSwipe = dx < 0
                        suppressTap = true
                        cancelRecyclerTouch(e)
                    } else if (swipeAxis == ReaderSwipeGesture.Axis.UNDECIDED &&
                        maxOf(abs(dx), abs(dy)) > directionTouchSlop
                    ) {
                        suppressTap = true
                    }
                }
                swipeAxis != ReaderSwipeGesture.Axis.READING
            }
            MotionEvent.ACTION_UP -> {
                val swipe = pendingHorizontalSwipe
                val dx = e.x - swipeDownX
                val dy = e.y - swipeDownY
                val consumed = swipeAxis == ReaderSwipeGesture.Axis.RANDOM
                val confirmed = swipe != null && ReaderSwipeGesture.isConfirmed(
                    randomDelta = dx,
                    readingDelta = dy,
                    threshold = horizontalSwipeThreshold,
                    dominanceRatio = HORIZONTAL_SWIPE_DIRECTION_RATIO,
                    expectedNegativeDirection = swipe,
                )
                swipeAxis = ReaderSwipeGesture.Axis.UNDECIDED
                pendingHorizontalSwipe = null
                if (confirmed) {
                    horizontalSwipeListener?.invoke(swipe)
                }
                consumed
            }
            MotionEvent.ACTION_CANCEL -> {
                val consumed = swipeAxis == ReaderSwipeGesture.Axis.RANDOM
                swipeAxis = ReaderSwipeGesture.Axis.UNDECIDED
                pendingHorizontalSwipe = null
                consumed
            }
            else -> swipeAxis == ReaderSwipeGesture.Axis.RANDOM
        }
    }

    private fun cancelRecyclerTouch(source: MotionEvent) {
        val cancel = MotionEvent.obtain(source)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        val layoutManager = layoutManager
        lastVisibleItemPosition =
            (layoutManager as LinearLayoutManager).findLastVisibleItemPosition()
        firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        val layoutManager = layoutManager
        val visibleItemCount = layoutManager?.childCount ?: 0
        val totalItemCount = layoutManager?.itemCount ?: 0
        atLastPosition = visibleItemCount > 0 && lastVisibleItemPosition == totalItemCount - 1
        atFirstPosition = firstVisibleItemPosition == 0

        if (state == SCROLL_STATE_IDLE) {
            isManuallyScrolling = false
            scrollGestureDirection = 0
        }
    }

    private fun getPositionX(positionX: Float): Float {
        if (currentScale < 1) {
            return 0f
        }
        val maxPositionX = halfWidth * (currentScale - 1)
        return positionX.coerceIn(-maxPositionX, maxPositionX)
    }

    private fun getPositionY(positionY: Float): Float {
        if (currentScale < 1) {
            return (originalHeight / 2 - halfHeight).toFloat()
        }
        val maxPositionY = halfHeight * (currentScale - 1)
        return positionY.coerceIn(-maxPositionY, maxPositionY)
    }

    private fun zoom(
        fromRate: Float,
        toRate: Float,
        fromX: Float,
        toX: Float,
        fromY: Float,
        toY: Float,
    ) {
        isZooming = true
        val animatorSet = AnimatorSet()
        val translationXAnimator = ValueAnimator.ofFloat(fromX, toX)
        translationXAnimator.addUpdateListener { animation -> x = animation.animatedValue as Float }

        val translationYAnimator = ValueAnimator.ofFloat(fromY, toY)
        translationYAnimator.addUpdateListener { animation -> y = animation.animatedValue as Float }

        val scaleAnimator = ValueAnimator.ofFloat(fromRate, toRate)
        scaleAnimator.addUpdateListener { animation ->
            currentScale = animation.animatedValue as Float
            setScaleRate(currentScale)
            updateLayoutForScale()
            requestLayout()
        }
        animatorSet.playTogether(translationXAnimator, translationYAnimator, scaleAnimator)
        animatorSet.duration = (
            doubleTapAnimDuration * context.animatorDurationScale
            ).toLong().coerceAtLeast(1L)
        animatorSet.interpolator = DecelerateInterpolator()
        animatorSet.start()
        animatorSet.doOnEnd {
            isZooming = false
            currentScale = toRate
        }
    }

    private fun toggleLandscapeHeight(tapX: Float, tapY: Float) {
        val referenceView = landscapeReferenceViewAt(tapX, tapY) ?: return
        if (referenceView.height <= 0) return

        val displayedHeightFraction = referenceView.height * currentScale / originalHeight
        val targetHeightFraction = WebtoonLandscapeZoom.targetHeightFraction(displayedHeightFraction)
        val targetScale = landscapeScaleFor(referenceView, targetHeightFraction)
        landscapeTargetScale = targetScale
        animateLandscapeScaleInPlace(targetScale)
    }

    private fun landscapeReferenceViewAt(tapX: Float, tapY: Float): View? {
        return findChildViewUnder(tapX, tapY)
            ?.takeIf { getChildViewHolder(it) is WebtoonPageHolder }
            ?: (0 until childCount)
                .map(::getChildAt)
                .filter { getChildViewHolder(it) is WebtoonPageHolder }
                .minByOrNull { abs((it.top + it.bottom) / 2f - tapY) }
    }

    private fun landscapeScaleFor(referenceView: View, heightFraction: Float): Float {
        return (originalHeight * heightFraction / referenceView.height)
            // The page starts at fit-width. Never exceed that baseline in landscape, otherwise
            // a very wide image would be cropped horizontally just to satisfy the height target.
            .coerceIn(MIN_LANDSCAPE_SCALE, DEFAULT_RATE)
    }

    private fun animateLandscapeScaleInPlace(toScale: Float) {
        val fromScale = currentScale
        val anchor = captureLandscapeViewportAnchor()
        isZooming = true
        ValueAnimator.ofFloat(fromScale, toScale).apply {
            duration = landscapeDoubleTapDuration()
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                currentScale = animation.animatedValue as Float
                setScaleRate(currentScale)
                updateLayoutForScale()
                x = 0f
                y = steadyTranslationY(currentScale)
                keepLandscapeAnchorInPlace(anchor)
                requestLayout()
            }
            doOnEnd {
                currentScale = toScale
                setScaleRate(currentScale)
                updateLayoutForScale()
                x = 0f
                y = steadyTranslationY(currentScale)
                keepLandscapeAnchorInPlace(anchor)
                requestLayout()
                isZooming = false
            }
            start()
        }
    }

    private fun captureLandscapeViewportAnchor(): LandscapeViewportAnchor? {
        val screenCenter = originalHeight / 2f
        val contentCenter = screenToContentY(screenCenter)
        val anchorView = findChildViewUnder(halfWidth.toFloat(), contentCenter)
            ?.takeIf { getChildViewHolder(it) is WebtoonPageHolder }
            ?: (0 until childCount)
                .map(::getChildAt)
                .filter { getChildViewHolder(it) is WebtoonPageHolder }
                .minByOrNull { abs((it.top + it.bottom) / 2f - contentCenter) }
            ?: return null
        val position = getChildAdapterPosition(anchorView)
        if (position == NO_POSITION) return null

        return LandscapeViewportAnchor(
            position = position,
            offsetInPage = contentCenter - anchorView.top,
            screenY = screenCenter,
        )
    }

    private fun keepLandscapeAnchorInPlace(anchor: LandscapeViewportAnchor?) {
        anchor ?: return
        val anchorView = layoutManager?.findViewByPosition(anchor.position) ?: return
        val currentScreenY = contentToScreenY(anchorView.top + anchor.offsetInPage)
        val correction = ((currentScreenY - anchor.screenY) / currentScale).roundToInt()
        if (correction != 0) {
            scrollBy(0, correction)
        }
    }

    private fun screenToContentY(screenY: Float): Float {
        return (screenY - (1f - currentScale) * halfHeight - y) / currentScale
    }

    private fun contentToScreenY(contentY: Float): Float {
        return currentScale * contentY + (1f - currentScale) * halfHeight + y
    }

    fun zoomFling(velocityX: Int, velocityY: Int): Boolean {
        if (currentScale <= 1f) return false

        val distanceTimeFactor = 0.4f
        val animatorSet = AnimatorSet()

        if (velocityX != 0) {
            val dx = (distanceTimeFactor * velocityX / 2)
            val newX = getPositionX(x + dx)
            val translationXAnimator = ValueAnimator.ofFloat(x, newX)
            translationXAnimator.addUpdateListener { animation -> x = getPositionX(animation.animatedValue as Float) }
            animatorSet.play(translationXAnimator)
        }
        if (velocityY != 0 && (atFirstPosition || atLastPosition)) {
            val dy = (distanceTimeFactor * velocityY / 2)
            val newY = getPositionY(y + dy)
            val translationYAnimator = ValueAnimator.ofFloat(y, newY)
            translationYAnimator.addUpdateListener { animation -> y = getPositionY(animation.animatedValue as Float) }
            animatorSet.play(translationYAnimator)
        }

        animatorSet.duration = 400
        animatorSet.interpolator = DecelerateInterpolator()
        animatorSet.start()

        return true
    }

    private fun zoomScrollBy(dx: Int, dy: Int) {
        if (dx != 0) {
            x = getPositionX(x + dx)
        }
        if (dy != 0) {
            y = getPositionY(y + dy)
        }
    }

    private fun setScaleRate(rate: Float) {
        scaleX = rate
        scaleY = rate
    }

    fun onScale(scaleFactor: Float) {
        currentScale *= scaleFactor
        currentScale = currentScale.coerceIn(
            minRate,
            MAX_SCALE_RATE,
        )

        setScaleRate(currentScale)
        updateLayoutForScale()

        if (currentScale != DEFAULT_RATE) {
            x = getPositionX(x)
            y = getPositionY(y)
        } else {
            x = 0f
            y = 0f
        }

        requestLayout()
    }

    fun onScaleBegin() {
        if (detector.isDoubleTapping) {
            detector.isQuickScaling = true
        }
    }

    fun onScaleEnd() {
        if (scaleX < minRate) {
            zoom(currentScale, minRate, x, 0f, y, 0f)
        }
    }

    fun onManualScroll() {
        isManuallyScrolling = true
    }

    inner class GestureListener : GestureDetectorWithLongTap.Listener() {

        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
            if (!tapDuringManualScroll && !suppressTap) {
                tapListener?.invoke(ev)
            }
            return false
        }

        override fun onDoubleTap(ev: MotionEvent): Boolean {
            detector.isDoubleTapping = true
            return false
        }

        fun onDoubleTapConfirmed(ev: MotionEvent) {
            if (!isZooming && doubleTapZoom) {
                val isLandscape = originalHeight > 0 && width > originalHeight && !zoomOutDisabled
                if (isLandscape) {
                    toggleLandscapeHeight(ev.x, ev.y)
                    return
                }

                val toScale = if (scaleX != DEFAULT_RATE) DEFAULT_RATE else DOUBLE_TAP_SCALE

                if (toScale == DEFAULT_RATE) {
                    zoom(currentScale, DEFAULT_RATE, x, 0f, y, 0f)
                } else {
                    val toX = (halfWidth - ev.x) * (toScale - 1)
                    val toY = (halfHeight - ev.y) * (toScale - 1)
                    zoom(currentScale, toScale, x, toX, y, toY)
                }
            }
        }

        override fun onLongTapConfirmed(ev: MotionEvent) {
            if (longTapListener?.invoke(ev) == true) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    inner class Detector : GestureDetectorWithLongTap(context, listener) {

        private var scrollPointerId = 0
        private var downX = 0
        private var downY = 0
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var isZoomDragging = false
        var isDoubleTapping = false
        var isQuickScaling = false

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val action = ev.actionMasked
            val actionIndex = ev.actionIndex

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    scrollPointerId = ev.getPointerId(0)
                    downX = (ev.x + 0.5f).toInt()
                    downY = (ev.y + 0.5f).toInt()
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    scrollPointerId = ev.getPointerId(actionIndex)
                    downX = (ev.getX(actionIndex) + 0.5f).toInt()
                    downY = (ev.getY(actionIndex) + 0.5f).toInt()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDoubleTapping && isQuickScaling) {
                        return true
                    }

                    val index = ev.findPointerIndex(scrollPointerId)
                    if (index < 0) {
                        return false
                    }

                    val x = (ev.getX(index) + 0.5f).toInt()
                    val y = (ev.getY(index) + 0.5f).toInt()
                    var dx = x - downX
                    var dy = if (atFirstPosition || atLastPosition) y - downY else 0

                    if (!isZoomDragging && currentScale > 1f) {
                        var startScroll = false

                        if (abs(dx) > touchSlop) {
                            if (dx < 0) {
                                dx += touchSlop
                            } else {
                                dx -= touchSlop
                            }
                            startScroll = true
                        }
                        if (abs(dy) > touchSlop) {
                            if (dy < 0) {
                                dy += touchSlop
                            } else {
                                dy -= touchSlop
                            }
                            startScroll = true
                        }

                        if (startScroll) {
                            isZoomDragging = true
                        }
                    }

                    if (isZoomDragging) {
                        zoomScrollBy(dx, dy)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isDoubleTapping && !isQuickScaling) {
                        listener.onDoubleTapConfirmed(ev)
                    }
                    isZoomDragging = false
                    isDoubleTapping = false
                    isQuickScaling = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    isZoomDragging = false
                    isDoubleTapping = false
                    isQuickScaling = false
                }
            }
            return super.onTouchEvent(ev)
        }
    }
}

private const val MIN_RATE = 0.5f
private const val MIN_LANDSCAPE_SCALE = 0.05f
private const val DEFAULT_RATE = 1f
private const val MAX_SCALE_RATE = 3f
private const val DOUBLE_TAP_SCALE = 2f
private const val DEFAULT_DOUBLE_TAP_ANIM_DURATION = 500
private const val HORIZONTAL_SWIPE_DIRECTION_RATIO = 1.5f

private data class LandscapeViewportAnchor(
    val position: Int,
    val offsetInPage: Float,
    val screenY: Float,
)
