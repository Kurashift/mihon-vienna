package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewPropertyAnimator
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import androidx.core.view.isVisible
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import tachiyomi.core.common.i18n.stringResource
import kotlin.math.abs

class ReaderNavigationOverlayView(context: Context, attributeSet: AttributeSet) : View(context, attributeSet) {

    private var viewPropertyAnimator: ViewPropertyAnimator? = null

    private var navigation: ViewerNavigation? = null

    /**
     * One small line drawn at the bottom of the overlay while it is visible. The swipe-to-jump
     * gestures ride on the same "here is how you steer" moment as the tap zones, so the
     * first-use education is one overlay instead of a second guide chasing it. Null draws
     * nothing — online sources carry no swipe pools.
     */
    var hintText: String? = null

    fun setNavigation(navigation: ViewerNavigation, showOnStart: Boolean, hintText: String? = null) {
        this.hintText = hintText
        val firstLaunch = this.navigation == null
        this.navigation = navigation
        invalidate()

        if (isVisible || (!showOnStart && firstLaunch) || navigation is DisabledNavigation) {
            return
        }

        viewPropertyAnimator = animate()
            .alpha(1f)
            .setDuration(FADE_DURATION)
            .withStartAction {
                isVisible = true
            }
            .withEndAction {
                viewPropertyAnimator = null
            }
        viewPropertyAnimator?.start()
    }

    private val regionPaint = Paint()

    private val textPaint = Paint().apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        textSize = 64f
    }

    private val textBorderPaint = Paint().apply {
        textAlign = Paint.Align.CENTER
        color = Color.BLACK
        textSize = 64f
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    // The hint line is a footnote to the region labels, not one of them: smaller, but with the
    // same white-on-black-stroke treatment so it reads as part of the same overlay.
    private val hintTextPaint = Paint().apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        textSize = 44f
    }

    private val hintTextBorderPaint = Paint().apply {
        textAlign = Paint.Align.CENTER
        color = Color.BLACK
        textSize = 44f
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    override fun onDraw(canvas: Canvas) {
        if (navigation == null) return

        navigation?.getRegions()?.forEach { region ->
            val rect = region.rectF

            // Scale rect from 1f,1f to screen width and height
            canvas.withScale(width.toFloat(), height.toFloat()) {
                regionPaint.color = region.type.color
                drawRect(rect, regionPaint)
            }

            // Don't want scale anymore because it messes with drawText
            // Translate origin to rect start (left, top)
            canvas.withTranslation(x = (width * rect.left), y = (height * rect.top)) {
                // Calculate center of rect width on screen
                val x = width * (abs(rect.left - rect.right) / 2)

                // Calculate center of rect height on screen
                val y = height * (abs(rect.top - rect.bottom) / 2)

                drawText(context.stringResource(region.type.nameRes), x, y, textBorderPaint)
                drawText(context.stringResource(region.type.nameRes), x, y, textPaint)
            }
        }

        hintText?.let { hint ->
            // The centre is where the MENU label sits and where the finger lands to steer;
            // the hint reads as a second line of that same label.
            canvas.withTranslation(x = width / 2f, y = height * 0.5f + 110f) {
                drawText(hint, 0f, 0f, hintTextBorderPaint)
                drawText(hint, 0f, 0f, hintTextPaint)
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()

        if (viewPropertyAnimator == null && isVisible) {
            viewPropertyAnimator = animate()
                .alpha(0f)
                .setDuration(FADE_DURATION)
                .withEndAction {
                    isVisible = false
                    viewPropertyAnimator = null
                }
            viewPropertyAnimator?.start()
        }

        return true
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // Hide overlay if user start tapping or swiping
        performClick()
        return super.onTouchEvent(event)
    }
}

private const val FADE_DURATION = 1000L
