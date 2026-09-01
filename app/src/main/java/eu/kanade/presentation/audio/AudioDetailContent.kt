package eu.kanade.presentation.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistAddCheck
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.audio.components.AudioFolderRow
import eu.kanade.presentation.audio.components.AudioTrackRow
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import eu.kanade.tachiyomi.data.audio.TrackNode
import eu.kanade.tachiyomi.data.audio.Work
import eu.kanade.tachiyomi.ui.audio.AudioDetailState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioDetailContent(
    work: Work,
    state: AudioDetailState,
    playlistUrls: Set<String>,
    isFavorite: Boolean,
    bottomBar: @Composable () -> Unit,
    navigateUp: () -> Unit,
    onClickHome: () -> Unit,
    onRetry: () -> Unit,
    onClickTrack: (Int) -> Unit,
    onTogglePlaylist: (AudioPlayItem) -> Unit,
    onToggleWorkPlaylist: () -> Unit,
    onToggleFolderPlaylist: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onClickCircle: (String) -> Unit,
    onClickVa: (String) -> Unit,
    onClickTag: (String) -> Unit,
) {
    var expanded by remember(state.rootNodes) { mutableStateOf(emptySet<String>()) }
    val audioIndexByUrl = remember(state.flatTracks) {
        state.flatTracks.mapIndexed { index, item -> item.mediaStreamUrl to index }.toMap()
    }
    val visibleRows = remember(state.rootNodes, expanded) {
        buildVisibleRows(state.rootNodes, expanded)
    }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                // No title: the header below already carries the work title, and a static
                // section name here repeated what the back stack makes obvious.
                title = null,
                navigateUp = navigateUp,
                navigationActions = {
                    // Landing on the details from the reader or a notification leaves no ASMR
                    // home underneath, so offer an explicit way into it. Sits next to the up
                    // button because it is a navigation target, not a command on this work.
                    IconButton(onClick = onClickHome) {
                        Icon(
                            Icons.Outlined.Home,
                            contentDescription = stringResource(MR.strings.audio_title),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                actions = {
                    if (state.flatTracks.isNotEmpty()) {
                        val queuedCount = state.flatTracks.count { it.mediaStreamUrl in playlistUrls }
                        val allQueued = queuedCount == state.flatTracks.size
                        IconButton(onClick = onToggleWorkPlaylist) {
                            Icon(
                                imageVector = if (allQueued) {
                                    Icons.AutoMirrored.Outlined.PlaylistAddCheck
                                } else {
                                    Icons.AutoMirrored.Outlined.PlaylistAdd
                                },
                                contentDescription = stringResource(
                                    if (allQueued) {
                                        MR.strings.audio_playlist_remove_work
                                    } else {
                                        MR.strings.audio_add_work_to_playlist
                                    },
                                ),
                                tint = if (queuedCount > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
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
            )
        },
        bottomBar = bottomBar,
    ) { contentPadding ->
        when {
            state.loading -> LoadingScreen(Modifier.padding(contentPadding))
            state.error -> {
                val message = state.errorMessage?.let { "${stringResource(MR.strings.audio_load_failed)}: $it" }
                    ?: stringResource(MR.strings.audio_load_failed)
                EmptyScreen(
                    message = message,
                    modifier = Modifier.padding(contentPadding),
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.audio_retry,
                            icon = Icons.Outlined.Refresh,
                            onClick = onRetry,
                        ),
                    ),
                )
            }
            state.rootNodes.isEmpty() -> EmptyScreen(
                stringRes = MR.strings.audio_no_tracks,
                modifier = Modifier.padding(contentPadding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                item {
                    WorkHeader(
                        work = work,
                        onClickCircle = onClickCircle,
                        onClickVa = onClickVa,
                        onClickTag = onClickTag,
                    )
                    HorizontalDivider()
                }
                items(visibleRows, key = { it.key }) { row ->
                    if (row.isFolder) {
                        val folderTracks = state.flatTracks.filter { it.folderPath == row.key }
                        val allQueued = folderTracks.isNotEmpty() &&
                            folderTracks.all { it.mediaStreamUrl in playlistUrls }
                        AudioFolderRow(
                            title = row.title,
                            expanded = row.key in expanded,
                            depth = row.depth,
                            onClick = {
                                expanded = if (row.key in expanded) expanded - row.key else expanded + row.key
                            },
                            actions = {
                                PlaylistToggleIcon(
                                    added = allQueued,
                                    onClick = { onToggleFolderPlaylist(row.key) },
                                )
                            },
                        )
                    } else {
                        val index = audioIndexByUrl[row.audioUrl] ?: -1
                        AudioTrackRow(
                            title = row.title,
                            durationMs = row.durationMs,
                            depth = row.depth,
                            onClick = { if (index >= 0) onClickTrack(index) },
                            actions = {
                                PlaylistToggleIcon(
                                    added = row.audioUrl in playlistUrls,
                                    onClick = {
                                        state.flatTracks.getOrNull(index)?.let(onTogglePlaylist)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private data class TreeRow(
    val key: String,
    val depth: Int,
    val isFolder: Boolean,
    val title: String,
    val durationMs: Long = 0,
    val audioUrl: String? = null,
)

private fun buildVisibleRows(nodes: List<TrackNode>, expanded: Set<String>): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()

    fun walk(list: List<TrackNode>, depth: Int, parentKey: String) {
        list.forEach { node ->
            val path = if (parentKey.isEmpty()) node.title else "$parentKey/${node.title}"
            when (node.type) {
                "folder" -> {
                    if (node.hasPlayableAudio()) {
                        rows += TreeRow(key = path, depth = depth, isFolder = true, title = node.title)
                        if (path in expanded) walk(node.children, depth + 1, path)
                    }
                }
                "audio" -> {
                    val url = node.mediaStreamUrl
                    if (!url.isNullOrBlank()) {
                        rows += TreeRow(
                            key = "audio:$url",
                            depth = depth,
                            isFolder = false,
                            title = node.title,
                            durationMs = ((node.duration ?: 0.0) * 1000).toLong(),
                            audioUrl = url,
                        )
                    }
                }
            }
        }
    }
    walk(nodes, 0, "")
    return rows
}

private fun TrackNode.hasPlayableAudio(): Boolean {
    return (type == "audio" && !mediaStreamUrl.isNullOrBlank()) || children.any { it.hasPlayableAudio() }
}

@Composable
private fun WorkHeader(
    work: Work,
    onClickCircle: (String) -> Unit,
    onClickVa: (String) -> Unit,
    onClickTag: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = work.mainCoverUrl ?: work.thumbnailCoverUrl ?: work.samCoverUrl,
                contentDescription = work.title,
                modifier = Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            ) {
                Text(
                    text = work.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = workMeta(work),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (work.name.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = work.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onClickCircle(work.name) },
            )
        }

        if (work.vas.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(MR.strings.audio_vas),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                work.vas.forEach { va ->
                    TagChip(va.name, onClick = { onClickVa(va.name) })
                }
            }
        }

        if (work.tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                work.tags.take(12).forEach { tag ->
                    TagChip(tag.name, onClick = { onClickTag(tag.name) })
                }
            }
        }
    }
}

@Composable
private fun TagChip(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Shared by folder and track rows: adds the whole folder, or the single track, to the playlist. */
@Composable
private fun PlaylistToggleIcon(
    added: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (added) {
                Icons.AutoMirrored.Outlined.PlaylistAddCheck
            } else {
                Icons.AutoMirrored.Outlined.PlaylistAdd
            },
            contentDescription = stringResource(
                if (added) {
                    MR.strings.audio_playlist_remove
                } else {
                    MR.strings.audio_add_to_playlist
                },
            ),
            tint = if (added) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
    }
}
