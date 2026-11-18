package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

internal val ReaderPanelInset = 24.dp

@Composable
private fun PanelRow(
    label: StringResource,
    modifier: Modifier,
    trailingContent: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = ReaderPanelInset, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        trailingContent()
    }
}

@Composable
internal fun PanelSwitch(label: StringResource, pref: Preference<Boolean>) {
    val checked by pref.collectAsState()
    PanelRow(
        label = label,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = pref::set),
    ) {
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun PanelCheckbox(label: StringResource, checked: Boolean, onChange: (Boolean) -> Unit) {
    PanelRow(
        label = label,
        modifier = Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun <T> PanelChoice(
    label: StringResource,
    value: T,
    options: List<Pair<T, StringResource>>,
    onChange: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == value }?.second

    Box {
        PanelRow(
            label = label,
            modifier = Modifier.clickable(role = Role.Button) { expanded = true },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(0.5f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentLabel != null) {
                    Text(
                        text = stringResource(currentLabel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
                Icon(Icons.Default.ExpandMore, contentDescription = null)
            }
        }
        Box(modifier = Modifier.align(Alignment.BottomEnd).padding(end = ReaderPanelInset)) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                options.forEach { (option, optionLabel) ->
                    val isSelected = option == value
                    DropdownMenuItem(
                        text = { Text(stringResource(optionLabel)) },
                        leadingIcon = { RadioButton(selected = isSelected, onClick = null) },
                        modifier = Modifier.semantics { selected = isSelected },
                        onClick = {
                            expanded = false
                            if (!isSelected) onChange(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun <T> PanelChoice(
    label: StringResource,
    pref: Preference<T>,
    options: List<Pair<T, StringResource>>,
) {
    val value by pref.collectAsState()
    PanelChoice(label, value, options, pref::set)
}

@Composable
internal fun PanelGroup(label: StringResource, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    HorizontalDivider(modifier = Modifier.padding(horizontal = ReaderPanelInset))
    PanelRow(
        label = label,
        modifier = Modifier.clickable(
            role = Role.Button,
            onClickLabel = stringResource(
                if (expanded) MR.strings.reader_panel_collapse else MR.strings.reader_panel_expand,
            ),
            onClick = { expanded = !expanded },
        ),
    ) {
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
    if (expanded) {
        Column(modifier = Modifier.padding(start = 12.dp)) {
            content()
        }
    }
}
