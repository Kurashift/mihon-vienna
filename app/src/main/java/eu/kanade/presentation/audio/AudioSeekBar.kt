package eu.kanade.presentation.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.audio.AudioPlayerController

@Composable
fun AudioSeekBar(
    controller: AudioPlayerController,
    modifier: Modifier = Modifier,
    showTimes: Boolean = true,
) {
    val state = controller.state
    val duration = state.durationMs.coerceAtLeast(0)
    var dragPosition by remember(state.item?.mediaStreamUrl) { mutableStateOf<Long?>(null) }
    val position = (dragPosition ?: state.positionMs).coerceIn(0, duration)

    Column(modifier = modifier) {
        Slider(
            value = position.toFloat(),
            onValueChange = { dragPosition = it.toLong().coerceIn(0, duration) },
            onValueChangeFinished = {
                dragPosition?.let(controller::seekTo)
                dragPosition = null
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            enabled = !state.isBuffering && duration > 0,
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
        )
        if (showTimes) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatDuration(position),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
