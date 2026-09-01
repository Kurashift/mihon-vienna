package eu.kanade.presentation.audio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.SearchHistoryDropdown
import eu.kanade.presentation.components.rememberSearchHistoryStore
import eu.kanade.tachiyomi.data.audio.AudioCategoryField
import eu.kanade.tachiyomi.data.search.SearchHistoryScope
import eu.kanade.tachiyomi.ui.audio.AudioCategoryState
import eu.kanade.tachiyomi.ui.audio.AudioCategoryType
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun AudioCategoryContent(
    state: AudioCategoryState,
    initialType: AudioCategoryType,
    bottomBar: @Composable () -> Unit,
    navigateUp: () -> Unit,
    onSelectTab: (AudioCategoryType) -> Unit,
    onRetry: (AudioCategoryField) -> Unit,
    onSelect: (AudioCategoryType, String, String) -> Unit,
) {
    var selectedTab by remember(initialType) { mutableStateOf(initialType) }
    var query by remember { mutableStateOf("") }
    var historyExpanded by remember { mutableStateOf(false) }
    val searchHistoryStore = rememberSearchHistoryStore()

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.audio_categories),
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = bottomBar,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            // Same row structure as the browse screen (equal-width tabs split by a column rule)
            // so the two tab rows read identically even though they pick from different sources.
            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                AudioCategoryType.entries.forEachIndexed { index, type ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tab(
                            selected = selectedTab == type,
                            onClick = {
                                if (selectedTab != type) {
                                    selectedTab = type
                                    onSelectTab(type)
                                }
                            },
                            text = { Text(stringResource(type.label)) },
                            modifier = Modifier.weight(1f),
                        )
                        if (index < AudioCategoryType.entries.lastIndex) {
                            AudioTabDivider()
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val historyKey = SearchHistoryScope.audioCategory(selectedTab.name)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged {
                            historyExpanded = it.isFocused
                            if (!it.isFocused) searchHistoryStore?.add(historyKey, query)
                        },
                    placeholder = { Text(stringResource(MR.strings.action_search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            searchHistoryStore?.add(historyKey, query)
                            historyExpanded = false
                        },
                    ),
                )
                SearchHistoryDropdown(
                    historyKey = historyKey,
                    query = query,
                    expanded = historyExpanded,
                    onDismissRequest = { historyExpanded = false },
                    onSelect = {
                        query = it
                        historyExpanded = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val activeField = selectedTab.field
            val activeItems = when (selectedTab) {
                AudioCategoryType.CIRCLE -> state.circles
                AudioCategoryType.VA -> state.vas
                AudioCategoryType.TAG -> state.tags
            }
            when {
                activeItems.isNotEmpty() -> {
                    // Anything already loaded stays on screen while a refresh runs: a thin bar is
                    // enough of a signal, and blanking a populated list would defeat the point of
                    // having cached it.
                    if (activeField in state.loadingFields) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    when (selectedTab) {
                        AudioCategoryType.CIRCLE -> CategoryList(
                            items = state.circles,
                            query = query,
                            nameOf = { it.name },
                            countOf = { it.count },
                            keyOf = { it.id },
                            idOf = { it.id.toString() },
                            onSelect = { id, name -> onSelect(AudioCategoryType.CIRCLE, id, name) },
                        )
                        AudioCategoryType.VA -> CategoryList(
                            items = state.vas,
                            query = query,
                            nameOf = { it.name },
                            countOf = { it.count },
                            keyOf = { it.id },
                            idOf = { it.id },
                            onSelect = { id, name -> onSelect(AudioCategoryType.VA, id, name) },
                        )
                        AudioCategoryType.TAG -> CategoryList(
                            items = state.tags,
                            query = query,
                            nameOf = { it.name },
                            countOf = { it.count },
                            keyOf = { it.id },
                            idOf = { it.id.toString() },
                            onSelect = { id, name -> onSelect(AudioCategoryType.TAG, id, name) },
                        )
                    }
                }
                activeField in state.errorFields -> {
                    EmptyScreen(
                        stringRes = MR.strings.audio_load_failed,
                        actions = listOf(
                            EmptyScreenAction(
                                stringRes = MR.strings.audio_retry,
                                icon = Icons.Outlined.Refresh,
                                onClick = { onRetry(activeField) },
                            ),
                        ),
                    )
                }
                else -> LoadingScreen()
            }
        }
    }
}

@Composable
private fun <T> CategoryList(
    items: List<T>,
    query: String,
    nameOf: (T) -> String,
    countOf: (T) -> Int,
    keyOf: (T) -> Any,
    /** The backend id, so the results page can filter by id instead of re-parsing the name. */
    idOf: (T) -> String,
    onSelect: (String, String) -> Unit,
) {
    // The tag list is tens of thousands of rows, so re-filtering it on every recomposition (every
    // keystroke in the search field) is a measurable stall.
    val filtered = remember(items, query) {
        items.filter { query.isBlank() || nameOf(it).contains(query, ignoreCase = true) }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(filtered, key = { keyOf(it) }) { item ->
            CategoryRow(
                name = nameOf(item),
                count = countOf(item),
                onClick = { onSelect(idOf(item), nameOf(item)) },
            )
        }
    }
}

@Composable
private fun CategoryRow(
    name: String,
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count > 0) {
                Text(
                    text = pluralStringResource(MR.plurals.audio_works_count, count = count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
