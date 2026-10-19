package eu.kanade.presentation.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
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
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import eu.kanade.tachiyomi.data.audio.AudioRepeatMode
import eu.kanade.tachiyomi.data.audio.AudioSubtitleDisplayMode
import eu.kanade.tachiyomi.data.audio.AudioSubtitleState
import eu.kanade.tachiyomi.data.audio.LyricLine
import eu.kanade.tachiyomi.ui.audio.AudioPlayerController
import eu.kanade.tachiyomi.ui.audio.AudioPlayerState
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.delay
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
    isFullscreen: Boolean,
    isScreenVisible: Boolean,
    onToggleFullscreen: () -> Unit,
    floatingSubtitleEnabled: Boolean,
    navigateUp: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRetrySubtitle: () -> Unit,
    onCycleSubtitleDisplayMode: () -> Unit,
    onToggleFloatingSubtitle: () -> Unit,
    onOpenWorkDetail: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenCircle: (String) -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onCyclePlaybackSpeed: () -> Unit,
    onCycleAudioQuality: () -> Unit,
    onSetSleepTimer: (Int?) -> Unit,
) {
    var dragPosition by remember { mutableStateOf<Long?>(null) }
    var showSleepTimer by remember { mutableStateOf(false) }
    val item = state.item
    val context = LocalContext.current
    val duration = state.durationMs.coerceAtLeast(0)
    val position = (dragPosition ?: state.positionMs).coerceIn(0, duration)

    var controlsVisible by remember { mutableStateOf(true) }

    // The strip is chrome, not content: it hides itself while the picture plays so the screen is
    // only the work. A tap brings it back.
    LaunchedEffect(isFullscreen, controlsVisible, state.isPlaying) {
        if (isFullscreen && controlsVisible && state.isPlaying) {
            delay(CONTROLS_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }

    // Long press copies the shown text and nothing else — no click, so no dead-tap ripple on a
    // line that is a label. detectTapGestures rather than combinedClickable for exactly that.
    fun Modifier.copyOnLongPress(text: String?): Modifier {
        if (text.isNullOrBlank()) return this
        return pointerInput(text) {
            detectTapGestures(onLongPress = { context.copyToClipboard(text, text) })
        }
    }

    // A real circle name also opens its works list — the same page the details page's circle name
    // lands on. The work-title fallback shown when the circle is unknown only copies.
    fun Modifier.circleTextModifier(shown: String, circleName: String?): Modifier {
        if (circleName == null) return copyOnLongPress(shown)
        return combinedClickable(
            onClick = { onOpenCircle(circleName) },
            onLongClick = { context.copyToClipboard(shown, shown) },
        )
    }

    // Same label, but inside the immersive strip the row itself cycles the display mode, so a line
    // with no action of its own must pass the tap on. A bare long-press detector consumes it and
    // would leave that stretch of the row dead, which is what the whole strip is here to avoid.
    fun Modifier.copyOnLongPressOrCycle(text: String?): Modifier {
        if (text.isNullOrBlank()) return this
        return combinedClickable(
            onClick = onCycleSubtitleDisplayMode,
            onLongClick = { context.copyToClipboard(text, text) },
        )
    }

    // The circle line keeps opening its works list when the name is real; the work-title fallback
    // is only a label, so it cycles with the rest of the strip.
    fun Modifier.circleTextOrCycleModifier(shown: String, circleName: String?): Modifier {
        if (circleName == null) return copyOnLongPressOrCycle(shown)
        return circleTextModifier(shown, circleName)
    }

    Scaffold(
        // Black behind the picture: the window still paints around the stage, and a light
        // background there is what showed as a white strip in the camera cutout.
        containerColor = if (isFullscreen) Color.Black else MaterialTheme.colorScheme.background,
        // Fullscreen draws under the cutout too, so the picture is not pushed away from it.
        contentWindowInsets = if (isFullscreen) {
            WindowInsets(0, 0, 0, 0)
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
        topBar = { scrollBehavior ->
            if (!isFullscreen) {
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
                                        if (isFavorite) {
                                            MR.strings.audio_favorite_remove
                                        } else {
                                            MR.strings.audio_favorite_add
                                        },
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
            }
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
            val immersive = !isFullscreen && displayMode == AudioSubtitleDisplayMode.IMMERSIVE
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (isFullscreen) 0.dp else 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Present in both layouts, empty in fullscreen: the stage below has to keep the
                // same slot. Handing it to a different parent destroys its surface, and that is
                // the black flash that used to sit between the page and fullscreen.
                Spacer(
                    Modifier.height(
                        if (isFullscreen) {
                            0.dp
                        } else if (immersive) {
                            6.dp
                        } else {
                            10.dp
                        },
                    ),
                )
                if (immersive) {
                    // The whole strip cycles the display mode, not just the 40dp thumbnail: the art
                    // used to be the only live pixel in a full-width row, and every tap to its
                    // right landed on nothing. The lines that own an action keep it, and the rest
                    // of the row is the way back out of immersive mode.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                onClickLabel = stringResource(MR.strings.audio_subtitle_display_mode),
                                onClick = onCycleSubtitleDisplayMode,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayerCover(
                            coverUrl = item?.coverUrl,
                            contentDescription = item?.workTitle,
                            size = coverSize,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item?.trackTitle.orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .copyOnLongPressOrCycle(item?.trackTitle)
                                    .marqueeTitle(),
                            )
                            item?.let { current ->
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = current.circleName.ifBlank { current.workTitle },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.circleTextOrCycleModifier(
                                        shown = current.circleName.ifBlank { current.workTitle },
                                        circleName = current.circleName.takeIf { it.isNotBlank() },
                                    ),
                                )
                            }
                        }
                    }
                } else {
                    // A track with a picture takes the cover's place, at the page's full width in
                    // the page and edge to edge in fullscreen. Same Box either way, so the stage
                    // keeps its place in the tree and its surface.
                    Box(
                        modifier = if (isFullscreen) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier.fillMaxWidth()
                        },
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        if (state.hasVideo && !state.videoUnsupported) {
                            AudioVideoStage(
                                controller = controller,
                                item = item,
                                hasVideo = true,
                                videoAspectRatio = state.videoAspectRatio,
                                videoReady = state.videoReady,
                                videoUnsupported = state.videoUnsupported,
                                isBuffering = state.isBuffering,
                                isScreenVisible = isScreenVisible,
                                isFullscreen = isFullscreen,
                                // Same gesture as the cover on a sound track: cycle the subtitle
                                // layout. Fullscreen gets its own button so both stay reachable.
                                onTap = {
                                    if (isFullscreen) {
                                        controlsVisible = !controlsVisible
                                    } else {
                                        onCycleSubtitleDisplayMode()
                                    }
                                },
                                onDoubleTap = onTogglePlay,
                                modifier = if (isFullscreen) {
                                    Modifier.fillMaxSize()
                                } else {
                                    Modifier.fillMaxWidth()
                                },
                            )
                        } else {
                            PlayerCover(
                                coverUrl = item?.coverUrl,
                                contentDescription = item?.workTitle,
                                size = coverSize,
                                onClick = onCycleSubtitleDisplayMode,
                            )
                        }

                        if (isFullscreen) {
                            FullscreenControls(
                                state = state,
                                controlsVisible = controlsVisible,
                                onExit = onToggleFullscreen,
                                onTogglePlay = onTogglePlay,
                                onSeek = onSeek,
                                onNext = onNext,
                                onPrevious = onPrevious,
                            )
                        }
                    }
                    if (!isFullscreen) {
                        if (state.videoUnsupported) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(MR.strings.audio_video_unsupported),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = item?.trackTitle.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .copyOnLongPress(item?.trackTitle),
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .circleTextModifier(
                                        shown = item.circleName.ifBlank { item.workTitle },
                                        circleName = item.circleName.takeIf { it.isNotBlank() },
                                    ),
                            )
                        }
                    }
                }
                if (!isFullscreen) {
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

                    AudioProgressBar(
                        positionMs = position,
                        durationMs = duration,
                        bufferedMs = state.bufferedPositionMs,
                        // Buffering no longer disables the slider: doing so swapped the whole track to
                        // the greyed-out colours for as long as the seek took, which read as a black
                        // bar flickering on every jump even when it was served from the disk cache.
                        enabled = duration > 0,
                        // The ring is painted in the page's own background so the thumb reads as sitting
                        // on the track rather than being part of it.
                        ringColor = MaterialTheme.colorScheme.background,
                        onSeek = { dragPosition = it.coerceIn(0, duration) },
                        onSeekFinished = {
                            dragPosition?.let(onSeek)
                            dragPosition = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Tabular figures, like the floating bar: these tick every half second, and
                        // proportional digits make the label twitch as 1s swaps in for 8s.
                        Text(
                            text = formatDuration(position),
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR_FIGURES),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatDuration(duration),
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR_FIGURES),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                                    imageVector = if (state.isPlaying) {
                                        Icons.Outlined.Pause
                                    } else {
                                        Icons.Outlined.PlayArrow
                                    },
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
                            Icon(
                                Icons.Outlined.Forward10,
                                contentDescription = stringResource(MR.strings.audio_forward),
                            )
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
                        IconButton(onClick = onCycleRepeatMode) {
                            // A shape of its own per mode, so the state is legible at a glance rather
                            // than by reading the "1" on a ring the other mode also draws. Off is the
                            // outline of the ring the single-track mode fills, which keeps those two
                            // one matched pair the way the sleep timer beside it works.
                            Icon(
                                imageVector = when (state.repeatMode) {
                                    AudioRepeatMode.OFF -> Icons.Outlined.Repeat
                                    AudioRepeatMode.ONE -> Icons.Filled.RepeatOne
                                    // Three bars for the whole queue, against the rings the other two
                                    // draw: no second ring to tell apart by the "1" on it, and no
                                    // play glyph hanging off it, which read as a transport control
                                    // rather than as a mode. Filled while the queue repeats, so the
                                    // shape alone says which mode is on.
                                    AudioRepeatMode.ALL -> Icons.Filled.Menu
                                },
                                contentDescription = stringResource(
                                    when (state.repeatMode) {
                                        AudioRepeatMode.OFF -> MR.strings.audio_repeat_off
                                        AudioRepeatMode.ONE -> MR.strings.audio_repeat_one
                                        AudioRepeatMode.ALL -> MR.strings.audio_repeat_all
                                    },
                                ),
                                tint = if (state.repeatMode == AudioRepeatMode.OFF) {
                                    LocalContentColor.current
                                } else {
                                    MaterialTheme.colorScheme.primary
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
                        // The queue lives beside the transport controls rather than in the app bar: it
                        // is something you reach for while listening, the same way as repeat and the
                        // sleep timer, and the bar keeps one slot free for the favourite.
                        IconButton(onClick = onOpenQueue, enabled = item != null) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.QueueMusic,
                                contentDescription = stringResource(MR.strings.audio_playlist),
                                tint = LocalContentColor.current,
                            )
                        }
                        // Right end of the strip: the way into fullscreen belongs with the
                        // transport controls, not on top of the picture.
                        if (state.hasVideo && !state.videoUnsupported) {
                            IconButton(onClick = onToggleFullscreen, enabled = item != null) {
                                Icon(
                                    imageVector = Icons.Outlined.Fullscreen,
                                    contentDescription = stringResource(MR.strings.audio_video_enter_fullscreen),
                                    tint = LocalContentColor.current,
                                )
                            }
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
 * The control strip of the fullscreen picture, drawn over it.
 *
 * Back and the exit button both leave fullscreen rather than the page: the screen keeps a
 * BackHandler for that, and this composable only draws the second way out.
 */
@Composable
private fun BoxScope.FullscreenControls(
    state: AudioPlayerState,
    controlsVisible: Boolean,
    onExit: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    var dragPosition by remember { mutableStateOf<Long?>(null) }
    val duration = state.durationMs.coerceAtLeast(0)
    val position = (dragPosition ?: state.positionMs).coerceIn(0, duration)

    if (controlsVisible) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            AudioProgressBar(
                positionMs = position,
                durationMs = duration,
                bufferedMs = state.bufferedPositionMs,
                enabled = duration > 0,
                ringColor = Color.Black,
                onSeek = { dragPosition = it.coerceIn(0, duration) },
                onSeekFinished = {
                    dragPosition?.let(onSeek)
                    dragPosition = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatDuration(position),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR_FIGURES),
                    color = Color.White,
                )
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR_FIGURES),
                    color = Color.White,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = onPrevious, enabled = state.hasPrevious) {
                    Icon(
                        Icons.Outlined.SkipPrevious,
                        contentDescription = stringResource(MR.strings.audio_previous_track),
                        tint = Color.White,
                    )
                }
                FilledIconButton(onClick = onTogglePlay, modifier = Modifier.size(56.dp)) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying) {
                                Icons.Outlined.Pause
                            } else {
                                Icons.Outlined.PlayArrow
                            },
                            contentDescription = if (state.isPlaying) {
                                stringResource(MR.strings.action_pause)
                            } else {
                                stringResource(MR.strings.action_play)
                            },
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                IconButton(onClick = onNext, enabled = state.hasNext) {
                    Icon(
                        Icons.Outlined.SkipNext,
                        contentDescription = stringResource(MR.strings.audio_next_track),
                        tint = Color.White,
                    )
                }
            }
        }

        // Part of the strip, not pinned on top of the picture: the way out is one tap away
        // (the picture itself brings the strip back), and a permanent button over the video
        // reads as clutter rather than as a control.
        IconButton(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        ) {
            Icon(
                Icons.Outlined.FullscreenExit,
                contentDescription = stringResource(MR.strings.audio_video_exit_fullscreen),
                tint = Color.White,
            )
        }
    }
}

/**
 * Cover art that also cycles the subtitle display mode, so the art is never dead space and the
 * immersive mode still has an obvious way back to a larger layout. In the immersive strip the row
 * owns that click instead, and the art is left without one so the strip is a single target.
 */
@Composable
private fun PlayerCover(
    coverUrl: String?,
    contentDescription: String?,
    size: Dp,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val clickLabel = stringResource(MR.strings.audio_subtitle_display_mode)
    val clickModifier = onClick?.let { action ->
        Modifier.clickable(onClickLabel = clickLabel, onClick = action)
    } ?: Modifier
    AsyncImage(
        model = coverUrl,
        contentDescription = contentDescription,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(if (size <= 48.dp) 6.dp else 8.dp))
            .then(clickModifier),
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

/** How long the fullscreen strip stays up after a tap or a pause. */
private const val CONTROLS_HIDE_DELAY_MS = 3_000L

private val SLEEP_TIMER_MINUTES = listOf(15, 30, 45, 60)
