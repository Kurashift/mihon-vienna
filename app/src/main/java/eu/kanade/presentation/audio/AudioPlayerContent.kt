package eu.kanade.presentation.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.util.marqueeTitle
import eu.kanade.tachiyomi.data.audio.AudioQualityMode
import eu.kanade.tachiyomi.data.audio.AudioSubtitleDisplayMode
import eu.kanade.tachiyomi.data.audio.AudioSubtitleState
import eu.kanade.tachiyomi.data.audio.LyricLine
import eu.kanade.tachiyomi.ui.audio.AudioPlayerController
import eu.kanade.tachiyomi.ui.audio.AudioPlayerState
import kotlinx.coroutines.flow.first
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun AudioPlayerContent(
    controller: AudioPlayerController,
    state: AudioPlayerState,
    lyrics: List<LyricLine>,
    subtitleState: AudioSubtitleState,
    displayMode: AudioSubtitleDisplayMode,
    isFavorite: Boolean,
    floatingSubtitleEnabled: Boolean,
    navigateUp: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRetrySubtitle: () -> Unit,
    onCycleSubtitleDisplayMode: () -> Unit,
    onToggleFloatingSubtitle: () -> Unit,
    onOpenWorkDetail: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleLoop: () -> Unit,
    onCyclePlaybackSpeed: () -> Unit,
    onCycleAudioQuality: () -> Unit,
    onSetSleepTimer: (Int?) -> Unit,
) {
    var dragPosition by remember { mutableStateOf<Long?>(null) }
    var showSleepTimer by remember { mutableStateOf(false) }
    val item = state.item
    val duration = state.durationMs.coerceAtLeast(0)
    val position = (dragPosition ?: state.positionMs).coerceIn(0, duration)

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                // The work title doubles as the way back to the work details, so the player can
                // be opened from anywhere without losing the path back upstream.
                titleContent = {
                    val titleText = item?.workTitle ?: stringResource(MR.strings.audio_title)
                    Text(
                        text = titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (item == null) {
                            Modifier
                        } else {
                            Modifier
                                .clickable(onClick = onOpenWorkDetail)
                                .marqueeTitle()
                        },
                    )
                },
                navigateUp = navigateUp,
                actions = {
                    if (item != null) {
                        IconButton(onClick = onToggleFavorite) {
                            Icon(
                                imageVector = if (isFavorite) {
                                    Icons.Outlined.Favorite
                                } else {
                                    Icons.Outlined.FavoriteBorder
                                },
                                contentDescription = stringResource(
                                    if (isFavorite) MR.strings.audio_favorite_remove else MR.strings.audio_favorite_add,
                                ),
                                tint = if (isFavorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            // The cover gives up its place as the subtitle panel grows, so the transcript is what
            // gets the extra height instead of staying squeezed between the art and the controls.
            val coverSize = when (displayMode) {
                AudioSubtitleDisplayMode.STANDARD ->
                    minOf(maxWidth - 56.dp, if (maxHeight < 700.dp) 196.dp else 292.dp)
                // Never fully gone: a small thumbnail keeps the cycle reachable by tapping it and
                // keeps the work recognisable while the subtitles take over the screen.
                AudioSubtitleDisplayMode.IMMERSIVE -> IMMERSIVE_COVER_SIZE
            }
            val immersive = displayMode == AudioSubtitleDisplayMode.IMMERSIVE
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(if (immersive) 6.dp else 10.dp))
                if (immersive) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayerCover(
                            coverUrl = item?.coverUrl,
                            contentDescription = item?.workTitle,
                            size = coverSize,
                            onClick = onCycleSubtitleDisplayMode,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item?.trackTitle.orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.marqueeTitle(),
                            )
                            item?.let { current ->
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = current.circleName.ifBlank { current.workTitle },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else {
                    PlayerCover(
                        coverUrl = item?.coverUrl,
                        contentDescription = item?.workTitle,
                        size = coverSize,
                        onClick = onCycleSubtitleDisplayMode,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = item?.trackTitle.orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (item != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = item.circleName.ifBlank { item.workTitle },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (state.totalCount > 1) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(MR.strings.audio_player_position, state.index + 1, state.totalCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LyricsPanel(
                    lyrics = lyrics,
                    subtitleState = subtitleState,
                    displayMode = displayMode,
                    position = position,
                    onSeek = onSeek,
                    onRetry = onRetrySubtitle,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )

                Slider(
                    value = position.toFloat(),
                    onValueChange = { dragPosition = it.toLong() },
                    onValueChangeFinished = {
                        dragPosition?.let(onSeek)
                        dragPosition = null
                    },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    // Buffering no longer disables the slider: doing so swapped the whole track to
                    // the greyed-out colours for as long as the seek took, which read as a black
                    // bar flickering on every jump even when it was served from the disk cache.
                    enabled = duration > 0,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatDuration(position), style = MaterialTheme.typography.labelSmall)
                    Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconButton(onClick = { onSeekBy(-10_000) }, enabled = item != null) {
                        Icon(Icons.Outlined.Replay10, contentDescription = stringResource(MR.strings.audio_rewind))
                    }
                    IconButton(
                        onClick = onPrevious,
                        enabled = state.hasPrevious,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Outlined.SkipPrevious,
                            contentDescription = stringResource(MR.strings.audio_previous_track),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    FilledIconButton(
                        onClick = onTogglePlay,
                        enabled = item != null,
                        modifier = Modifier.size(68.dp),
                    ) {
                        if (state.isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = if (state.isPlaying) {
                                    stringResource(MR.strings.action_pause)
                                } else {
                                    stringResource(MR.strings.action_play)
                                },
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = onNext,
                        enabled = state.hasNext,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Outlined.SkipNext,
                            contentDescription = stringResource(MR.strings.audio_next_track),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    IconButton(onClick = { onSeekBy(10_000) }, enabled = item != null) {
                        Icon(Icons.Outlined.Forward10, contentDescription = stringResource(MR.strings.audio_forward))
                    }
                }

                AudioVolumeControl(
                    controller = controller,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )

                val sleepMinutes = ((state.sleepTimerRemainingMs + 59_999) / 60_000).toInt()
                val sleeping = state.sleepTimerRemainingMs > 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    // Speed and quality stay textual: neither has a glyph that can express its
                    // value, and they read as a pair in the middle of the row.
                    TextButton(onClick = onCyclePlaybackSpeed) {
                        Text("${formatSpeed(state.playbackSpeed)}x")
                    }
                    TextButton(onClick = onCycleAudioQuality) {
                        Text(
                            stringResource(
                                if (state.audioQuality == AudioQualityMode.FLUENT_FIRST) {
                                    MR.strings.audio_quality_fluent
                                } else {
                                    MR.strings.audio_quality_high
                                },
                            ),
                        )
                    }
                    IconButton(onClick = onToggleLoop) {
                        // Both states carry the "1": the control is only ever about repeating the
                        // current track, and the plain repeat glyph reads as repeating the queue
                        // for everyone who has used another player. Off and on are told apart by
                        // outline vs. fill and by the tint, the same way the sleep timer beside it
                        // does it, so the two neighbours stay one matched pair.
                        Icon(
                            imageVector = if (state.isLooping) {
                                Icons.Filled.RepeatOne
                            } else {
                                Icons.Outlined.RepeatOne
                            },
                            contentDescription = stringResource(
                                if (state.isLooping) {
                                    MR.strings.audio_loop_on
                                } else {
                                    MR.strings.audio_loop_off
                                },
                            ),
                            tint = if (state.isLooping) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                    }
                    IconButton(onClick = { showSleepTimer = true }) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (sleeping) Icons.Filled.Bedtime else Icons.Outlined.Bedtime,
                                contentDescription = if (sleeping) {
                                    stringResource(MR.strings.audio_sleep_minutes, sleepMinutes)
                                } else {
                                    stringResource(MR.strings.audio_sleep_timer)
                                },
                                tint = if (sleeping) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                },
                            )
                            // Countdown rides on the icon as a badge so the row stays one line;
                            // the opaque chip keeps it legible over the filled glyph.
                            if (sleeping) {
                                Text(
                                    text = sleepMinutes.toString(),
                                    fontSize = 9.sp,
                                    lineHeight = 9.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .offset(x = 6.dp, y = 5.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.surface,
                                            shape = RoundedCornerShape(3.dp),
                                        )
                                        .padding(horizontal = 3.dp),
                                )
                            }
                        }
                    }
                    FloatingSubtitleToggle(
                        enabled = floatingSubtitleEnabled,
                        onToggle = onToggleFloatingSubtitle,
                    )
                }
                if (state.error != null) {
                    TextButton(onClick = onTogglePlay) {
                        Text(stringResource(MR.strings.audio_retry))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showSleepTimer) {
        SleepTimerDialog(
            currentRemainingMs = state.sleepTimerRemainingMs,
            onDismiss = { showSleepTimer = false },
            onSelect = {
                onSetSleepTimer(it)
                showSleepTimer = false
            },
        )
    }
}

/**
 * Cover art that also cycles the subtitle display mode, so the art is never dead space and the
 * immersive mode still has an obvious way back to a larger layout.
 */
@Composable
private fun PlayerCover(
    coverUrl: String?,
    contentDescription: String?,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = coverUrl,
        contentDescription = contentDescription,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(if (size <= 48.dp) 6.dp else 8.dp))
            .clickable(
                onClickLabel = stringResource(MR.strings.audio_subtitle_display_mode),
                onClick = onClick,
            ),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun LyricsPanel(
    lyrics: List<LyricLine>,
    subtitleState: AudioSubtitleState,
    displayMode: AudioSubtitleDisplayMode,
    position: Long,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (subtitleState != AudioSubtitleState.READY || lyrics.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            when (subtitleState) {
                AudioSubtitleState.LOADING -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(MR.strings.audio_subtitle_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AudioSubtitleState.ERROR, AudioSubtitleState.EMPTY -> TextButton(onClick = onRetry) {
                    Text(
                        stringResource(
                            if (subtitleState == AudioSubtitleState.EMPTY) {
                                MR.strings.audio_subtitle_empty
                            } else {
                                MR.strings.audio_subtitle_error
                            },
                        ),
                    )
                }
                AudioSubtitleState.NOT_AVAILABLE -> Text(
                    text = stringResource(MR.strings.audio_subtitle_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                AudioSubtitleState.READY -> Unit
            }
        }
        return
    }
    val listState = rememberLazyListState()
    val currentLineIndex = remember(lyrics, position) { lyrics.indexOfLast { it.timeMs <= position } }
    // Karaoke style: park the line that is playing at the vertical centre of the panel, so the
    // eye has a fixed anchor instead of chasing whichever line happens to sit at an edge.
    // The display mode is a key too, because cycling it resizes every line and resizes the panel
    // itself, which otherwise leaves the line wherever the old metrics happened to put it.
    LaunchedEffect(currentLineIndex, displayMode) {
        // Nothing has played yet at the very start, so anchor on the opening line instead.
        val targetIndex = currentLineIndex.coerceAtLeast(0)

        // How far the target line's centre sits from the panel's centre, or null while either
        // the panel or that line is still unmeasured.
        fun offsetFromCentre(): Float? {
            val info = listState.layoutInfo
            if (info.viewportSize.height <= 0) return null
            val line = info.visibleItemsInfo.firstOrNull { it.index == targetIndex } ?: return null
            val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2
            return (line.offset + line.size / 2 - centre).toFloat()
        }

        // A panel that has only just been composed has no measurements at all yet.
        snapshotFlow { listState.layoutInfo }.first { it.viewportSize.height > 0 }

        if (offsetFromCentre() == null) {
            // Off screen, and therefore unmeasured: hop next to it, then glide the rest of the
            // way so a big jump still reads as a movement rather than a cut.
            listState.scrollToItem(targetIndex)
            snapshotFlow { listState.layoutInfo }.first {
                it.visibleItemsInfo.any { visible -> visible.index == targetIndex }
            }
        }
        offsetFromCentre()?.let { listState.animateScrollBy(it) }
    }
    // Denser type in compact mode and larger type in immersive mode, so the mode controls how
    // much transcript fits rather than only how tall the panel is.
    val currentLineStyle = when (displayMode) {
        AudioSubtitleDisplayMode.STANDARD -> MaterialTheme.typography.titleMedium
        AudioSubtitleDisplayMode.IMMERSIVE -> MaterialTheme.typography.titleLarge
    }
    val otherLineStyle = when (displayMode) {
        AudioSubtitleDisplayMode.STANDARD -> MaterialTheme.typography.bodyMedium
        AudioSubtitleDisplayMode.IMMERSIVE -> MaterialTheme.typography.bodyLarge
    }
    val lineSpacing = when (displayMode) {
        AudioSubtitleDisplayMode.STANDARD -> 6.dp
        AudioSubtitleDisplayMode.IMMERSIVE -> 10.dp
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(lyrics, key = { index, _ -> index }) { index, line ->
            Text(
                text = line.text,
                style = if (index == currentLineIndex) currentLineStyle else otherLineStyle,
                color = if (index == currentLineIndex) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth()
                    .clickable { onSeek(line.timeMs) }
                    .padding(vertical = lineSpacing),
            )
        }
    }
}

@Composable
private fun SleepTimerDialog(
    currentRemainingMs: Long,
    onDismiss: () -> Unit,
    onSelect: (Int?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.audio_sleep_timer)) },
        text = {
            Column {
                SLEEP_TIMER_MINUTES.forEach { minutes ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(minutes) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentRemainingMs in ((minutes - 1) * 60_000L)..(minutes * 60_000L),
                            onClick = null,
                        )
                        Text(stringResource(MR.strings.audio_sleep_minutes, minutes))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(null) }) {
                Text(stringResource(MR.strings.audio_sleep_off))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

private fun formatSpeed(value: Float): String {
    return if (value % 1f == 0f) value.toInt().toString() else value.toString()
}

private val IMMERSIVE_COVER_SIZE = 40.dp

private val SLEEP_TIMER_MINUTES = listOf(15, 30, 45, 60)
