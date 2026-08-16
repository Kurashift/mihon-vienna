@file:Suppress("PackageDirectoryMismatch")

package androidx.recyclerview.widget

import android.content.Context
import androidx.recyclerview.widget.RecyclerView.NO_POSITION

/**
 * Layout manager used by the webtoon viewer. Item prefetch is disabled because the extra layout
 * space feature is used which allows setting the image even if the holder is not visible,
 * avoiding (in most cases) black views when they are visible.
 *
 * This layout manager uses the same package name as the support library in order to use a package
 * protected method.
 */
class WebtoonLayoutManager(context: Context, private val extraLayoutSpace: Int) : LinearLayoutManager(context) {

    init {
        isItemPrefetchEnabled = false
    }

    /**
     * Returns the custom extra layout space.
     */
    @Deprecated("Deprecated in Java")
    override fun getExtraLayoutSpace(state: RecyclerView.State): Int {
        return extraLayoutSpace
    }

    /**
     * Returns the position of the last item whose end side is visible on screen.
     */
    fun findLastEndVisibleItemPosition(): Int {
        ensureLayoutState()
        val callback = if (mOrientation == HORIZONTAL) {
            mHorizontalBoundCheck
        } else {
            mVerticalBoundCheck
        }.mCallback
        val start = callback.parentStart
        val end = callback.parentEnd
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)!!
            val childStart = callback.getChildStart(child)
            val childEnd = callback.getChildEnd(child)
            if (childEnd <= end || childStart < start) {
                return getPosition(child)
            }
        }
        return NO_POSITION
    }

    /**
     * Returns the position of the item that covers the vertical center of the viewport.
     * Used to persist webtoon progress so a page only counts as read once its content
     * actually occupies the middle of the screen.
     */
    fun findCenteredVisibleItemPosition(): Int {
        ensureLayoutState()
        val callback = if (mOrientation == HORIZONTAL) {
            mHorizontalBoundCheck
        } else {
            mVerticalBoundCheck
        }.mCallback
        val start = callback.parentStart
        val end = callback.parentEnd
        val center = start + (end - start) / 2
        for (i in 0 until childCount) {
            val child = getChildAt(i)!!
            val childStart = callback.getChildStart(child)
            val childEnd = callback.getChildEnd(child)
            if (childStart <= center && childEnd >= center) {
                return getPosition(child)
            }
        }
        return NO_POSITION
    }
}
