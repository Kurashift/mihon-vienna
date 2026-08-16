package eu.kanade.presentation.browse.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Small badge shown on grid covers when a search matched one of the manga chapters,
 * so the user can see why the manga appeared in the results. Long chapter names
 * scroll horizontally (marquee) so they stay readable, but the animation only runs
 * when the text actually overflows and the item is on screen, keeping the list
 * cheap to render.
 */
@Composable
fun MatchedChapterBadge(chapter: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = shape,
            )
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .widthIn(max = 110.dp)
            .clip(shape),
    ) {
        MarqueeText(
            text = chapter,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
) {
    var boxWidth by remember { mutableIntStateOf(0) }
    // Measure the text without any width constraint so overflowing names are
    // detected reliably. onTextLayout reports the constrained (clipped) width,
    // which would never exceed the badge box and would always end up clipped.
    val textMeasurer = rememberTextMeasurer()
    val textWidth = remember(text, style) {
        textMeasurer.measure(
            text = AnnotatedString(text),
            style = style,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(),
        ).size.width
    }

    Box(
        modifier = Modifier
            .onSizeChanged { boxWidth = it.width }
            .clipToBounds(),
    ) {
        if (boxWidth > 0 && textWidth > boxWidth) {
            val density = LocalDensity.current
            // Scroll until the tail of the name is aligned with the right edge of
            // the box, so the end of the text stays visible during the rest pause.
            val scrollDistance = (textWidth - boxWidth).coerceAtLeast(0).toFloat()
            val speed = with(density) { MARQUEE_SPEED_DP_PER_SECOND.toPx() }
            val scrollMillis = (scrollDistance / speed * 1000).toInt()
                .coerceAtLeast(MIN_MARQUEE_SCROLL_MILLIS)
            val cycleMillis = MARQUEE_INITIAL_PAUSE_MILLIS + scrollMillis + MARQUEE_END_PAUSE_MILLIS

            val transition = rememberInfiniteTransition(label = "matchedChapterMarquee")
            val offset by transition.animateFloat(
                initialValue = 0f,
                targetValue = -scrollDistance,
                animationSpec = infiniteRepeatable(
                    // Rest at the start so the name is readable, scroll to the tail at a
                    // calm pace, rest with the tail visible, then loop back to the
                    // start. The box never sits empty while the animation runs.
                    animation = keyframes {
                        durationMillis = cycleMillis
                        0f at 0
                        0f at MARQUEE_INITIAL_PAUSE_MILLIS
                        -scrollDistance at MARQUEE_INITIAL_PAUSE_MILLIS + scrollMillis
                        -scrollDistance at cycleMillis
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "matchedChapterMarqueeOffset",
            )
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.graphicsLayer { translationX = offset },
            )
        } else {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Scroll speed of overflowing chapter names. */
private val MARQUEE_SPEED_DP_PER_SECOND = 40.dp

/** How long the name rests at the start before scrolling. */
private const val MARQUEE_INITIAL_PAUSE_MILLIS = 1_000

/** How long the name rests at the end before looping back. */
private const val MARQUEE_END_PAUSE_MILLIS = 800

/** Floor for the scroll phase so very short names don't blink. */
private const val MIN_MARQUEE_SCROLL_MILLIS = 800
