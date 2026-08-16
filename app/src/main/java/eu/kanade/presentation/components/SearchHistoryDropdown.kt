package eu.kanade.presentation.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import eu.kanade.tachiyomi.data.search.SearchHistoryStore
import kotlinx.coroutines.flow.flowOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.material3.DropdownMenu as ComposeDropdownMenu

@Composable
fun SearchHistoryDropdown(
    historyKey: String?,
    query: String,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val store = rememberSearchHistoryStore() ?: return
    val historyFlow = remember(historyKey) {
        historyKey?.let(store::observe) ?: flowOf(emptyList())
    }
    val history by historyFlow.collectAsState(initial = emptyList())
    val suggestions = remember(history, query) {
        history
            .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
            .take(MAX_VISIBLE_ENTRIES)
    }

    ComposeDropdownMenu(
        expanded = expanded && historyKey != null && suggestions.isNotEmpty(),
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 240.dp, max = 360.dp),
        properties = PopupProperties(focusable = false),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(MR.strings.search_history),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            TextButton(
                onClick = {
                    historyKey?.let(store::clear)
                    onDismissRequest()
                },
            ) {
                Text(stringResource(MR.strings.clear_search_history))
            }
        }

        suggestions.forEach { entry ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = entry,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                onClick = {
                    historyKey?.let { store.add(it, entry) }
                    onSelect(entry)
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = {
                    IconButton(
                        onClick = { historyKey?.let { store.remove(it, entry) } },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(MR.strings.action_delete),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
        }
    }
}

@Composable
fun rememberSearchHistoryStore(): SearchHistoryStore? = remember {
    runCatching { Injekt.get<SearchHistoryStore>() }.getOrNull()
}

private const val MAX_VISIBLE_ENTRIES = 5
