package eu.kanade.presentation.components

import androidx.compose.runtime.Composable
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Renders [names] as a bullet list, collapsing the tail past [maxVisible].
 *
 * Dialogs have to keep their buttons reachable, so a long selection is summarised rather than
 * printed in full. Shared by the local-file deletion and import-conflict dialogs so both collapse
 * at the same point instead of each picking its own limit.
 */
@Composable
internal fun condensedBulletList(names: List<String>, maxVisible: Int = 6): String = buildString {
    val visible = names.take(maxVisible)
    visible.forEach { name ->
        appendLine("\u2022 ${name.trim().ifBlank { "-" }}")
    }
    val remaining = names.size - visible.size
    if (remaining > 0) {
        append("\u2022 ")
        append(stringResource(MR.strings.list_and_more, remaining))
    }
}.trimEnd()
