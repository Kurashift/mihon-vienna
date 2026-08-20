package eu.kanade.presentation.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ChapterTranslatedTitleActionsDialog(
    chapter: Chapter,
    onDismissRequest: () -> Unit,
    onEdit: () -> Unit,
    onCopyOriginalTitle: () -> Unit,
    onSelect: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(chapter.translatedNameOrNull ?: chapter.name) },
        text = {
            Column {
                ChapterTitleAction(
                    label = stringResource(MR.strings.edit_chapter_translated_title),
                    icon = Icons.Outlined.Edit,
                    onClick = onEdit,
                )
                ChapterTitleAction(
                    label = stringResource(MR.strings.copy_original_title),
                    icon = Icons.Outlined.ContentCopy,
                    onClick = onCopyOriginalTitle,
                )
                ChapterTitleAction(
                    label = stringResource(MR.strings.select_chapter),
                    icon = Icons.Outlined.CheckBox,
                    onClick = onSelect,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_close))
            }
        },
    )
}

@Composable
fun EditChapterTranslatedTitleDialog(
    chapter: Chapter,
    onDismissRequest: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(chapter.id) { mutableStateOf(chapter.translatedNameOrNull.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.edit_chapter_translated_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(chapter.name)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(MR.strings.chapter_translated_title)) },
                    supportingText = { Text(stringResource(MR.strings.chapter_translated_title_hint)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun ChapterTitleAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Text(label)
    }
}
