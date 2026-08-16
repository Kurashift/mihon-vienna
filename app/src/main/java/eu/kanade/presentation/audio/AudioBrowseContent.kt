package eu.kanade.presentation.audio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.data.audio.AudioQualityMode
import eu.kanade.tachiyomi.data.audio.Work
import eu.kanade.tachiyomi.data.search.SearchHistoryScope
import eu.kanade.tachiyomi.ui.audio.AudioAuthState
import eu.kanade.tachiyomi.ui.audio.AudioBrowseState
import eu.kanade.tachiyomi.ui.audio.AudioBrowseTab
import eu.kanade.tachiyomi.ui.audio.AudioSort
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun AudioBrowseContent(
    state: AudioBrowseState,
    title: String,
    sort: AudioSort,
    auth: AudioAuthState,
    audioQuality: AudioQualityMode,
    showTabs: Boolean,
    bottomBar: @Composable () -> Unit,
    onClickWork: (Work) -> Unit,
    onClickHistory: () -> Unit,
    onClickCategories: () -> Unit,
    onClickPlaylist: () -> Unit,
    navigateUp: () -> Unit,
    onSearch: (String) -> Unit,
    onExitSearch: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSortChange: (AudioSort) -> Unit,
    onSelectTab: (AudioBrowseTab) -> Unit,
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit,
    onCycleAudioQuality: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf<String?>(null) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
    val audioQualityAction = if (showTabs) {
        AppBar.OverflowAction(
            title = stringResource(
                if (audioQuality == AudioQualityMode.FLUENT_FIRST) {
                    MR.strings.audio_quality_fluent
                } else {
                    MR.strings.audio_quality_high
                },
            ),
            onClick = onCycleAudioQuality,
        )
    } else {
        null
    }

    BackHandler(enabled = searchQuery != null) {
        searchQuery = null
        onExitSearch()
    }

    LaunchedEffect(auth.username, showLoginDialog) {
        if (showLoginDialog && auth.username != null) showLoginDialog = false
    }

    Scaffold(
        topBar = { scrollBehavior ->
            Column {
                SearchToolbar(
                    searchQuery = searchQuery,
                    onChangeSearchQuery = { query ->
                        searchQuery = query
                        if (query == null) onExitSearch()
                    },
                    onSearch = onSearch,
                    historyKey = SearchHistoryScope.AUDIO,
                    placeholderText = stringResource(MR.strings.audio_search_hint),
                    titleContent = { AppBarTitle(title) },
                    navigateUp = navigateUp,
                    actions = {
                        IconButton(onClick = onClickHistory) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = stringResource(MR.strings.audio_history),
                            )
                        }
                        IconButton(onClick = { showSortDialog = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Sort,
                                contentDescription = stringResource(MR.strings.audio_sort),
                            )
                        }
                        AppBarActions(
                            listOfNotNull(
                                audioQualityAction,
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.audio_playlist),
                                    onClick = onClickPlaylist,
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.audio_categories),
                                    onClick = onClickCategories,
                                ),
                                AppBar.OverflowAction(
                                    title = if (auth.username != null) {
                                        stringResource(MR.strings.audio_logout)
                                    } else {
                                        stringResource(MR.strings.audio_login)
                                    },
                                    onClick = {
                                        if (auth.username != null) onLogout() else showLoginDialog = true
                                    },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
                if (showTabs) {
                    val availableTabs = AudioBrowseTab.entries
                    PrimaryTabRow(
                        selectedTabIndex = availableTabs.indexOf(state.tab).coerceAtLeast(0),
                    ) {
                        availableTabs.forEach { tab ->
                            Tab(
                                selected = state.tab == tab,
                                onClick = { onSelectTab(tab) },
                                text = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                }
            }
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
                            onClick = onRefresh,
                        ),
                    ),
                )
            }
            state.works.isEmpty() -> EmptyScreen(
                stringRes = MR.strings.audio_empty,
                modifier = Modifier.padding(contentPadding),
            )
            else -> {
                val gridState = rememberLazyGridState()
                LaunchedEffect(gridState, state.works.size) {
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                        .collect { lastIndex ->
                            if (lastIndex != null && lastIndex >= state.works.size - LOAD_MORE_THRESHOLD) {
                                onLoadMore()
                            }
                        }
                }
                PullRefresh(
                    refreshing = state.refreshing,
                    enabled = !state.loadingMore,
                    onRefresh = onRefresh,
                    indicatorPadding = contentPadding,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.works, key = { it.id }) { work ->
                            WorkGridItem(work = work, onClick = { onClickWork(work) })
                        }
                        if (state.loadingMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                )
                            }
                        } else if (state.loadMoreError) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                TextButton(
                                    onClick = onLoadMore,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                ) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(MR.strings.audio_retry))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSortDialog) {
        SortDialog(
            current = sort,
            onDismiss = { showSortDialog = false },
            onSelect = {
                onSortChange(it)
                showSortDialog = false
            },
        )
    }

    if (showLoginDialog) {
        LoginDialog(
            auth = auth,
            onDismiss = { showLoginDialog = false },
            onLogin = onLogin,
        )
    }
}

@Composable
internal fun WorkGridItem(
    work: Work,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            AsyncImage(
                model = work.thumbnailCoverUrl ?: work.samCoverUrl,
                contentDescription = work.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
            ) {
                Text(
                    text = work.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = workGridMeta(work),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun workGridMeta(work: Work): String = buildString {
    append(work.name)
    work.rateAverage2dp?.let { rating ->
        if (isNotEmpty()) append(" · ")
        append("★ ").append(rating)
    }
}

@Composable
private fun SortDialog(
    current: AudioSort,
    onDismiss: () -> Unit,
    onSelect: (AudioSort) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.audio_sort)) },
        text = {
            Column {
                AudioSort.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == current, onClick = null)
                        Text(stringResource(option.label))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
internal fun LoginDialog(
    auth: AudioAuthState,
    onDismiss: () -> Unit,
    onLogin: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.audio_login_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(MR.strings.audio_username)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(MR.strings.audio_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (auth.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(MR.strings.audio_login_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onLogin(name.trim(), password) },
                enabled = !auth.loading && name.isNotBlank() && password.isNotBlank(),
            ) {
                if (auth.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(MR.strings.audio_login))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

private const val LOAD_MORE_THRESHOLD = 8
