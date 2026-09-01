package eu.kanade.tachiyomi.ui.audio

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.audio.AudioSubtitleState
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/**
 * Keeps the subtitle window in sync with the "floating subtitles" preference.
 *
 * It is owned by [AudioPlaybackService] rather than by a screen: the whole point is that the
 * window survives the app going to the background, and playback is the only time it is useful.
 * Nothing is added to the window while the draw-over-other-apps permission is missing, because
 * that would throw instead of merely doing nothing.
 *
 * Two windows are added rather than one. The card holds the line, and a small second window holds
 * the lock and close buttons just below it. Splitting them is what lets a locked card be fully
 * touch-through while its lock button stays tappable: a single window is either touchable or it is
 * not, and a tap that reaches a window nobody consumes is dropped instead of falling through to
 * the app below. It also keeps the card's background from wrapping the buttons, and lets them be
 * dragged off the bottom edge while the card itself stays fully on screen.
 *
 * The player screen is the single place the window hides: everywhere else, including the
 * background and other apps, is somewhere the line would otherwise not be readable at all.
 */
class AudioFloatingSubtitleOverlay(
    private val context: Context,
    private val preferences: BasePreferences,
    private val controller: AudioPlayerController,
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var subtitleView: ComposeView? = null
    private var controlsView: ComposeView? = null
    private var subtitleOwner: FloatingWindowLifecycleOwner? = null
    private var controlsOwner: FloatingWindowLifecycleOwner? = null

    private var subtitleParams: WindowManager.LayoutParams? = null
    private var controlsParams: WindowManager.LayoutParams? = null

    /** Read by both compositions, so locking only has to flip this for the icons to follow. */
    private var locked by mutableStateOf(preferences.audioFloatingSubtitleLocked.get())

    /** Starts following the conditions that decide whether the window belongs on screen. */
    fun attach() {
        combine(
            preferences.audioFloatingSubtitle.changes(),
            preferences.audioFloatingSubtitleLocked.changes(),
            hasSubtitleContent(),
            playerScreenVisible(),
        ) { enabled, isLocked, hasContent, playerVisible ->
            Conditions(enabled, isLocked, hasContent, playerVisible)
        }
            .distinctUntilChanged()
            .onEach { conditions ->
                val lockChanged = conditions.isLocked != locked
                locked = conditions.isLocked
                when {
                    !conditions.visible -> hide()
                    subtitleView == null -> show()
                    // Already on screen, so only the flags and the icon set have to catch up.
                    lockChanged -> applyLockedState()
                }
            }
            .launchIn(scope)
    }

    /** Removes the window and stops following it. */
    fun detach() {
        scope.cancel()
        hide()
    }

    /**
     * Re-fits the windows after a configuration change, a rotation above all.
     *
     * The width is a pixel count taken from the screen the card was first shown on, so it has to
     * be taken again: left alone, a card that appeared in landscape keeps that width when the
     * screen turns upright, which is what stretched it and pushed it off the edge.
     *
     * The remembered spot is clamped to the new screen but not rewritten, so turning the screen
     * back restores where it was rather than whatever the other orientation forced it to.
     */
    fun onConfigurationChanged() {
        val view = subtitleView ?: return
        val params = subtitleParams ?: return
        params.width = cardWidthPx()
        params.x = params.x.coerceIn(0, maxX(params.width))
        params.y = params.y.coerceIn(0, maxY())
        runCatching { windowManager.updateViewLayout(view, params) }
        // The line is one line tall whatever the width is, so its height is already known and the
        // buttons can be placed straight away rather than waiting for a new measurement.
        updateControls()
    }

    /**
     * Emits whether there is a line worth showing.
     *
     * Playback republishes its position twice a second, so this deliberately reads only the two
     * fields that decide visibility. The line itself is resolved inside the composition, which is
     * where that rate would actually cost something.
     */
    private fun hasSubtitleContent(): Flow<Boolean> = snapshotFlow {
        val state = controller.state
        state.subtitleState == AudioSubtitleState.READY && state.lyrics.isNotEmpty()
    }.distinctUntilChanged()

    /**
     * Emits whether the player screen is the page on screen, which is the one place the window
     * hides. Leaving the page, or leaving the app from it, brings the window straight back.
     */
    private fun playerScreenVisible(): Flow<Boolean> = snapshotFlow {
        controller.playerScreenVisible
    }.distinctUntilChanged()

    private fun show() {
        if (subtitleView != null || !canDrawOverlays()) return
        val owner = FloatingWindowLifecycleOwner()
        val view = composeView(owner) { SubtitleCard() }
        val params = newSubtitleParams()
        if (!addWindow(view, params)) {
            owner.stop()
            return
        }
        subtitleView = view
        subtitleParams = params
        subtitleOwner = owner
        showControls()
    }

    private fun showControls() {
        val owner = FloatingWindowLifecycleOwner()
        val view = composeView(owner) { SubtitleControls() }
        val params = newControlsParams() ?: run {
            owner.stop()
            return
        }
        if (!addWindow(view, params)) {
            owner.stop()
            return
        }
        controlsView = view
        controlsParams = params
        controlsOwner = owner
        // A remembered spot near the bottom may leave no room under the card, and the card is
        // allowed to be there; the buttons stay out of the way until it is dragged back up.
        if (!controlsFitBelow(subtitleParams ?: return)) view.visibility = View.GONE
    }

    private fun hide() {
        hideControls()
        val view = subtitleView ?: return
        subtitleView = null
        subtitleParams = null
        // removeViewImmediate is what keeps the view tree from outliving the window and holding
        // on to the service; a plain removeView defers the detach to the next frame.
        runCatching { windowManager.removeViewImmediate(view) }
        subtitleOwner?.stop()
        subtitleOwner = null
    }

    private fun hideControls() {
        val view = controlsView ?: return
        controlsView = null
        controlsParams = null
        runCatching { windowManager.removeViewImmediate(view) }
        controlsOwner?.stop()
        controlsOwner = null
    }

    /**
     * Re-applies a changed lock state to windows that are already on screen.
     *
     * The card only has to gain or drop the touch-through flag, which an update of the window's
     * layout params applies without re-adding it. The controls window also changes width, because
     * locking is what removes the close button.
     */
    private fun applyLockedState() {
        subtitleParams?.let { params ->
            params.flags = subtitleFlags()
            subtitleView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
        }
        if (controlsView == null) {
            showControls()
            return
        }
        updateControls()
    }

    private fun composeView(
        owner: FloatingWindowLifecycleOwner,
        content: @Composable () -> Unit,
    ): ComposeView = ComposeView(context).apply {
        owner.start()
        owner.attachTo(this)
        setComposeContent(
            disposeStrategy = ViewCompositionStrategy.DisposeOnDetachedFromWindow,
            content = content,
        )
    }

    private fun addWindow(view: ComposeView, params: WindowManager.LayoutParams): Boolean {
        return try {
            windowManager.addView(view, params)
            true
        } catch (_: Exception) {
            // A ROM that refuses overlay windows outright, or a permission revoked mid-flight.
            // Dropping the owner here is what keeps a failed attempt from leaking one.
            false
        }
    }

    /**
     * Moves the card during a drag.
     *
     * [WindowManager.updateViewLayout] is a synchronous call into the system that also asks the
     * view to lay itself out again, so calling it every frame of a drag is what made the card lag
     * behind the finger: each call is not expensive on its own, but they queue up on the main
     * thread behind whatever the app itself is drawing, and the queue is exactly the delay that
     * reads as the card chasing where the finger already was.
     *
     * What keeps it at one call per frame is [hideControlsWhileDragging]: the controls are a
     * second window, and moving both is what doubled the cost. They are taken out for the duration
     * of the drag and put back at the end, which is also what the drag wants visually — a card
     * with nothing hanging off it.
     *
     * Moving the contents instead of the window does not work: a translation is a render offset
     * inside the window's own surface, so anything carried past the edge of that surface is
     * clipped away rather than drawn further down the screen.
     */
    private fun dragBy(dx: Float, dy: Float) {
        val params = subtitleParams ?: return
        params.x = (params.x + dx.roundToInt()).coerceIn(0, maxX(params.width))
        params.y = (params.y + dy.roundToInt()).coerceIn(0, maxY())
        subtitleView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    /**
     * Takes the buttons off screen for the length of a drag.
     *
     * Gone rather than transparent, so the window stops being laid out and composited at all
     * while the finger is moving.
     */
    private fun hideControlsWhileDragging() {
        controlsView?.visibility = View.GONE
    }

    /** Ends a drag: remembers the spot and puts the buttons back where they belong. */
    private fun commitPosition() {
        persistPosition()
        updateControls()
    }

    /**
     * Places the buttons under the card, or takes them away when there is no room left.
     *
     * Letting them hang past the bottom edge is not something that can be relied on: MIUI pulls a
     * window that has left the screen back into view, so buttons parked off the bottom come to
     * rest on top of the line instead, right where they are least wanted. Removing them is what
     * actually leaves the card on its own at the bottom edge, and it is also cheaper, since a gone
     * window is neither laid out nor composited.
     *
     * The bottom of the screen is therefore a place the card can be put and left alone, and
     * dragging back up brings the buttons with it.
     */
    private fun updateControls() {
        val view = controlsView ?: return
        val controls = controlsParams ?: return
        val subtitle = subtitleParams ?: return
        if (!controlsFitBelow(subtitle)) {
            view.visibility = View.GONE
            return
        }
        controls.width = controlsWidthPx()
        controls.x = subtitle.x + (subtitle.width - controls.width) / 2
        // Measured rather than assumed: the card grows to fit its line, so the buttons have to
        // sit under whatever it actually came out as, not under a constant.
        controls.y = subtitle.y + subtitleHeight()
        // Positioned while still hidden, so the frame it comes back on is already the right one
        // rather than flashing at the card's old side.
        runCatching { windowManager.updateViewLayout(view, controls) }
        view.visibility = View.VISIBLE
    }

    private fun controlsFitBelow(subtitle: WindowManager.LayoutParams): Boolean {
        return subtitle.y + subtitleHeight() + controlsHeightPx() <= screenHeight()
    }

    /** Remembers where the card was dropped, so the next track opens in the same place. */
    private fun persistPosition() {
        val params = subtitleParams ?: return
        preferences.audioFloatingSubtitleX.set(params.x)
        preferences.audioFloatingSubtitleY.set(params.y)
    }

    private fun toggleLocked() {
        preferences.audioFloatingSubtitleLocked.set(!preferences.audioFloatingSubtitleLocked.get())
    }

    /** Closing is the same thing as the player screen's toggle being off, and is just as lasting. */
    private fun close() {
        preferences.audioFloatingSubtitle.set(false)
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    private fun newSubtitleParams(): WindowManager.LayoutParams {
        val width = cardWidthPx()
        return WindowManager.LayoutParams(
            width,
            // Wrapped, because the line is allowed to ask for more room at a larger font scale.
            // It does not make dragging more expensive: the size is settled before the drag
            // starts, and a drag only ever moves the card.
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable so the window never steals the keyboard or the back gesture from
            // whatever the user is doing underneath it. Locking adds touch-through on top.
            subtitleFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resolveX(width)
            y = resolveY()
        }
    }

    private fun subtitleFlags(): Int {
        val touchThrough = if (locked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or touchThrough
    }

    /**
     * The controls window sits just below the card. It is added after it, so it draws over it, and
     * it is sized in pixels rather than wrapped so it stays only as wide as the buttons and leaves
     * the rest of the strip clear.
     */
    private fun newControlsParams(): WindowManager.LayoutParams? {
        val subtitle = subtitleParams ?: return null
        return WindowManager.LayoutParams(
            controlsWidthPx(),
            controlsHeightPx(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = subtitle.x + (subtitle.width - controlsWidthPx()) / 2
            y = subtitle.y + subtitleHeight()
        }
    }

    private fun resolveX(width: Int): Int {
        val saved = preferences.audioFloatingSubtitleX.get()
        // Centred to begin with; anything remembered after that wins, unless the screen got
        // narrower (rotation) and the remembered spot no longer fits.
        return if (saved == BasePreferences.UNSET_POSITION) {
            maxX(width) / 2
        } else {
            saved.coerceIn(0, maxX(width))
        }
    }

    private fun resolveY(): Int {
        val saved = preferences.audioFloatingSubtitleY.get()
        // Hugging the top edge means clearing the status bar, whose height is only reachable
        // through the framework's own dimension. The exact spot is refined by dragging later.
        val fallback = statusBarHeight() + topGapPx()
        return if (saved == BasePreferences.UNSET_POSITION) fallback else saved.coerceIn(0, maxY())
    }

    private fun maxX(width: Int): Int = (screenWidth() - width).coerceAtLeast(0)

    /**
     * Only the card is kept on screen, so it can be dragged to the very bottom edge. The buttons
     * hang below it and are free to pass off the bottom: that is a deliberate way to get the bar
     * on its own, with nothing under it.
     */
    private fun maxY(): Int = (screenHeight() - subtitleHeight()).coerceAtLeast(0)

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels

    /** The card as it was measured, falling back to the height it is composed at. */
    private fun subtitleHeight(): Int = subtitleView?.height?.takeIf { it > 0 } ?: lineHeightPx()

    private fun statusBarHeight(): Int {
        val resources = context.resources
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun topGapPx(): Int = (TOP_GAP_DP * density()).toInt()

    private fun lineHeightPx(): Int = (LINE_HEIGHT.value * density()).toInt()

    private fun cardWidthPx(): Int = (screenWidth() * WIDTH_RATIO).toInt()

    private fun controlsHeightPx(): Int = (CONTROL_ROW_HEIGHT.value * density()).toInt()

    private fun controlsWidthPx(): Int = (controlsWidth().value * density()).toInt()

    /** As wide as the buttons it holds and no wider, so it stays clear of the card's edges. */
    private fun controlsWidth(): Dp = CONTROL_BUTTON_SIZE * if (locked) 1 else 2

    private fun density(): Float = context.resources.displayMetrics.density

    /**
     * The line, inside the one rectangle the window draws.
     *
     * The buttons are not part of this rectangle. They are a second window resting below it, which
     * is what keeps the card to a single rounded shape around the text, and what lets the buttons
     * be dragged off the bottom edge while the card stays put.
     */
    @Composable
    private fun SubtitleCard() {
        val line by remember(controller) { derivedStateOf { currentLine() } }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // A minimum rather than a fixed height so a larger font scale can still ask for
                // more; the bar stays one line tall either way.
                .heightIn(min = LINE_HEIGHT)
                .pointerInput(locked) {
                    // Locking makes the whole window touch-through, so this would never fire;
                    // bailing out early just keeps the gesture detector from being installed.
                    if (locked) return@pointerInput
                    detectDragGestures(
                        onDragStart = { hideControlsWhileDragging() },
                        onDragEnd = { commitPosition() },
                        onDragCancel = { commitPosition() },
                        onDrag = { _, amount -> dragBy(amount.x, amount.y) },
                    )
                }
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = BACKGROUND_ALPHA),
                    shape = RoundedCornerShape(12.dp),
                ),
        ) {
            SubtitleLine(line)
        }
    }

    /**
     * The line playing right now, scrolled across as it is spoken.
     *
     * The scroll is paced by the playback position rather than run as an animation of its own. An
     * animation with a fixed duration keeps going on its own clock once started, so it slides on
     * through a pause and drifts out of step with the voice over a long line. Driving it from the
     * position instead means it advances exactly as far as the speaker has got, stops the moment
     * playback does, and is already at the right place after a seek.
     *
     * A short line has nothing to scroll and stays still, with no frames being asked for at all.
     */
    @Composable
    private fun SubtitleLine(line: SubtitleLine) {
        val scrollState = rememberScrollState()
        // How far there is to scroll depends on how wide the card is, so a rotation has to restart
        // the scroll for the line already on screen rather than let it finish against the distance
        // the previous orientation gave.
        val availableWidthDp = LocalConfiguration.current.screenWidthDp
        LaunchedEffect(line, availableWidthDp) {
            scrollState.scrollTo(0)
            // How far there is to scroll only exists once the text has been measured.
            withFrameMillis { }
            val distance = scrollState.maxValue
            if (distance <= 0) return@LaunchedEffect
            if (line.durationMs <= 0) {
                // Nothing to pace the scroll against, so show the whole line rather than none.
                scrollState.scrollTo(distance)
                return@LaunchedEffect
            }

            while (isActive) {
                // The position only arrives twice a second, so between two of those the scroll is
                // carried by elapsed time, and each new position pulls it back onto the truth.
                // That keeps it smooth without letting it drift away from the speaker.
                var anchoredProgress = 0f
                var anchoredAt = 0L
                var anchoredTo = Long.MIN_VALUE

                // Leaving the loop is what stops the scroll on a pause, and waiting this way rather
                // than in a frame callback is what stops it costing anything while paused.
                while (isActive && controller.state.isPlaying) {
                    // Awaited for its time rather than used as a callback, so the scroll below
                    // stays in the coroutine where it is allowed to suspend.
                    val frameTime = withFrameMillis { it }
                    val positionMs = controller.state.positionMs
                    if (positionMs != anchoredTo) {
                        anchoredProgress = (positionMs - line.startsAtMs).toFloat() /
                            line.durationMs
                        anchoredAt = frameTime
                        anchoredTo = positionMs
                    }
                    val progress = anchoredProgress +
                        (frameTime - anchoredAt).toFloat() / line.durationMs
                    scrollState.scrollTo((distance * progress.coerceIn(0f, 1f)).toInt())
                }
                if (!isActive) break
                snapshotFlow { controller.state.isPlaying }.first { it }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LINE_HEIGHT)
                .padding(horizontal = 16.dp)
                .horizontalScroll(scrollState, enabled = false),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = line.text,
                style = MaterialTheme.typography.titleMedium,
                // Bolder than body text so it stays readable over an arbitrary app underneath.
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
            )
        }
    }

    /**
     * Lock, plus close while unlocked, sitting clear of the card.
     *
     * The close button is hidden once locked on purpose: locking exists to stop stray taps, and a
     * locked card leaves only this row tappable, so leaving a way to dismiss it there would defeat
     * the point. Unlocking first, or the player screen's own toggle, is how it gets closed.
     *
     * Both are kept faint and small: they are an afterthought next to the line, and the bar is at
     * its best when they go unnoticed. They can also be dragged off the bottom edge entirely,
     * leaving nothing but the line on screen.
     */
    @Composable
    private fun SubtitleControls() {
        Row(
            modifier = Modifier.height(CONTROL_ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(
                onClick = ::toggleLocked,
                modifier = Modifier.size(CONTROL_BUTTON_SIZE),
            ) {
                Icon(
                    imageVector = if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    contentDescription = stringResource(
                        if (locked) {
                            MR.strings.audio_floating_subtitle_unlock
                        } else {
                            MR.strings.audio_floating_subtitle_lock
                        },
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = CONTROL_ICON_ALPHA),
                    modifier = Modifier.size(CONTROL_ICON_SIZE),
                )
            }
            if (!locked) {
                IconButton(
                    onClick = ::close,
                    modifier = Modifier.size(CONTROL_BUTTON_SIZE),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(MR.strings.action_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = CONTROL_ICON_ALPHA),
                        modifier = Modifier.size(CONTROL_ICON_SIZE),
                    )
                }
            }
        }
    }

    /**
     * The line playing right now, and how long it stays up.
     *
     * Read through [derivedStateOf] so the twice-a-second position ticks only re-run this lookup.
     * The result is compared by value, so the composition is told when the line changes rather
     * than whenever the position moves.
     */
    private fun currentLine(): SubtitleLine {
        val state = controller.state
        val lyrics = state.lyrics
        val index = lyrics.indexOfLast { it.timeMs <= state.positionMs }
        if (index < 0) return SubtitleLine("", 0L, 0L)
        val line = lyrics[index]
        // Nothing marks the end of a line, so it runs until the next one begins.
        val endsAt = lyrics.getOrNull(index + 1)?.timeMs ?: state.durationMs
        return SubtitleLine(line.text, line.timeMs, (endsAt - line.timeMs).coerceAtLeast(0L))
    }

    private data class SubtitleLine(
        val text: String,
        val startsAtMs: Long,
        val durationMs: Long,
    )

    private data class Conditions(
        val enabled: Boolean,
        val isLocked: Boolean,
        val hasContent: Boolean,
        val playerScreenVisible: Boolean,
    ) {
        val visible: Boolean
            get() = enabled && hasContent && !playerScreenVisible
    }

    private companion object {
        const val WIDTH_RATIO = 0.8f
        const val BACKGROUND_ALPHA = 0.72f
        const val TOP_GAP_DP = 4f

        /** One line of text. The card's background covers this and nothing else. */
        val LINE_HEIGHT = 40.dp

        /** The strip the buttons occupy, below the card rather than inside it. */
        val CONTROL_ROW_HEIGHT = 32.dp

        /**
         * Buttons are smaller than the 48dp default: this window is meant to stay out of the way.
         * The strip is only as wide as the buttons, so the rest of it is left clear.
         */
        val CONTROL_BUTTON_SIZE = 32.dp

        /** Smaller than the tap target it sits in, so it reads lighter than it handles. */
        val CONTROL_ICON_SIZE = 16.dp

        /** Held back so the icons read as part of the background rather than as controls on it. */
        const val CONTROL_ICON_ALPHA = 0.6f
    }
}
