package eu.kanade.presentation.audio

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import eu.kanade.tachiyomi.data.audio.AudioPlaylistStore
import eu.kanade.tachiyomi.ui.audio.AudioPlaybackService
import eu.kanade.tachiyomi.ui.audio.AudioPlayerController
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioQuickPlaySheet(
    onDismiss: () -> Unit,
    onOpenWork: ((AudioPlayItem) -> Unit)? = null,
) {
    val context = LocalContext.current
    val controller = remember { Injekt.get<AudioPlayerController>() }
    val playlistStore = remember { Injekt.get<AudioPlaylistStore>() }
    val playlist = remember { playlistStore.load() }
    val state = controller.state
    val works = remember(playlist) {
        playlist.groupBy { it.workId to it.workTitle }.toList()
    }
    var selectedWork by remember { mutableStateOf<Pair<Long, String>?>(null) }

    LaunchedEffect(works, state.item?.workId, state.item?.workTitle) {
        val playingWork = state.item?.let { it.workId to it.workTitle }
        selectedWork = when {
            playingWork != null && works.any { it.first == playingWork } -> playingWork
            selectedWork != null && works.any { it.first == selectedWork } -> selectedWork
            else -> works.firstOrNull()?.first
        }
    }

    val selectedTracks = remember(works, selectedWork) {
        works.firstOrNull { it.first == selectedWork }?.second.orEmpty()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.88f)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(MR.strings.audio_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            MR.strings.audio_quick_queue_summary,
                            works.size,
                            playlist.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (playlist.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            playAudio(playlist, playlist.indices.random(), controller, context)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(MR.strings.audio_quick_random))
                    }
                }
            }

            CurrentAudioPanel(
                controller = controller,
                onOpenWork = onOpenWork,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            HorizontalDivider()

            if (playlist.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(MR.strings.audio_playlist_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            Text(
                text = stringResource(MR.strings.audio_quick_switch_work),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            )
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = works,
                    key = { it.first },
                ) { (workKey, tracks) ->
                    AudioWorkItem(
                        item = tracks.first(),
                        trackCount = tracks.size,
                        selected = workKey == selectedWork,
                        onOpenWork = onOpenWork,
                        onClick = {
                            selectedWork = workKey
                            val index = playlist.indexOfFirst { it.mediaStreamUrl == tracks.first().mediaStreamUrl }
                            playAudio(playlist, index, controller, context)
                        },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedTracks.firstOrNull()?.workTitle.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(MR.strings.audio_quick_track_count, selectedTracks.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                itemsIndexed(
                    items = selectedTracks,
                    key = { _, item -> item.mediaStreamUrl },
                ) { index, item ->
                    val globalIndex = playlist.indexOfFirst { it.mediaStreamUrl == item.mediaStreamUrl }
                    val isCurrent = state.item?.mediaStreamUrl == item.mediaStreamUrl
                    AudioTrackItem(
                        number = index + 1,
                        item = item,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && state.isPlaying,
                        onClick = { playAudio(playlist, globalIndex, controller, context) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentAudioPanel(
    controller: AudioPlayerController,
    onOpenWork: ((AudioPlayItem) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state = controller.state
    val item = state.item

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AudioCover(
                coverUrl = item?.coverUrl,
                contentDescription = item?.workTitle,
                onClick = item?.takeIf { it.workId > 0 }?.let { current ->
                    onOpenWork?.let { open -> { open(current) } }
                },
                modifier = Modifier.size(56.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = item?.trackTitle ?: stringResource(MR.strings.audio_quick_choose_work),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item?.workTitle ?: stringResource(MR.strings.audio_playlist),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = controller::previous,
                enabled = state.hasPrevious,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SkipPrevious,
                    contentDescription = stringResource(MR.strings.audio_previous_track),
                )
            }
            FilledIconButton(
                onClick = controller::togglePlay,
                enabled = item != null,
            ) {
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = null,
                    )
                }
            }
            IconButton(
                onClick = controller::next,
                enabled = state.hasNext,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SkipNext,
                    contentDescription = stringResource(MR.strings.audio_next_track),
                )
            }
        }

        AudioSeekBar(
            controller = controller,
            modifier = Modifier.padding(top = 2.dp),
        )

        AudioVolumeControl(
            controller = controller,
            compact = true,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item != null && state.totalCount > 0) {
                Text(
                    text = stringResource(MR.strings.audio_player_position, state.index + 1, state.totalCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            IconButton(onClick = controller::random, enabled = state.totalCount > 0) {
                Icon(
                    imageVector = Icons.Outlined.Shuffle,
                    contentDescription = stringResource(MR.strings.audio_quick_random),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = controller::toggleLoop, enabled = item != null) {
                Icon(
                    imageVector = Icons.Outlined.Repeat,
                    contentDescription = stringResource(MR.strings.audio_repeat_one),
                    tint = if (state.isLooping) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun AudioWorkItem(
    item: AudioPlayItem,
    trackCount: Int,
    selected: Boolean,
    onOpenWork: ((AudioPlayItem) -> Unit)?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AudioCover(
                coverUrl = item.coverUrl,
                contentDescription = item.workTitle,
                onClick = item.takeIf { it.workId > 0 }?.let { work ->
                    onOpenWork?.let { open -> { open(work) } }
                },
                modifier = Modifier.size(48.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Text(
                    text = item.workTitle,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(MR.strings.audio_quick_track_count, trackCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AudioTrackItem(
    number: Int,
    item: AudioPlayItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isCurrent) {
                Icon(
                    imageVector = if (isPlaying) Icons.Outlined.GraphicEq else Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = item.trackTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
    }
}

@Composable
private fun AudioCover(
    coverUrl: String?,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interactionModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .then(interactionModifier),
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

private fun playAudio(
    playlist: List<AudioPlayItem>,
    index: Int,
    controller: AudioPlayerController,
    context: Context,
) {
    if (index !in playlist.indices) return
    controller.start(playlist, index, 0)
    AudioPlaybackService.start(context)
}
