package eu.kanade.presentation.audio

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.audio.components.AudioCover
import eu.kanade.presentation.audio.components.AudioFolderRow
import eu.kanade.presentation.audio.components.AudioQueueRow
import eu.kanade.presentation.audio.components.AudioTrackRow
import eu.kanade.presentation.audio.components.buildAudioQueueRows
import eu.kanade.presentation.audio.components.currentWorkKey
import eu.kanade.presentation.audio.components.initialExpandedFolders
import eu.kanade.presentation.util.marqueeTitle
import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import eu.kanade.tachiyomi.data.audio.AudioPlaylistGroup
import eu.kanade.tachiyomi.data.audio.AudioPlaylistStore
import eu.kanade.tachiyomi.ui.audio.AudioPlaybackService
import eu.kanade.tachiyomi.ui.audio.AudioPlayerController
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Quick picker for the playlist: what is playing, which work to browse, and its tracks.
 *
 * Editing deliberately lives in the full playlist screen instead — this sheet only picks a track
 * to play, so selecting a work here must never start playback on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioQuickPlaySheet(
    onDismiss: () -> Unit,
    onOpenWork: (AudioPlayItem) -> Unit,
) {
    val context = LocalContext.current
    val controller = remember { Injekt.get<AudioPlayerController>() }
    val playlistStore = remember { Injekt.get<AudioPlaylistStore>() }
    var groups by remember { mutableStateOf(playlistStore.loadGrouped()) }
    val state = controller.state
    val currentItem = state.item

    // The playlist is edited elsewhere, so whatever was read at the time this composable first
    // ran can already be stale by the time the sheet is opened again.
    LaunchedEffect(Unit) { groups = playlistStore.loadGrouped() }

    // Seeded once from what is playing, then owned by the user: no effect may pull the selection
    // or the expansion back, or a manual choice would be undone by the next playback event.
    var selectedKey by remember {
        mutableStateOf(currentWorkKey(groups, currentItem) ?: groups.firstOrNull()?.key)
    }
    var expandedFolders by remember { mutableStateOf(initialExpandedFolders(currentItem)) }

    val selected = groups.firstOrNull { it.key == selectedKey } ?: groups.firstOrNull()
    val rows = remember(selected, expandedFolders) {
        selected?.let { buildAudioQueueRows(it.tracks, expandedFolders) }.orEmpty()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight(0.88f)
                .fillMaxWidth(),
        ) {
            if (currentItem != null) {
                item(key = "now-playing") {
                    NowPlayingBar(
                        controller = controller,
                        item = currentItem,
                        isPlaying = state.isPlaying,
                        onTogglePlay = controller::togglePlay,
                        onOpenWork = { onOpenWork(currentItem) },
                    )
                }
                item(key = "now-playing-divider") { HorizontalDivider() }
            }

            if (groups.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp, horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(MR.strings.audio_playlist_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (selected != null) {
                item(key = "work-tabs") {
                    AudioWorkTabs(
                        groups = groups,
                        selected = selected,
                        onSelect = { selectedKey = it.key },
                    )
                }
                item(key = "selector-divider") { HorizontalDivider() }

                items(items = rows, key = { it.key }) { row ->
                    when (row) {
                        is AudioQueueRow.Folder -> {
                            val folderExpanded = row.path in expandedFolders
                            AudioFolderRow(
                                title = row.path,
                                expanded = folderExpanded,
                                depth = 0,
                                trackCount = row.trackCount,
                                onClick = {
                                    expandedFolders = if (folderExpanded) {
                                        expandedFolders - row.path
                                    } else {
                                        expandedFolders + row.path
                                    }
                                },
                            )
                        }

                        is AudioQueueRow.Track -> {
                            val isCurrent = currentItem?.mediaStreamUrl == row.item.mediaStreamUrl
                            AudioTrackRow(
                                title = row.item.trackTitle,
                                number = row.number,
                                durationMs = row.item.durationMs,
                                isCurrent = isCurrent,
                                isPlaying = isCurrent && state.isPlaying,
                                depth = 1,
                                onClick = {
                                    playTrack(
                                        context = context,
                                        controller = controller,
                                        tracks = selected.tracks,
                                        index = row.number - 1,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The one playing track, laid out as a player: cover on the left, a transport button, and a
 * progress bar spanning the whole sheet underneath.
 *
 * The bar is what separates it from the work tabs below, which also show covers — those are
 * narrow posters with the title underneath, while this is a full-width row with playback controls.
 * The selected work and the playing work are usually the same one, so their artwork looks alike;
 * making the layout differ is what actually tells them apart.
 */
@Composable
private fun NowPlayingBar(
    controller: AudioPlayerController,
    item: AudioPlayItem,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onOpenWork: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpenWork)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AudioCover(
                coverUrl = item.coverUrl,
                contentDescription = item.workTitle,
                modifier = Modifier.size(80.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                MarqueeTrackTitle(item.trackTitle)
                Text(
                    text = item.workTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // A nested clickable: it consumes the touch, so it does not fall through to the row.
            IconButton(onClick = onTogglePlay) {
                Icon(
                    imageVector = if (isPlaying) {
                        Icons.Outlined.Pause
                    } else {
                        Icons.Outlined.PlayArrow
                    },
                    contentDescription = stringResource(
                        if (isPlaying) MR.strings.action_pause else MR.strings.action_play,
                    ),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(MR.strings.audio_quick_open_work_details),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        PlaybackProgress(controller)
    }
}

/**
 * Split out so the position updates recompose only this, and never the row above.
 *
 * That matters for the marquee: [marqueeTitle] is a composable, so every recomposition of the
 * parent hands the title a fresh Modifier, which restarts the scroll mid-animation. Keeping the
 * per-frame reads down here leaves the title untouched.
 */
@Composable
private fun PlaybackProgress(controller: AudioPlayerController) {
    val state = controller.state
    val duration = state.durationMs

    LinearProgressIndicator(
        progress = {
            if (duration > 0) {
                (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = formatDuration(state.positionMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = formatDuration(duration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Title that scrolls when it overflows, matching the full player.
 *
 * Its own composable for the same reason as [PlaybackProgress]: the modifier has to stay out of
 * the parent's recomposition to keep the scroll running.
 */
@Composable
private fun MarqueeTrackTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.marqueeTitle(),
    )
}

/**
 * A poster per work, one tap to switch which tracks are listed.
 *
 * Deliberately the opposite arrangement to [NowPlayingBar]: cover on top with the title below, in a
 * narrow card. Comparing the two is how a user tells "what is playing" from "what I am browsing",
 * which matters because on open they are usually the same work and so share the same artwork.
 * Playback is started by tapping a track only — never by picking a tab.
 */
@Composable
private fun AudioWorkTabs(
    groups: List<AudioPlaylistGroup>,
    selected: AudioPlaylistGroup,
    onSelect: (AudioPlaylistGroup) -> Unit,
) {
    val listState = rememberLazyListState()

    // The initial selection follows whatever is playing, which can sit far to the right.
    LaunchedEffect(selected.key) {
        val index = groups.indexOfFirst { it.key == selected.key }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = groups, key = { it.key }) { group ->
            val isSelected = group.key == selected.key
            val tint = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(
                modifier = Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(group) }
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AudioCover(
                    coverUrl = group.coverUrl,
                    contentDescription = group.workTitle,
                    modifier = Modifier.size(68.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = group.workTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(MR.strings.audio_quick_track_count, group.tracks.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                )
                Spacer(Modifier.height(4.dp))
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                } else {
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

private fun playTrack(
    context: Context,
    controller: AudioPlayerController,
    tracks: List<AudioPlayItem>,
    index: Int,
) {
    if (index !in tracks.indices) return
    controller.start(tracks, index, 0)
    AudioPlaybackService.start(context)
}
