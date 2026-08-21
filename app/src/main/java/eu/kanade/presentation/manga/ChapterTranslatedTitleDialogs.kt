package eu.kanade.presentation.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun EditChapterTranslatedTitleDialog(
    chapter: Chapter,
    onDismissRequest: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(chapter.id, chapter.translatedNameOrNull) {
        mutableStateOf(chapter.translatedNameOrNull.orEmpty())
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.edit_chapter_translated_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(chapter.name)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    label = { Text(stringResource(MR.strings.chapter_translated_title)) },
                    supportingText = { Text(stringResource(MR.strings.chapter_translated_title_hint)) },
                    minLines = 3,
                    maxLines = 6,
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
