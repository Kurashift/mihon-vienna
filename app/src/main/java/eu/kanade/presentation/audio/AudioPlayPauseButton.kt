package eu.kanade.presentation.audio

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Shared play/pause button used across all audio surfaces (full player, fullscreen video overlay,
 * mini player bar, and reader floating bars). Centralizes the buffering spinner contrast color and
 * accessibility labels so individual surfaces cannot drift out of sync.
 */
@Composable
internal fun AudioPlayPauseButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
    buttonSize: Dp,
    spinnerSize: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp? = null,
    spinnerStrokeWidth: Dp = 2.dp,
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(buttonSize),
    ) {
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(spinnerSize),
                strokeWidth = spinnerStrokeWidth,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Icon(
                imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) MR.strings.action_pause else MR.strings.action_play,
                ),
                modifier = if (iconSize != null) Modifier.size(iconSize) else Modifier,
            )
        }
    }
}
