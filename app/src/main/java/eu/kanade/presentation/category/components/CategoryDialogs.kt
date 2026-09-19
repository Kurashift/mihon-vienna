package eu.kanade.presentation.category.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import eu.kanade.core.preference.asToggleableState
import eu.kanade.presentation.category.visualName
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun CategoryCreateDialog(
    onDismissRequest: () -> Unit,
    onCreate: (String) -> Unit,
    categories: List<String>,
) {
    var name by remember { mutableStateOf("") }

    val focusRequester = remember { FocusRequester() }
    val nameAlreadyExists = remember(name) { categories.contains(name) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = name.isNotEmpty() && !nameAlreadyExists,
                onClick = {
                    onCreate(name)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_add_category))
        },
        text = {
            OutlinedTextField(
                modifier = Modifier
                    .focusRequester(focusRequester),
                value = name,
                onValueChange = { name = it },
                label = {
                    Text(text = stringResource(MR.strings.name))
                },
                supportingText = {
                    val msgRes = if (name.isNotEmpty() && nameAlreadyExists) {
                        MR.strings.error_category_exists
                    } else {
                        MR.strings.information_required_plain
                    }
                    Text(text = stringResource(msgRes))
                },
                isError = name.isNotEmpty() && nameAlreadyExists,
                singleLine = true,
            )
        },
    )

    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }
}

@Composable
fun CategoryRenameDialog(
    onDismissRequest: () -> Unit,
    onRename: (String) -> Unit,
    categories: List<String>,
    category: String,
) {
    var name by remember { mutableStateOf(category) }
    var valueHasChanged by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val nameAlreadyExists = remember(name) { categories.contains(name) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = valueHasChanged && !nameAlreadyExists,
                onClick = {
                    onRename(name)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_rename_category))
        },
        text = {
            OutlinedTextField(
                modifier = Modifier.focusRequester(focusRequester),
                value = name,
                onValueChange = {
                    valueHasChanged = name != it
                    name = it
                },
                label = { Text(text = stringResource(MR.strings.name)) },
                supportingText = {
                    val msgRes = if (valueHasChanged && nameAlreadyExists) {
                        MR.strings.error_category_exists
                    } else {
                        MR.strings.information_required_plain
                    }
                    Text(text = stringResource(msgRes))
                },
                isError = valueHasChanged && nameAlreadyExists,
                singleLine = true,
            )
        },
    )

    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }
}

@Composable
fun CategoryDeleteDialog(
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
    category: String,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = {
                onDelete()
                onDismissRequest()
            }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.delete_category))
        },
        text = {
            Text(text = stringResource(MR.strings.delete_category_confirmation, category))
        },
    )
}

@Composable
fun ChangeCategoryDialog(
    initialSelection: List<CheckboxState<Category>>,
    onDismissRequest: () -> Unit,
    onEditCategories: () -> Unit,
    onConfirm: (List<Long>, List<Long>) -> Unit,
    includeDefaultCategory: Boolean = false,
) {
    if (initialSelection.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {
                tachiyomi.presentation.core.components.material.TextButton(
                    onClick = {
                        onDismissRequest()
                        onEditCategories()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_edit_categories))
                }
            },
            title = {
                Text(text = stringResource(MR.strings.action_move_category))
            },
            text = {
                Text(text = stringResource(MR.strings.information_empty_category_dialog))
            },
        )
        return
    }
    var selection by remember { mutableStateOf(initialSelection) }
    val checkedIds = selection
        .filter { it is CheckboxState.State.Checked || it is CheckboxState.TriState.Include }
        .map { it.value.id }
    val uncheckedIds = selection
        .filter { it is CheckboxState.State.None || it is CheckboxState.TriState.None }
        .map { it.value.id }
    // Nothing picked is not a decision this picker can carry out: a work belongs to a shelf or to
    // none, and "none" is what the library's own button does, not something a filing dialog should
    // reach. The confirm stays disabled rather than quietly leaving the works unfiled.
    val hasPick = !selectionPicksNothing(selection)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            Row {
                tachiyomi.presentation.core.components.material.TextButton(onClick = {
                    onDismissRequest()
                    onEditCategories()
                }) {
                    Text(text = stringResource(MR.strings.action_edit))
                }
                Spacer(modifier = Modifier.weight(1f))
                tachiyomi.presentation.core.components.material.TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
                tachiyomi.presentation.core.components.material.TextButton(
                    enabled = hasPick,
                    onClick = {
                        onDismissRequest()
                        onConfirm(checkedIds, uncheckedIds)
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_move_category))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (includeDefaultCategory) {
                    val defaultCategory = selection.firstOrNull { it.value.isSystemCategory }
                    if (defaultCategory != null) {
                        // The default shelf excludes every named one, and picking any named one
                        // leaves the default shelf, so the two cannot be on at the same time.
                        val onDefaultChange: (CheckboxState<Category>) -> Unit = {
                            val turningOn = it is CheckboxState.State.None
                            selection = selection.map { checkbox ->
                                when {
                                    checkbox.value.isSystemCategory -> checkbox.next()
                                    turningOn -> CheckboxState.State.None(checkbox.value)
                                    else -> checkbox
                                }
                            }
                        }
                        CategoryRow(checkbox = defaultCategory, onChange = onDefaultChange)
                    }
                }
                selection.filterNot { it.value.isSystemCategory }.forEach { checkbox ->
                    val onChange: (CheckboxState<Category>) -> Unit = {
                        val index = selection.indexOf(it)
                        if (index != -1) {
                            val mutableList = selection.toMutableList()
                            mutableList[index] = it.next()
                            // A named category and the default shelf are mutually exclusive:
                            // turning one on takes the work out of the other.
                            selection = if (it is CheckboxState.State.None) {
                                mutableList.map { current ->
                                    if (current.value.isSystemCategory) {
                                        CheckboxState.State.None(current.value)
                                    } else {
                                        current
                                    }
                                }.toList()
                            } else {
                                mutableList.toList()
                            }
                        }
                    }
                    CategoryRow(checkbox = checkbox, onChange = onChange)
                }
            }
        },
    )
}

/**
 * Whether [selection] picks no shelf at all.
 *
 * A work belongs to a shelf or to none, and none is a state the picker cannot produce: taking
 * works off the shelf is the library row's own button. Confirming such a pick would file the works
 * nowhere, so the caller disables its confirm instead. Only an outright empty pick counts: a batch
 * of works filed differently opens with half-checked rows, and reading those as "nothing picked"
 * would disable the confirm on a selection the user never touched.
 */
internal fun selectionPicksNothing(selection: List<CheckboxState<Category>>): Boolean {
    if (selection.isEmpty()) return false
    return selection.all {
        it is CheckboxState.State.None || it is CheckboxState.TriState.None
    }
}

/**
 * The ids of [checked] that may actually be written as category rows.
 *
 * The default shelf is the absence of a row, not a row pointing at [Category.UNCATEGORIZED_ID], so
 * its id is a signal the picker reads rather than something to persist. Writing it would name a
 * shelf that does not exist and make the stored categories of a work filed nowhere come back
 * non-empty, which is the state the default shelf is defined as.
 */
internal fun writableCategoryIds(checked: List<Long>): List<Long> =
    checked.filterNot { it == Category.UNCATEGORIZED_ID }

@Composable
private fun CategoryRow(
    checkbox: CheckboxState<Category>,
    onChange: (CheckboxState<Category>) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(checkbox) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (checkbox) {
            is CheckboxState.TriState -> {
                TriStateCheckbox(
                    state = checkbox.asToggleableState(),
                    onClick = { onChange(checkbox) },
                )
            }
            is CheckboxState.State -> {
                Checkbox(
                    checked = checkbox.isChecked,
                    onCheckedChange = { onChange(checkbox) },
                )
            }
        }

        Text(
            text = checkbox.value.visualName,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
        )
    }
}
