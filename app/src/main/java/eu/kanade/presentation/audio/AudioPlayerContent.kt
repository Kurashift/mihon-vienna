package eu.kanade.presentation.audio

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.data.audio.LyricLine
import eu.kanade.tachiyomi.data.audio.AudioQualityMode
import eu.kanade.tachiyomi.ui.audio.AudioPlayerState
import eu.kanade.tachiyomi.ui.audio.AudioPlayerController
import eu.kanade.tachiyomi.ui.audio.AudioSubtitleState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun AudioPlayerContent(
    controller: AudioPlayerController,
    state: AudioPlayerState,
    lyrics: List<LyricLine>,
    subtitleState: AudioSubtitleState,
    isFavorite: Boolean,
    navigateUp: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRetrySubtitle: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onRandom: () -> Unit,
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
                title = item?.workTitle ?: stringResource(MR.strings.audio_title),
                navigateUp = navigateUp,
                actions = {
                    if (item != null) {
                        IconButton(onClick = onToggleFavorite) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
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
            val coverSize = minOf(maxWidth - 56.dp, if (maxHeight < 700.dp) 196.dp else 292.dp)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(10.dp))
                AsyncImage(
                    model = item?.coverUrl,
                    contentDescription = item?.workTitle,
                    modifier = Modifier
                        .size(coverSize)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
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
                    enabled = !state.isBuffering && duration > 0,
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
                    IconButton(onClick = onPrevious, enabled = item != null) {
                        Icon(Icons.Outlined.SkipPrevious, contentDescription = null, modifier = Modifier.size(32.dp))
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
                    IconButton(onClick = onNext, enabled = state.hasNext) {
                        Icon(Icons.Outlined.SkipNext, contentDescription = null, modifier = Modifier.size(32.dp))
                    }
                    IconButton(onClick = { onSeekBy(10_000) }, enabled = item != null) {
                        Icon(Icons.Outlined.Forward10, contentDescription = stringResource(MR.strings.audio_forward))
                    }
                }

                AudioVolumeControl(
                    controller = controller,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconButton(onClick = onRandom, enabled = state.totalCount > 1) {
                        Icon(Icons.Outlined.Shuffle, contentDescription = stringResource(MR.strings.audio_quick_random))
                    }
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
                    IconButton(onClick = { showSleepTimer = true }) {
                        Icon(
                            Icons.Outlined.Bedtime,
                            contentDescription = stringResource(MR.strings.audio_sleep_timer),
                            tint = if (state.sleepTimerRemainingMs > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = onToggleLoop) {
                        Icon(
                            Icons.Outlined.Repeat,
                            contentDescription = stringResource(MR.strings.audio_repeat_one),
                            tint = if (state.isLooping) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
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

@Composable
private fun LyricsPanel(
    lyrics: List<LyricLine>,
    subtitleState: AudioSubtitleState,
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
    LaunchedEffect(currentLineIndex) {
        if (currentLineIndex >= 0) listState.animateScrollToItem(currentLineIndex)
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
                style = if (index == currentLineIndex) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                },
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
                    .padding(vertical = 6.dp),
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

private val SLEEP_TIMER_MINUTES = listOf(15, 30, 45, 60)
