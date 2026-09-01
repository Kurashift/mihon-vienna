package eu.kanade.presentation.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/** Direction the FAB was dragged towards; drives the icon and the triggered action. */
private enum class FabDragDirection { RandomManga, RandomGoodDoujin }

/** Distance the finger must travel along the dominant axis to arm the gesture. */
private val DragTriggerDistance: Dp = 36.dp

/** Upper bound for how far the button itself follows the finger. */
private val MaxVisualOffset: Dp = 28.dp

/** Distance past which the icon swaps to the direction-specific one. */
private val IconSwitchDistance: Dp = 12.dp

/** How long the finger must rest before the direction hints appear. */
private const val LongPressHintDelayMillis = 350L

/** Scale applied while the finger rests on the button; the press feedback itself. */
private const val PressedScale = 0.92f

/**
 * Resting opacity of any floating control that sits on top of a cover: the continue reading
 * button on library grid items, and the bottom-start floating button on the manga and source
 * browse screens.
 *
 * A fully solid control punches a hole into the artwork it covers, which is the whole point of
 * it being here, so only a slice of the cover is let through. It stays a plate rather than
 * becoming a bare icon because the plate is what stops the control from dissolving into a cover
 * of a similar colour.
 *
 * How this is applied depends on the control. The floating button fades its whole layer — see
 * the graphicsLayer in [RandomGestureFab] for why. A plain icon button has no shadow, so fading
 * its container colour is equivalent and keeps the glyph at full strength.
 */
const val FloatingControlPlateAlpha = 0.82f

/**
 * The control turns fully solid for as long as the finger is on it. Pressing and dragging are the
 * moments it has to be read against an unknown, moving background, and the direction hints and
 * the drag target are drawn straight onto the cover with no plate of their own, so the control
 * firming up is what anchors them.
 */
private const val EngagedPlateAlpha = 1f

/**
 * Opacity the control drops to once the list behind it has run out of scroll.
 *
 * Every row but the last one can be slid out from under the button by scrolling; the last row
 * has nowhere left to scroll to, so it is the one row the button can never stop covering.
 * Giving up more of itself is how the button hands that row back, and it stays a plate rather
 * than a bare icon so that it remains findable on a cover of a similar colour.
 */
private const val EndOfListPlateAlpha = 0.55f

/**
 * Whether the list behind a floating control has run out of downward scroll and came to rest
 * there, which is the case [RandomGestureFab] dims itself past [FloatingControlPlateAlpha] for.
 *
 * `canScrollBackward` is required as well so that content shorter than the viewport does not
 * count: nothing was scrolled into place there, so nothing is trapped under the button either.
 * The scroll also has to be over, otherwise the plate would flicker while the list is still
 * settling under the finger.
 */
@Composable
fun rememberAtListEnd(scrollState: ScrollableState): Boolean {
    return remember(scrollState) {
        derivedStateOf {
            !scrollState.canScrollForward &&
                scrollState.canScrollBackward &&
                !scrollState.isScrollInProgress
        }
    }.value
}

/**
 * How much of the finger travel the button follows. Tuned together with
 * [TargetDistance] so the button covers the target icon exactly when the gesture
 * triggers, while still keeping the travelled path short.
 */
private const val FollowRatio = 0.75f

/**
 * Where the direction target sits, measured from the button's resting centre. The
 * target is drawn *below* the button, so the button slides over it and swallows it;
 * how much of the target is still showing doubles as the remaining progress, which
 * removes any need for a track or a fill bar.
 */
private val TargetDistance: Dp = 44.dp

private val TargetIconSize: Dp = 20.dp

/**
 * Floating action button with two directional drag actions on top of the plain tap.
 *
 * - Tap: [onTap], handled by the button itself as usual.
 * - Drag up: [onRandomGoodDoujin]
 * - Drag right: [onRandomManga]
 *
 * The gesture has no time gate. The long-press hint timer and the move detection race
 * against each other, so a fast flick is handled on the very first move while a resting
 * finger gets the direction hints after [LongPressHintDelayMillis]. Releasing below
 * [DragTriggerDistance] cancels everything, so a drag can always be reverted by moving
 * back towards the origin.
 *
 * When [gesturesEnabled] is false this behaves exactly like a plain [FloatingActionButton]:
 * no hints, no offset, no drag handling.
 */
@Composable
fun RandomGestureFab(
    onTap: () -> Unit,
    idleIcon: ImageVector,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = false,
    // Set once the list behind the button has run out of downward scroll and came to rest
    // there: the last row is the one row that cannot be slid out from under the button, so
    // the plate dims past FloatingControlPlateAlpha to hand it back.
    atListEnd: Boolean = false,
    idleContentDescription: String? = null,
    onRandomManga: () -> Unit = {},
    onRandomGoodDoujin: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current

    // Follows the finger while dragging. This one does reach composition every frame:
    // [animateOffsetAsState] reads it as its target value, and it is the only way to hand
    // an unanimated offset to it. The composable is small enough that the per-frame
    // recomposition costs nothing measurable.
    var liveOffset by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }
    var isPressed by remember { mutableStateOf(false) }
    var hasMoved by remember { mutableStateOf(false) }

    // Discrete states, only written when a boundary is crossed.
    var direction by remember { mutableStateOf<FabDragDirection?>(null) }
    var showHints by remember { mutableStateOf(false) }

    val interactionSource = remember { MutableInteractionSource() }

    // Follow the finger 1:1 while dragging, and settle back without any bounce.
    val offset by animateOffsetAsState(
        targetValue = if (isDragging) liveOffset else Offset.Zero,
        animationSpec = if (isDragging) snap() else tween(120, easing = FastOutLinearInEasing),
        label = "FabDragOffset",
    )

    // Press feedback is a quick scale instead of the Material ripple: the ripple is
    // press-driven, so it would otherwise flash across the button on every drag too,
    // and its fade-out is far slower than the rest of the interaction. Driven by the
    // button's own press state so it keeps working even if the gesture never starts.
    val buttonPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (buttonPressed && !hasMoved) PressedScale else 1f,
        animationSpec = tween(80, easing = FastOutLinearInEasing),
        label = "FabPressScale",
    )

    // Solid as soon as the finger is down, and for the whole drag — not just while the button
    // reports a press. The gesture consumes the move events, which drops the button's own press
    // state, so keying off the press alone would let it fade back while the finger is still on
    // it: a translucent control no longer covers the drag target it is sliding over, leaving a
    // second dice visible behind the button. A tap has to feel answered before it settles back.
    val restingAlpha = if (atListEnd) EndOfListPlateAlpha else FloatingControlPlateAlpha
    val isEngaged = buttonPressed || isDragging
    val plateAlpha by animateFloatAsState(
        targetValue = if (isEngaged) EngagedPlateAlpha else restingAlpha,
        // Solidifying is the button answering the finger, so it is quick. Everything else is
        // the button drifting back out of the way, which reads better unhurried.
        animationSpec = tween(if (isEngaged) 80 else 160, easing = FastOutLinearInEasing),
        label = "FabPlateAlpha",
    )

    // Hints are a courtesy for a resting finger. Any movement wins the race and cancels them.
    LaunchedEffect(isPressed, hasMoved) {
        if (!isPressed || hasMoved) return@LaunchedEffect
        delay(LongPressHintDelayMillis)
        showHints = true
    }

    val hintAlpha by animateFloatAsState(
        targetValue = if (showHints) 1f else 0f,
        animationSpec = tween(100, easing = FastOutLinearInEasing),
        label = "FabHintAlpha",
    )
    // The target is the drag destination: it shows as soon as the drag starts and is
    // covered by the button as the finger approaches the trigger point.
    val targetAlpha by animateFloatAsState(
        targetValue = if (isDragging && direction != null) 1f else 0f,
        animationSpec = tween(if (isDragging && direction != null) 100 else 80, easing = FastOutLinearInEasing),
        label = "FabTargetAlpha",
    )

    val randomMangaIcon = Icons.Outlined.Casino
    val randomGoodDoujinIcon = ImageVector.vectorResource(R.drawable.ic_dice_heart_24dp)
    val randomMangaLabel = stringResource(MR.strings.action_open_random_manga)
    val randomGoodDoujinLabel = stringResource(MR.strings.action_open_random_good_doujin)

    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val triggerPx = with(density) { DragTriggerDistance.toPx() }
    val iconSwitchPx = with(density) { IconSwitchDistance.toPx() }
    val maxOffsetPx = with(density) { MaxVisualOffset.toPx() }
    val targetDistancePx = with(density) { TargetDistance.toPx() }

    val dragModifier = if (gesturesEnabled) {
        Modifier.pointerInput(triggerPx, iconSwitchPx, maxOffsetPx, touchSlop) {
            awaitEachGesture {
                // The button's own clickable sits *below* this node and receives the
                // down first (the Main pass dispatches child-first), consuming it.
                // Requiring an unconsumed down would make this never resolve, so the
                // gesture must accept the consumed down. Taps stay with the button
                // itself for the same reason: it sees the up before we do.
                val down = awaitFirstDown(requireUnconsumed = false)
                var dragDirection: FabDragDirection? = null
                var isArmed = false
                var lastPosition = down.position
                var endedWithUp = false

                isPressed = true
                hasMoved = false
                isDragging = true

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    lastPosition = change.position
                    if (!change.pressed) {
                        endedWithUp = change.changedToUp()
                        break
                    }

                    val delta = change.position - down.position
                    if (!hasMoved && delta.getDistance() > touchSlop) {
                        hasMoved = true
                        showHints = false
                    }
                    if (!hasMoved) continue

                    // Keep the underlying button from treating this drag as a tap.
                    change.consume()

                    // Dominant axis wins, so a diagonal drag has a stable owner instead
                    // of flickering between the two actions.
                    val current = if (abs(delta.y) > abs(delta.x) && delta.y < 0) {
                        FabDragDirection.RandomGoodDoujin
                    } else {
                        FabDragDirection.RandomManga
                    }
                    dragDirection = current

                    val along = if (current == FabDragDirection.RandomGoodDoujin) -delta.y else delta.x
                    direction = if (along >= iconSwitchPx) current else null

                    val applied = (along.coerceAtLeast(0f) * FollowRatio).coerceAtMost(maxOffsetPx)
                    liveOffset = if (current == FabDragDirection.RandomGoodDoujin) {
                        Offset(0f, -applied)
                    } else {
                        Offset(applied, 0f)
                    }

                    // Only on the crossing, so the feedback fires once per drag.
                    val shouldArm = along >= triggerPx
                    if (shouldArm != isArmed) {
                        isArmed = shouldArm
                        if (shouldArm) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }

                if (endedWithUp) {
                    val delta = lastPosition - down.position
                    val travelled = if (dragDirection == FabDragDirection.RandomGoodDoujin) {
                        -delta.y
                    } else {
                        delta.x
                    }
                    if (hasMoved && travelled >= triggerPx) {
                        when (dragDirection) {
                            FabDragDirection.RandomGoodDoujin -> onRandomGoodDoujin()
                            FabDragDirection.RandomManga -> onRandomManga()
                            null -> Unit
                        }
                    }
                    // A tap is not handled here: the button's own clickable sees the up
                    // before this node does, and it already skips the action whenever
                    // the finger moved.
                }

                isPressed = false
                hasMoved = false
                isDragging = false
                liveOffset = Offset.Zero
                direction = null
                showHints = false
            }
        }
    } else {
        Modifier
    }

    // The gesture is measured on this Box, which never moves. Measuring on the button
    // itself would self-cancel: the button follows the finger, so the pointer's local
    // position inside it only advances by the *difference* between finger travel and
    // button travel, making the trigger point unreachable.
    Box(modifier = modifier.then(dragModifier), contentAlignment = Alignment.Center) {
        // Direction hints: only for a resting finger, never together with the target.
        if (gesturesEnabled && hintAlpha > 0f) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowUp,
                contentDescription = randomGoodDoujinLabel,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-40).dp)
                    .size(20.dp)
                    .graphicsLayer { alpha = hintAlpha * 0.35f },
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = randomMangaLabel,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 40.dp)
                    .size(20.dp)
                    .graphicsLayer { alpha = hintAlpha * 0.35f },
            )
        }

        // Drag target: sits at the end of the travel and is drawn *under* the button,
        // so the button slides over it. How much of it is still visible is the remaining
        // distance, which is why no track or fill bar is needed.
        val targetDirection = direction
        if (gesturesEnabled && targetAlpha > 0f && targetDirection != null) {
            val isUp = targetDirection == FabDragDirection.RandomGoodDoujin
            Icon(
                imageVector = if (isUp) randomGoodDoujinIcon else randomMangaIcon,
                contentDescription = if (isUp) randomGoodDoujinLabel else randomMangaLabel,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset {
                        val distance = targetDistancePx.roundToInt()
                        IntOffset(if (isUp) 0 else distance, if (isUp) -distance else 0)
                    }
                    .size(TargetIconSize)
                    .graphicsLayer { alpha = targetAlpha * 0.9f },
            )
        }

        // No ripple on this button: the quick scale below is the press feedback, and a
        // ripple is press-driven so it would streak across the button on every drag.
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            FloatingActionButton(
                // The button keeps handling the tap. hasMoved is still true when the
                // button sees the up (it is a child of this node, so it is dispatched
                // to first) which is what stops a drag from also firing a tap.
                onClick = { if (!hasMoved) onTap() },
                interactionSource = interactionSource,
                modifier = Modifier
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .graphicsLayer {
                        scaleX = pressScale
                        scaleY = pressScale
                        // Fade the whole control as one layer rather than only the container
                        // colour, so a translucent container cannot leave anything drawn
                        // behind it shining through, and so the button still covers the drag
                        // target it is meant to swallow.
                        alpha = plateAlpha
                    },
                // No elevation. The plate is translucent by design so the cover behind it
                // still shows through; a drop shadow is the opposite of that — it is fully
                // opaque darkness laid around the plate, and because the plate is a rounded
                // square the shadow reads as a blocky square halo around the control. The
                // plate's own colour is what keeps it findable, so the shadow has no job
                // here and is dropped entirely.
                elevation = FloatingActionButtonDefaults.loweredElevation(),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Crossfade(
                    targetState = direction,
                    animationSpec = tween(100, easing = FastOutLinearInEasing),
                    label = "FabIcon",
                ) { target ->
                    when (target) {
                        FabDragDirection.RandomGoodDoujin -> Icon(
                            imageVector = randomGoodDoujinIcon,
                            contentDescription = randomGoodDoujinLabel,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        FabDragDirection.RandomManga -> Icon(
                            imageVector = randomMangaIcon,
                            contentDescription = randomMangaLabel,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        null -> Icon(
                            imageVector = idleIcon,
                            contentDescription = idleContentDescription,
                        )
                    }
                }
            }
        }
    }
}
