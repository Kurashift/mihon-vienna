package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.viewpager.widget.DirectionalViewPager
import eu.kanade.tachiyomi.ui.reader.viewer.GestureDetectorWithLongTap
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderSwipeGesture
import kotlin.math.abs

/**
 * Pager implementation that listens for tap and long tap and allows temporarily disabling touch
 * events in order to work with child views that need to disable touch events on this parent. The
 * pager can also be declared to be vertical by creating it with [isHorizontal] to false.
 */
open class Pager(
    context: Context,
    isHorizontal: Boolean = true,
) : DirectionalViewPager(context, isHorizontal) {

    /**
     * Tap listener function to execute when a tap is detected.
     */
    var tapListener: ((MotionEvent) -> Unit)? = null

    /**
     * Long tap listener function to execute when a long tap is detected.
     */
    var longTapListener: ((MotionEvent) -> Boolean)? = null

    /**
     * Gesture listener that implements tap and long tap events.
     */
    private val gestureListener = object : GestureDetectorWithLongTap.Listener() {
        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
            if (!suppressTap) {
                tapListener?.invoke(ev)
            }
            return true
        }

        override fun onLongTapConfirmed(ev: MotionEvent) {
            val listener = longTapListener
            if (listener != null && listener.invoke(ev)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    /**
     * Gesture detector which handles motion events.
     */
    private val gestureDetector = GestureDetectorWithLongTap(context, gestureListener)

    /**
     * Whether the gesture detector is currently enabled.
     */
    private var isGestureDetectorEnabled = true

    /**
     * Invoked when the user performs a clear vertical swipe in a horizontal pager (not zoomed
     * in), used to jump to a random reader target.
     */
    var verticalSwipeListener: ((upSwipe: Boolean) -> Unit)? = null

    /**
     * Returns whether the currently displayed page is zoomed in, so pan gestures are not
     * hijacked by the random-manga jump.
     */
    var isCurrentPageZoomed: (() -> Boolean)? = null

    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeAxis = ReaderSwipeGesture.Axis.UNDECIDED
    private var pendingVerticalSwipe: Boolean? = null
    private var suppressTap = false
    private val directionTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val verticalSwipeThreshold = (64 * resources.displayMetrics.density).toInt()

    /**
     * Dispatches a touch event.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (handleVerticalSwipe(ev)) {
            return true
        }
        val handled = super.dispatchTouchEvent(ev)
        if (isGestureDetectorEnabled) {
            gestureDetector.onTouchEvent(ev)
        }
        return handled
    }

    /**
     * Detects a clear vertical swipe in horizontal pagers while the page is not zoomed in.
     * The swipe is only triggered once the vertical movement clearly dominates, so regular
     * page flipping (horizontal) is never disturbed.
     */
    private fun handleVerticalSwipe(ev: MotionEvent): Boolean {
        if (!isHorizontal || verticalSwipeListener == null) return false
        return when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
                swipeAxis = ReaderSwipeGesture.Axis.UNDECIDED
                pendingVerticalSwipe = null
                suppressTap = false
                false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                swipeAxis = ReaderSwipeGesture.Axis.READING
                false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - swipeDownY
                val dx = ev.x - swipeDownX
                if (swipeAxis == ReaderSwipeGesture.Axis.UNDECIDED) {
                    swipeAxis = if (isCurrentPageZoomed?.invoke() == true || ev.pointerCount > 1) {
                        ReaderSwipeGesture.Axis.READING
                    } else {
                        ReaderSwipeGesture.resolveAxis(
                            randomDelta = dy,
                            readingDelta = dx,
                            touchSlop = directionTouchSlop,
                            dominanceRatio = RANDOM_SWIPE_DIRECTION_RATIO,
                        )
                    }
                    if (swipeAxis == ReaderSwipeGesture.Axis.RANDOM) {
                        pendingVerticalSwipe = dy < 0
                        suppressTap = true
                        cancelPagerTouch(ev)
                    } else if (swipeAxis == ReaderSwipeGesture.Axis.UNDECIDED &&
                        maxOf(abs(dx), abs(dy)) > directionTouchSlop
                    ) {
                        suppressTap = true
                    }
                }
                swipeAxis != ReaderSwipeGesture.Axis.READING
            }
            MotionEvent.ACTION_UP -> {
                val swipe = pendingVerticalSwipe
                val dy = ev.y - swipeDownY
                val dx = ev.x - swipeDownX
                val confirmed = swipe != null && ReaderSwipeGesture.isConfirmed(
                    randomDelta = dy,
                    readingDelta = dx,
                    threshold = verticalSwipeThreshold,
                    dominanceRatio = RANDOM_SWIPE_DIRECTION_RATIO,
                    expectedNegativeDirection = swipe,
                )
                val consumed = swipeAxis == ReaderSwipeGesture.Axis.RANDOM
                swipeAxis = ReaderSwipeGesture.Axis.UNDECIDED
                pendingVerticalSwipe = null
                if (confirmed) {
                    verticalSwipeListener?.invoke(swipe)
                }
                consumed
            }
            MotionEvent.ACTION_CANCEL -> {
                val consumed = swipeAxis == ReaderSwipeGesture.Axis.RANDOM
                swipeAxis = ReaderSwipeGesture.Axis.UNDECIDED
                pendingVerticalSwipe = null
                consumed
            }
            else -> swipeAxis == ReaderSwipeGesture.Axis.RANDOM
        }
    }

    private fun cancelPagerTouch(source: MotionEvent) {
        val cancel = MotionEvent.obtain(source)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        gestureDetector.onTouchEvent(cancel)
        cancel.recycle()
    }

    /**
     * Whether the given [ev] should be intercepted. Only used to prevent crashes when child
     * views manipulate [requestDisallowInterceptTouchEvent].
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onInterceptTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Handles a touch event. Only used to prevent crashes when child views manipulate
     * [requestDisallowInterceptTouchEvent].
     */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onTouchEvent(ev)
        } catch (e: NullPointerException) {
            false
        } catch (e: IndexOutOfBoundsException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Executes the given key event when this pager has focus. Just do nothing because the reader
     * already dispatches key events to the viewer and has more control than this method.
     */
    override fun executeKeyEvent(event: KeyEvent): Boolean {
        // Disable viewpager's default key event handling
        return false
    }

    /**
     * Enables or disables the gesture detector.
     */
    fun setGestureDetectorEnabled(enabled: Boolean) {
        isGestureDetectorEnabled = enabled
    }
}

private const val RANDOM_SWIPE_DIRECTION_RATIO = 1.5f
