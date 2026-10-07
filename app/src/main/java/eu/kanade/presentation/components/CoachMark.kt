package eu.kanade.presentation.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * One spotlight step: what the bubble shows and which registered anchor it points at. A step
 * carries either a sentence ([text]) or a few icon-and-word rows ([iconRows]) — the short form
 * reads closer to the app's own hint language. A step with a null [anchorId] — or one whose
 * anchor never reports — renders as a centered, hole-free bubble over the full-screen scrim.
 */
class CoachStep(
    val anchorId: String? = null,
    val text: String = "",
    val iconRows: List<CoachIconRow> = emptyList(),
)

/** One icon-plus-word line of a [CoachStep] bubble. */
class CoachIconRow(
    val icon: ImageVector,
    val text: String,
)

/**
 * State of one coach-mark sequence. Anchors report their window-space bounds through
 * [Modifier.coachAnchor]; the overlay reads the current step's anchor from here while drawing.
 */
class CoachMarkState {

    val anchors = mutableStateMapOf<String, Rect>()

    var steps: List<CoachStep> = emptyList()
        private set

    /** Index of the step currently on screen, or -1 while inactive. */
    var index by mutableIntStateOf(-1)
        private set

    val isActive: Boolean
        get() = index in steps.indices

    val currentStep: CoachStep?
        get() = steps.getOrNull(index)

    fun start(steps: List<CoachStep>) {
        if (steps.isEmpty()) return
        this.steps = steps
        index = 0
    }

    fun advance() {
        index = if (index + 1 < steps.size) index + 1 else -1
    }

    fun finish() {
        index = -1
    }
}

/**
 * Reports this node's window-space bounds into [state] under [id] for as long as it exists.
 * Applied directly on the control a spotlight points at, so the control owns its registration
 * and intermediate composables do not have to thread the anchor through.
 */
fun Modifier.coachAnchor(state: CoachMarkState, id: String): Modifier = composed {
    DisposableEffect(id) {
        onDispose { state.anchors.remove(id) }
    }
    onGloballyPositioned { coordinates ->
        state.anchors[id] = Rect(
            coordinates.positionInRoot(),
            Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()),
        )
    }
}

/**
 * Hands the active [CoachMarkState] down to the control a step points at. Provided only while
 * a sequence may run; a null value tells controls like the browse FAB to skip registering
 * altogether.
 */
val LocalCoachAnchorRegistry = compositionLocalOf<CoachMarkState?> { null }

private val ScrimColor = Color(0xB3000000)

private const val ANCHOR_TIMEOUT_MS = 3000L

/**
 * Full-screen spotlight sequence: a scrim with a hole over the current anchor and a bubble
 * beside it. Tapping anywhere advances; the last tap ends the sequence. The bubble carries a
 * skip action, and Back ends the whole thing.
 */
@Composable
fun CoachMarkOverlay(
    state: CoachMarkState,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onStepEntered: (Int) -> Unit = {},
) {
    val step = state.currentStep ?: return

    BackHandler {
        state.finish()
        onFinished()
    }

    LaunchedEffect(state.index) {
        if (state.isActive) onStepEntered(state.index)
    }

    // A step whose anchor never shows up is skipped silently rather than blocking the rest.
    LaunchedEffect(step) {
        val anchorId = step.anchorId ?: return@LaunchedEffect
        delay(ANCHOR_TIMEOUT_MS)
        if (state.anchors[anchorId] == null) {
            state.advance()
            if (!state.isActive) onFinished()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures {
                    state.advance()
                    if (!state.isActive) onFinished()
                }
            },
    ) {
        val density = LocalDensity.current
        val holePaddingPx = with(density) { 8.dp.toPx() }
        val cornerPx = with(density) { 12.dp.toPx() }
        val gapPx = with(density) { 16.dp.toPx() }
        val marginPx = with(density) { 24.dp.toPx() }
        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxHeightPx = constraints.maxHeight.toFloat()

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCoachScrim(
                hole = step.anchorId?.let { state.anchors[it] }?.inflate(holePaddingPx),
                cornerRadiusPx = cornerPx,
                scrimColor = ScrimColor,
            )
        }

        val anchorRect = step.anchorId?.let { state.anchors[it] }
        var bubbleSize by remember { mutableStateOf(IntSize.Zero) }

        // Above the anchor when it sits in the lower half, below it otherwise; the bubble is
        // clamped to the screen on both axes so a corner anchor never pushes it out of view.
        val rawX = anchorRect?.let { it.center.x - bubbleSize.width / 2f }
            ?: ((maxWidthPx - bubbleSize.width) / 2f)
        val rawY = when {
            anchorRect == null -> (maxHeightPx - bubbleSize.height) / 2f
            anchorRect.center.y > maxHeightPx / 2f -> anchorRect.top - bubbleSize.height - gapPx
            else -> anchorRect.bottom + gapPx
        }
        val maxX = (maxWidthPx - bubbleSize.width - marginPx).coerceAtLeast(marginPx)
        val maxY = (maxHeightPx - bubbleSize.height - marginPx).coerceAtLeast(marginPx)
        val offset = IntOffset(
            rawX.coerceIn(marginPx, maxX).roundToInt(),
            rawY.coerceIn(marginPx, maxY).roundToInt(),
        )

        // The bubble stays invisible until its first measure reports a size, so the very first
        // frame does not flash it at the top-left corner before the offset is known.
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 3.dp,
            modifier = Modifier
                .onSizeChanged { bubbleSize = it }
                .widthIn(max = 340.dp)
                .absoluteOffset { offset }
                .alpha(if (bubbleSize == IntSize.Zero) 0f else 1f),
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 6.dp),
            ) {
                if (step.text.isNotEmpty()) {
                    Text(
                        text = step.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                step.iconRows.forEach { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = row.icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = row.text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Text(
                    text = stringResource(MR.strings.onboarding_action_skip),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inversePrimary,
                    modifier = Modifier
                        .align(Alignment.End)
                        .clickable {
                            state.finish()
                            onFinished()
                        }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private fun DrawScope.drawCoachScrim(hole: Rect?, cornerRadiusPx: Float, scrimColor: Color) {
    if (hole == null) {
        drawRect(color = scrimColor)
        return
    }
    val path = Path()
    path.addRect(Rect(Offset.Zero, size))
    path.addRoundRect(RoundRect(hole, CornerRadius(cornerRadiusPx)))
    path.fillType = PathFillType.EvenOdd
    drawPath(path, scrimColor)
}

/**
 * One-shot non-blocking hint: the same face as the root back-exit hint — a capsule with no
 * pointer-input modifiers, so taps fall through to whatever runs underneath. Used where a
 * spotlight would be louder than the message deserves.
 */
@Composable
fun CoachHintPill(
    text: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(90)) + scaleIn(initialScale = 0.9f, animationSpec = tween(90)),
        exit = fadeOut(tween(150)),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 3.dp,
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
