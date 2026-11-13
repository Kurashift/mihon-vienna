package tachiyomi.presentation.core.components.material

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun PillSnackbar(
    message: String,
    onDismiss: () -> Unit,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnPressedChange by rememberUpdatedState(onPressedChange)
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(0f) }
    var animation by remember { mutableStateOf<Job?>(null) }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val dismissDistance = constraints.maxWidth.toFloat() + width
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .onSizeChanged { width = it.width.toFloat() }
                .graphicsLayer { translationX = offset }
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    dismiss {
                        currentOnDismiss()
                        true
                    }
                }
                .pointerInput(Unit) {
                    // Observe before buttons and swipes consume events; never consume their taps.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        currentOnPressedChange(true)
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            currentOnPressedChange(false)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { animation?.cancel() },
                        onHorizontalDrag = { change, amount ->
                            offset += amount
                            change.consume()
                        },
                        onDragEnd = {
                            val dismiss = width > 0f && abs(offset) >= width * 0.25f
                            animation = scope.launch {
                                animate(
                                    initialValue = offset,
                                    targetValue = if (dismiss) {
                                        if (offset < 0f) -dismissDistance else dismissDistance
                                    } else {
                                        0f
                                    },
                                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
                                ) { value, _ -> offset = value }
                                if (dismiss) currentOnDismiss()
                            }
                        },
                        onDragCancel = {
                            animation = scope.launch {
                                animate(offset, 0f, animationSpec = spring()) { value, _ -> offset = value }
                            }
                        },
                    )
                },
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .padding(start = 18.dp, end = if (actionLabel == null) 18.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (actionLabel != null) {
                    TextButton(
                        onClick = onAction,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.inversePrimary,
                        ),
                    ) {
                        Text(text = actionLabel, maxLines = 1)
                    }
                }
            }
        }
    }
}
