package eu.kanade.presentation.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * One annotation of a spotlight step: a short line of white-on-scrim text drawn where the
 * gesture happens — the same face as the reader's tap-zone overlay — instead of a capsule.
 * Arrows travel inside the text itself ("↑ 上拖 · 好本子").
 */
class CoachAnnotation(
    val text: String,
    val placement: CoachAnnotationPlacement,
)

enum class CoachAnnotationPlacement {
    /** Stacked upward from just above the anchor, horizontally centered on it. */
    Above,

    /** To the anchor's trailing side, starting vertically centered on it. */
    RightOf,
}

/**
 * One spotlight step: a scrim with a hole over the registered anchor plus a few annotation
 * lines around it. A step with a null [anchorId] — or one whose anchor never reports — shows
 * the scrim alone; there is nothing to point at, so the step simply waits to be tapped away.
 */
class CoachStep(
    val anchorId: String? = null,
    val annotations: List<CoachAnnotation> = emptyList(),
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
 * Full-screen spotlight: a scrim with a hole over the current anchor and the step's
 * annotation lines drawn beside it, in the reader's white-with-black-stroke style. There is
 * no separate bubble and no skip button — a tap anywhere moves on, and Back ends the whole
 * thing, mirroring how the reader's own tap-zone overlay works.
 */
@Composable
fun CoachMarkOverlay(
    state: CoachMarkState,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = state.currentStep ?: return

    BackHandler {
        state.finish()
        onFinished()
    }

    // A step whose anchor never shows up ends silently rather than blocking the screen.
    LaunchedEffect(step) {
        val anchorId = step.anchorId ?: return@LaunchedEffect
        delay(ANCHOR_TIMEOUT_MS)
        if (state.anchors[anchorId] == null) {
            state.finish()
            onFinished()
        }
    }

    val textMeasurer = rememberTextMeasurer()

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

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCoachScrim(
                hole = step.anchorId?.let { state.anchors[it] }?.inflate(holePaddingPx),
                cornerRadiusPx = with(density) { 12.dp.toPx() },
                scrimColor = ScrimColor,
            )
            val anchor = step.anchorId?.let { state.anchors[it] } ?: return@Canvas
            drawCoachAnnotations(textMeasurer, step.annotations, anchor)
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

private fun DrawScope.drawCoachAnnotations(
    textMeasurer: TextMeasurer,
    annotations: List<CoachAnnotation>,
    anchor: Rect,
) {
    // White fill over a black stroke, exactly how the reader's overlay draws its labels —
    // the stroke keeps the line legible wherever it slides off the scrim onto bright art.
    val fontSize = 15.sp
    val fillStyle = TextStyle(color = Color.White, fontSize = fontSize)
    val strokeStyle = TextStyle(
        color = Color.Black,
        fontSize = fontSize,
        drawStyle = Stroke(width = 2.dp.toPx()),
    )
    val marginPx = 16.dp.toPx()

    var nextBottom = anchor.top - 14.dp.toPx()
    annotations
        .filter { it.placement == CoachAnnotationPlacement.Above }
        .forEach { ann ->
            val measured = textMeasurer.measure(ann.text, fillStyle)
            val x = (anchor.center.x - measured.size.width / 2f)
                .coerceIn(marginPx, (size.width - marginPx - measured.size.width).coerceAtLeast(marginPx))
            val top = nextBottom - measured.size.height
            drawText(textMeasurer, ann.text, topLeft = Offset(x, top), style = strokeStyle)
            drawText(textMeasurer, ann.text, topLeft = Offset(x, top), style = fillStyle)
            nextBottom = top - 8.dp.toPx()
        }

    var nextCenter = anchor.center.y
    annotations
        .filter { it.placement == CoachAnnotationPlacement.RightOf }
        .forEach { ann ->
            val measured = textMeasurer.measure(ann.text, fillStyle)
            val x = (anchor.right + 20.dp.toPx())
                .coerceAtMost((size.width - marginPx - measured.size.width).coerceAtLeast(marginPx))
            val top = nextCenter - measured.size.height / 2f
            drawText(textMeasurer, ann.text, topLeft = Offset(x, top), style = strokeStyle)
            drawText(textMeasurer, ann.text, topLeft = Offset(x, top), style = fillStyle)
            nextCenter = top + measured.size.height + 8.dp.toPx()
        }
}
