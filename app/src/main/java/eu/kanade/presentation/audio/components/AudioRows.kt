package eu.kanade.presentation.audio.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.audio.formatDuration

/**
 * Indentation shared by every audio row so that folder/track nesting reads the same
 * in the work details tree, the playlist sheet and the full playlist screen.
 */
internal fun audioRowStart(depth: Int): Dp = 12.dp + 16.dp * depth

/**
 * Trailing margin shared by every audio row.
 *
 * A row that ends in an icon button already carries that button's own internal padding, so it
 * only needs a token nudge away from the screen edge. A row that ends in plain text — a duration
 * or a track count — has nothing padding it, so it needs the full margin; without it the text
 * sits flush against the edge while everything else in the row is inset, which reads as lopsided.
 */
internal fun audioRowEnd(hasPaddedTrailing: Boolean): Dp = if (hasPaddedTrailing) 4.dp else 16.dp

/** A folder in an audio track tree. */
@Composable
fun AudioFolderRow(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    depth: Int = 0,
    trackCount: Int? = null,
    actions: (@Composable () -> Unit)? = null,
    selectionState: ToggleableState? = null,
    onToggleSelection: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    // A non-null state is what puts the row in selection mode: taps then toggle instead of
    // expanding, and the leading arrow gives way to a checkbox.
    val inSelectionMode = selectionState != null
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = {
                    if (inSelectionMode && onToggleSelection != null) {
                        onToggleSelection()
                    } else {
                        onClick()
                    }
                },
                onLongClick = onLongClick,
            )
            .padding(
                // The checkbox reserves a 48dp touch target around a much smaller icon, so pull
                // the row back by half the difference to keep the indentation ladder intact.
                start = if (inSelectionMode) {
                    (audioRowStart(depth) - 14.dp).coerceAtLeast(0.dp)
                } else {
                    audioRowStart(depth)
                },
                end = audioRowEnd(actions != null),
                top = 10.dp,
                bottom = 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inSelectionMode) {
            TriStateCheckbox(
                state = selectionState!!,
                onClick = onToggleSelection,
            )
        } else {
            Icon(
                imageVector = if (expanded) {
                    Icons.Outlined.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 6.dp)
                .size(20.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        if (trackCount != null) {
            Text(
                text = trackCount.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        actions?.invoke()
    }
}

/**
 * A single audio track.
 *
 * Trailing controls are supplied by the caller through [actions], so the same row serves the
 * read-only playlist sheet (no actions), the playlist screen and the work details tree (add/
 * remove from playlist).
 *
 * [selectionState] doubles as the switch for selection mode: while it is non-null a tap toggles
 * the track instead of playing it, and the leading number or play icon becomes a checkbox.
 */
@Composable
fun AudioTrackRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
    durationMs: Long = 0L,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    depth: Int = 0,
    actions: (@Composable () -> Unit)? = null,
    selectionState: ToggleableState? = null,
    onToggleSelection: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val inSelectionMode = selectionState != null
    val highlight = MaterialTheme.colorScheme.secondaryContainer
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (isCurrent) Modifier.background(highlight) else Modifier)
            .combinedClickable(
                onClick = {
                    if (inSelectionMode && onToggleSelection != null) {
                        onToggleSelection()
                    } else {
                        onClick()
                    }
                },
                onLongClick = onLongClick,
            )
            .padding(
                // Same half-the-difference correction as the folder row, keeping the checkbox
                // centred where the 32dp leading slot used to sit.
                start = if (inSelectionMode) {
                    (audioRowStart(depth) - 8.dp).coerceAtLeast(0.dp)
                } else {
                    audioRowStart(depth)
                },
                end = audioRowEnd(actions != null),
                top = 10.dp,
                bottom = 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(if (inSelectionMode) 48.dp else 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (inSelectionMode) {
                Checkbox(
                    checked = selectionState == ToggleableState.On,
                    onCheckedChange = null,
                )
            } else {
                when {
                    isCurrent -> Icon(
                        imageVector = if (isPlaying) {
                            Icons.Outlined.GraphicEq
                        } else {
                            Icons.Outlined.PlayArrow
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )

                    // A number is information, so it stays muted; a bare icon stands for the "play"
                    // action itself (used by surfaces that have no position to show) and reads as one.
                    number != null -> Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        if (durationMs > 0) {
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        actions?.invoke()
    }
}

/** Cover thumbnail with a headphone placeholder for works without artwork. */
@Composable
fun AudioCover(
    coverUrl: String?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    cornerSize: Dp = 6.dp,
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(cornerSize)),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (coverUrl.isNullOrBlank()) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Headphones,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AsyncImage(
                model = coverUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
            )
        }
    }
}
