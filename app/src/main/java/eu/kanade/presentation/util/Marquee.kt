package eu.kanade.presentation.util

import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Gap between repetitions, roughly two characters wide. */
private val MARQUEE_GAP = 48.dp

/** Default is 30.dp per second, which reads as sluggish next to these titles. */
private val MARQUEE_SPEED_PER_SECOND = 45.dp

/**
 * Marquee for single line titles that overflow.
 *
 * Three defaults deliberately differ from [basicMarquee]:
 *
 * - [MarqueeAnimationMode.Immediately] instead of WhileFocused. These titles sit inside bars
 *   that hide and show, and next to rows that take focus when tapped. Focus is therefore not
 *   something the animation can rely on: losing it froze the text part way through a scroll.
 *
 * - A fixed gap instead of the default third of the container width, which scaled with the bar
 *   and dragged a long empty stretch through between repetitions. Seamless spacing (zero) was
 *   tried first and ran the tail of the text straight into the head of the next pass.
 *
 * - A faster velocity than the default, so the text reaches the end in a reasonable time.
 *
 * Text that fits stays still either way, so short titles are unaffected.
 */
@Composable
fun Modifier.marqueeTitle(repeatDelayMillis: Int = 1_000): Modifier {
    val gapPx = with(LocalDensity.current) { MARQUEE_GAP.roundToPx() }
    return basicMarquee(
        animationMode = MarqueeAnimationMode.Immediately,
        repeatDelayMillis = repeatDelayMillis,
        spacing = MarqueeSpacing { _, _ -> gapPx },
        velocity = MARQUEE_SPEED_PER_SECOND,
    )
}
