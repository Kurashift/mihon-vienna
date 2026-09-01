package eu.kanade.presentation.audio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
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
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.data.audio.AudioQualityMode
import eu.kanade.tachiyomi.data.audio.TagRef
import eu.kanade.tachiyomi.data.audio.Work
import eu.kanade.tachiyomi.data.search.SearchHistoryScope
import eu.kanade.tachiyomi.ui.audio.AudioAuthState
import eu.kanade.tachiyomi.ui.audio.AudioBrowseState
import eu.kanade.tachiyomi.ui.audio.AudioBrowseTab
import eu.kanade.tachiyomi.ui.audio.AudioSort
import kotlinx.coroutines.flow.distinctUntilChanged
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
    var showSortSheet by remember { mutableStateOf(false) }
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
                    // Without a tab row the list is filtered to one dictionary entry, and a
                    // keyword typed here narrows it further rather than replacing it — so the
                    // field names the entry it is searching inside, instead of the whole library.
                    placeholderText = if (showTabs) {
                        stringResource(MR.strings.audio_search_hint)
                    } else {
                        stringResource(MR.strings.audio_search_hint_in_category, title)
                    },
                    titleContent = { AppBarTitle(title) },
                    navigateUp = navigateUp,
                    actions = {
                        IconButton(onClick = onClickHistory) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = stringResource(MR.strings.audio_history),
                            )
                        }
                        AppBarActions(
                            listOfNotNull(
                                // With tabs on screen, sorting lives on the tabs themselves and
                                // this entry is redundant. Screens without a tab row (a circle,
                                // VA or tag result) would otherwise have no way to sort at all.
                                if (!showTabs) {
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.audio_sort),
                                        onClick = { showSortSheet = true },
                                    )
                                } else {
                                    null
                                },
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.audio_categories),
                                    onClick = onClickCategories,
                                ),
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
                        availableTabs.forEachIndexed { index, tab ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // The work-list tab is the only one a sort applies to, so it
                                // carries the active sort as its name instead of a fixed one.
                                val label = if (tab == AudioBrowseTab.LATEST) sort.tabLabel else tab.label
                                Tab(
                                    selected = state.tab == tab,
                                    onClick = {
                                        // Re-tapping the active tab is the shortcut to the sort
                                        // sheet, which is why the toolbar has no filter icon.
                                        if (state.tab == tab && tab.sortable) {
                                            showSortSheet = true
                                        } else {
                                            onSelectTab(tab)
                                        }
                                    },
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(stringResource(label))
                                            // Re-tapping to sort is invisible otherwise. The caret
                                            // also doubles as a hint for which tabs have a sort at
                                            // all: the backend feeds do not.
                                            if (tab.sortable) {
                                                Icon(
                                                    imageVector = Icons.Outlined.ArrowDropDown,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                if (index < availableTabs.lastIndex) {
                                    AudioTabDivider()
                                }
                            }
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
                // Read through rememberUpdatedState so appending a page no longer tears down and
                // restarts the scroll observer — the flow only needs the latest count, not to be
                // resubscribed whenever it changes.
                val worksCount by rememberUpdatedState(state.works.size)
                LaunchedEffect(gridState) {
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                        .distinctUntilChanged()
                        .collect { lastIndex ->
                            if (lastIndex != null && lastIndex >= worksCount - LOAD_MORE_THRESHOLD) {
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
                        // Cards touch the hairline below rather than float in a wide gap: the
                        // rule is what separates two works, so the gap only has to keep them
                        // off it. spacing is zero and the rule carries its own padding, so the
                        // two read as one rhythm instead of gap-then-gap.
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        itemsIndexed(state.works, key = { _, work -> work.id }) { index, work ->
                            Column {
                                if (index > 0) WorkSeparator()
                                WorkGridItem(
                                    work = work,
                                    onClick = { onClickWork(work) },
                                )
                            }
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

    if (showSortSheet) {
        AudioSortSheet(
            current = sort,
            onDismiss = { showSortSheet = false },
            onSelect = {
                onSortChange(it)
                showSortSheet = false
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
        // Fixed total height, so no card ever sits taller or shorter than its neighbours.
        // The tag row is a strip along the bottom edge — full card width, under the cover as
        // well as the text — which is both where it reads as its own band and where it has the
        // most room to show tags. A work with no tags drops the strip and the cover grows into
        // the space instead, so the card keeps its height without an empty strip under it.
        modifier = Modifier
            .fillMaxWidth()
            .height(WORK_CARD_HEIGHT),
        shape = RoundedCornerShape(WORK_CARD_CORNER),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Takes whatever the tag strip does not, which is the whole card when there
                    // are no tags. The cover follows this row's height rather than setting it.
                    .weight(1f),
                verticalAlignment = Alignment.Top,
            ) {
                AsyncImage(
                    model = work.mainCoverUrl ?: work.thumbnailCoverUrl ?: work.samCoverUrl,
                    contentDescription = work.title,
                    modifier = Modifier
                        .fillMaxHeight()
                        // Square off the row's height, so dropping the tag strip shows a larger
                        // cover rather than a gap beside one that stayed put.
                        .aspectRatio(1f, matchHeightConstraintsFirst = true)
                        // Bottom corner only when the cover reaches the card's own bottom edge.
                        // With a tag strip below it, its bottom edge lands mid-card, where a
                        // rounded corner would punch a notch out of the card's fill.
                        .clip(
                            RoundedCornerShape(
                                topStart = WORK_CARD_CORNER,
                                bottomStart = if (work.tags.isNotEmpty()) 0.dp else WORK_CARD_CORNER,
                            ),
                        ),
                    contentScale = ContentScale.Crop,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 10.dp, vertical = WORK_CARD_PADDING),
                ) {
                    Text(
                        text = work.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = WORK_TITLE_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Consumes whatever is left so the stats stay pinned to the bottom of this
                    // band, directly above the tag strip.
                    Spacer(Modifier.weight(1f))
                    WorkStats(work)
                }
            }
            if (work.tags.isNotEmpty()) {
                WorkTagRow(work.tags)
            }
        }
    }
}

/** One scrollable line of tags along the bottom of a card, spanning its full width. */
@Composable
private fun WorkTagRow(tags: List<TagRef>) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(WORK_TAG_ROW_HEIGHT),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tags.size) { index ->
            WorkTag(tags[index].name)
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
private fun AudioSortSheet(
    current: AudioSort,
    onDismiss: () -> Unit,
    onSelect: (AudioSort) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(MR.strings.audio_sort),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 12.dp),
            )
            // Grouped so accessibility reports the entries as one radio set rather than five
            // unrelated clickable rows.
            Column(modifier = Modifier.selectableGroup()) {
                AudioSort.entries.forEach { option ->
                    SortOptionRow(
                        option = option,
                        selected = option == current,
                        onSelect = { onSelect(option) },
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/**
 * One entry of the sort sheet: a full-width pill that fills with secondaryContainer when active.
 *
 * The pill is what carries the selection — the radio only confirms it — so a tap anywhere in the
 * row lands on the same target the eye is already reading.
 */
@Composable
private fun SortOptionRow(
    option: AudioSort,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SORT_OPTION_HEIGHT)
            .clip(CircleShape)
            .background(containerColor)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        Text(
            text = stringResource(option.label),
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * Hairline between two work cards.
 *
 * Cards sit on surfaceContainerLow against a surface list, so the fill alone only hints at an
 * edge. The rule finishes the separation without claiming a divider's worth of attention: it is
 * inset from both screen edges, alpha-dimmed, and given a few dp of air on each side so the two
 * cards read as neighbours rather than as rows in a table.
 */
@Composable
private fun WorkSeparator() {
    Column {
        Spacer(Modifier.height(WORK_SEPARATOR_GAP))
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = WORK_SEPARATOR_INSET),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = WORK_SEPARATOR_ALPHA),
        )
        Spacer(Modifier.height(WORK_SEPARATOR_GAP))
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

/** Minimum row height of a sort-sheet entry: the M3 single-line list tile height. */
private val SORT_OPTION_HEIGHT = 56.dp

/** Corner radius of the card, and of the cover where it meets one. */
private val WORK_CARD_CORNER = 6.dp

/** Height of every work card: the cover band plus the tag strip. */
private val WORK_CARD_HEIGHT = 152.dp

/**
 * Height of the tag strip along the bottom edge. Chip is 20dp (labelSmall plus its own padding);
 * the rest is air above and below it, which is what separates the strip from the stats above.
 */
private val WORK_TAG_ROW_HEIGHT = 28.dp

/** Vertical padding of the text column beside the cover. */
private val WORK_CARD_PADDING = 7.dp

/**
 * Title lines a card can show. Three fit in the cover band; the [Spacer] below the title absorbs
 * the leftover on a short title rather than stretching the card.
 */
private const val WORK_TITLE_MAX_LINES = 3

/** Air above and below the hairline between two work cards. */
private val WORK_SEPARATOR_GAP = 3.dp

/** How far the hairline stops short of the screen edge, so it reads as a rule and not a border. */
private val WORK_SEPARATOR_INSET = 4.dp

/** The rule is a hint, not a divider: dimmed well past outlineVariant's own weight. */
private const val WORK_SEPARATOR_ALPHA = 0.45f

/** Gap left above a tab separator so it does not butt against the toolbar edge. */
private val DIVIDER_TOP_INSET = 10.dp

/** Gap left below a tab separator so it clears the selected-tab indicator. */
private val DIVIDER_BOTTOM_INSET = 6.dp

/**
 * Vertical rule between category tabs, shared by the browse and category screens so both tab rows
 * read the same way.
 *
 * Spans most of the row height so it reads as a column rule rather than a stub floating beside the
 * label. The insets are deliberately uneven: it hangs lower than it sits high, so it reads as a
 * stem landing on the row's own bottom divider instead of a bar centred in the row. The bottom
 * inset has to clear the selected-tab indicator, which is drawn at the very bottom edge, so it
 * cannot go to zero. Both use outlineVariant, so the corner matches.
 */
@Composable
internal fun AudioTabDivider() {
    Box(
        Modifier
            .fillMaxHeight()
            .padding(
                top = DIVIDER_TOP_INSET,
                bottom = DIVIDER_BOTTOM_INSET,
            )
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
