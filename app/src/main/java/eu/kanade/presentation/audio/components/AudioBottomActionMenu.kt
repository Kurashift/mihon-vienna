package eu.kanade.presentation.audio.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * One action in [AudioBottomActionMenu].
 *
 * [enabled] is false when the action would do nothing — removing an empty selection, for instance —
 * so the bar keeps its shape instead of dropping a button and re-flowing the rest.
 */
data class AudioBottomAction(
    val title: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The bulk-action bar every audio list shows while selecting.
 *
 * Mirrors the app's other selection bars (history, chapter lists): one row above the navigation
 * inset, animated in from the bottom. It replaces the mini player rather than stacking on top of
 * it — while a selection is being made, that is the task at hand.
 *
 * Only the destructive action lives down here; select-all and invert sit in the top bar, where the
 * rest of the app puts them. A lone action gets its label spelled out: there is room for it, and
 * an icon on its own would have to be guessed at.
 */
@Composable
fun AudioBottomActionMenu(
    visible: Boolean,
    actions: List<AudioBottomAction>,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Bottom),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.large.copy(bottomEnd = ZeroCornerSize, bottomStart = ZeroCornerSize),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .only(WindowInsetsSides.Bottom),
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                if (actions.size == 1) {
                    LabelledAction(actions.first())
                } else {
                    actions.forEach { action ->
                        ActionButton(action)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.LabelledAction(action: AudioBottomAction) {
    Box(
        modifier = Modifier
            .height(44.dp)
            .weight(1f)
            .combinedClickable(
                interactionSource = null,
                indication = ripple(bounded = false),
                enabled = action.enabled,
                onClick = action.onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.title,
                tint = action.tint(),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = action.title,
                style = MaterialTheme.typography.labelLarge,
                color = action.tint(),
            )
        }
    }
}

@Composable
private fun AudioBottomAction.tint() = if (enabled) {
    LocalContentColor.current
} else {
    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
}

@Composable
private fun RowScope.ActionButton(action: AudioBottomAction) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .weight(1f)
            .combinedClickable(
                interactionSource = null,
                indication = ripple(bounded = false),
                enabled = action.enabled,
                onClick = action.onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.title,
                tint = action.tint(),
            )
        }
    }
}
