package eu.kanade.presentation.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * One annotation of a spotlight step: an icon plus a short line, sitting where the gesture
 * happens. Rendered as a plain composable — the same small capsule as the root back-exit
 * hint — so the text keeps the system's own rendering instead of a hand-drawn imitation.
 */
class CoachAnnotation(
    val text: String,
    val placement: CoachAnnotationPlacement,
    val icon: ImageVector? = null,
)

enum class CoachAnnotationPlacement {
    /** Stacked upward from just above the anchor, horizontally centered on it. */
    Above,

    /** To the anchor's trailing side, vertically centered on it. */
    Side,
}

/**
 * One spotlight step: a scrim with a hole over the registered anchor plus the step's
 * annotation capsules around it. A step with a null [anchorId] — or one whose anchor never
 * reports — shows the scrim alone; there is nothing to point at, so it waits to be tapped
 * away. No skip button: a tap anywhere moves on, Back ends the whole thing.
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
 * annotation capsules placed around it. Tapping anywhere moves on, and Back ends the whole
 * thing.
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
        val maxWidthPx = constraints.maxWidth.toFloat()

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCoachScrim(
                hole = step.anchorId?.let { state.anchors[it] }?.inflate(holePaddingPx),
                cornerRadiusPx = with(density) { 12.dp.toPx() },
                scrimColor = ScrimColor,
            )
        }

        val anchorRect = step.anchorId?.let { state.anchors[it] } ?: return@BoxWithConstraints

        // The annotations above the anchor stack as one column whose bottom edge sits just
        // over the hole; the whole column is clamped horizontally so a wide line over a
        // corner-anchored target cannot slide off the screen edge.
        val above = step.annotations.filter { it.placement == CoachAnnotationPlacement.Above }
        if (above.isNotEmpty()) {
            var columnSize by remember { mutableStateOf(IntSize.Zero) }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .onSizeChanged { columnSize = it }
                    .absoluteOffset {
                        val x = (anchorRect.center.x - columnSize.width / 2f)
                            .coerceIn(16f, (maxWidthPx - 16f - columnSize.width).coerceAtLeast(16f))
                        IntOffset(
                            x.roundToInt(),
                            (anchorRect.top - with(density) { 10.dp.toPx() } - columnSize.height)
                                .roundToInt(),
                        )
                    }
                    .alpha(if (columnSize == IntSize.Zero) 0f else 1f),
            ) {
                above.forEach { ann -> CoachAnnotationCapsule(ann) }
            }
        }

        val side = step.annotations.filter { it.placement == CoachAnnotationPlacement.Side }
        if (side.isNotEmpty()) {
            var columnSize by remember { mutableStateOf(IntSize.Zero) }
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .onSizeChanged { columnSize = it }
                    .absoluteOffset {
                        val x = (anchorRect.right + with(density) { 10.dp.toPx() })
                            .coerceAtLeast(16f)
                            .coerceAtMost((maxWidthPx - 16f - columnSize.width).coerceAtLeast(16f))
                        IntOffset(
                            x.roundToInt(),
                            (anchorRect.center.y - columnSize.height / 2f).roundToInt(),
                        )
                    }
                    .alpha(if (columnSize == IntSize.Zero) 0f else 1f),
            ) {
                side.forEach { ann -> CoachAnnotationCapsule(ann) }
            }
        }
    }
}

@Composable
private fun CoachAnnotationCapsule(ann: CoachAnnotation) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 2.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            ann.icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(text = ann.text, style = MaterialTheme.typography.bodyMedium)
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
