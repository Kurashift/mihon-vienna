package eu.kanade.presentation.audio

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.audio.AudioPlayerController
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

@Composable
fun AudioVolumeControl(
    controller: AudioPlayerController,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val state = controller.state
    val maximum = state.maxMediaVolume.coerceAtLeast(1)
    var dragValue by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(controller) {
        controller.refreshSystemVolume()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 36.dp else 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { controller.setMediaVolume(state.mediaVolume - 1) },
            enabled = !state.isMediaVolumeFixed && state.mediaVolume > 0,
            modifier = Modifier.size(if (compact) 32.dp else 40.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.VolumeDown,
                contentDescription = stringResource(MR.strings.audio_volume_down),
                modifier = Modifier.size(if (compact) 18.dp else 21.dp),
            )
        }
        Slider(
            value = (dragValue ?: state.mediaVolume.toFloat()).coerceIn(0f, maximum.toFloat()),
            onValueChange = { value ->
                dragValue = value
                val target = value.roundToInt()
                if (target != state.mediaVolume) controller.setMediaVolume(target)
            },
            onValueChangeFinished = {
                dragValue = null
                controller.refreshSystemVolume()
            },
            valueRange = 0f..maximum.toFloat(),
            steps = (maximum - 1).coerceAtLeast(0),
            enabled = !state.isMediaVolumeFixed,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { controller.setMediaVolume(state.mediaVolume + 1) },
            enabled = !state.isMediaVolumeFixed && state.mediaVolume < maximum,
            modifier = Modifier.size(if (compact) 32.dp else 40.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = stringResource(MR.strings.audio_volume_up),
                modifier = Modifier.size(if (compact) 18.dp else 21.dp),
            )
        }
    }
}
