package eu.kanade.presentation.audio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import eu.kanade.tachiyomi.ui.audio.AudioCategoryType
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
    onClickCategory: (AudioCategoryType) -> Unit,
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
    var showFilters by remember { mutableStateOf(false) }
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
                        IconButton(onClick = { showFilters = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = stringResource(MR.strings.audio_filters),
                            )
                        }
                        IconButton(onClick = onClickHistory) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = stringResource(MR.strings.audio_history),
                            )
                        }
                        AppBarActions(
                            listOfNotNull(
                                audioQualityAction,
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
                        columns = GridCells.Fixed(1),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.works, key = { it.id }) { work ->
                            WorkGridItem(
                                work = work,
                                onClick = { onClickWork(work) },
                            )
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

    if (showFilters) {
        AudioFilterSheet(
            current = sort,
            onDismiss = { showFilters = false },
            onSelect = {
                onSortChange(it)
                showFilters = false
            },
            onSelectCategory = {
                showFilters = false
                onClickCategory(it)
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
        modifier = Modifier.height(168.dp),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(124.dp),
                verticalAlignment = Alignment.Top,
            ) {
                AsyncImage(
                    model = work.mainCoverUrl ?: work.thumbnailCoverUrl ?: work.samCoverUrl,
                    contentDescription = work.title,
                    modifier = Modifier
                        .size(124.dp)
                        .clip(RoundedCornerShape(topStart = 6.dp)),
                    contentScale = ContentScale.Crop,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = work.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                    WorkStats(work)
                }
            }
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(work.tags.size) { index ->
                    WorkTag(work.tags[index].name)
                }
            }
        }
    }
}

@Composable
private fun WorkStats(work: Work) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(3.dp))
        Text(
            text = buildString {
                append(work.rateAverage2dp ?: "-")
                if (work.rateCount > 0) append(" (").append(formatCompactCount(work.rateCount)).append(')')
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = Icons.Outlined.Download,
            contentDescription = stringResource(MR.strings.audio_sales),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(3.dp))
        Text(
            text = formatCompactCount(work.dlCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun WorkTag(name: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = name,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

internal fun formatCompactCount(value: Int): String {
    if (value < 1_000) return value.toString()
    val unit = if (value >= 1_000_000) 1_000_000 else 1_000
    val suffix = if (unit == 1_000_000) "M" else "K"
    val whole = value / unit
    val decimal = value % unit / (unit / 10)
    return if (decimal == 0 || whole >= 100) "$whole$suffix" else "$whole.$decimal$suffix"
}

@Composable
private fun AudioFilterSheet(
    current: AudioSort,
    onDismiss: () -> Unit,
    onSelect: (AudioSort) -> Unit,
    onSelectCategory: (AudioCategoryType) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(MR.strings.audio_filters),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(MR.strings.audio_sort),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            AudioSort.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == current, onClick = null)
                    Text(stringResource(option.label))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                text = stringResource(MR.strings.audio_categories),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            AudioCategoryType.entries.forEach { type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectCategory(type) }
                        .padding(horizontal = 24.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(type.label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
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
