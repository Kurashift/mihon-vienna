package eu.kanade.presentation.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.manga.ChapterTitleTranslationFormat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ChapterTitleTranslationDialog(
    onDismissRequest: () -> Unit,
    onExportCurrentManga: (ChapterTitleTranslationFormat, Boolean) -> Unit,
    onImportCurrentManga: () -> Unit,
) {
    var showExportFormatDialog by remember { mutableStateOf(false) }
    if (showExportFormatDialog) {
        ChapterTitleTranslationExportFormatDialog(
            onDismissRequest = { showExportFormatDialog = false },
            onFormatSelected = { format, onlyUntranslated ->
                showExportFormatDialog = false
                onExportCurrentManga(format, onlyUntranslated)
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.current_manga_chapter_title_translations)) },
        text = {
            Column {
                TranslationAction(
                    label = stringResource(MR.strings.export_current_manga_chapter_titles),
                    icon = Icons.Outlined.FileDownload,
                    onClick = { showExportFormatDialog = true },
                )
                TranslationAction(
                    label = stringResource(MR.strings.import_current_manga_chapter_titles),
                    icon = Icons.Outlined.FileUpload,
                    onClick = onImportCurrentManga,
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
fun LocalLibraryChapterTitleTranslationDialog(
    onDismissRequest: () -> Unit,
    onExport: (ChapterTitleTranslationFormat, Boolean) -> Unit,
    onImport: () -> Unit,
    onImportMangaFiles: () -> Unit,
) {
    var showExportFormatDialog by remember { mutableStateOf(false) }
    if (showExportFormatDialog) {
        ChapterTitleTranslationExportFormatDialog(
            onDismissRequest = { showExportFormatDialog = false },
            onFormatSelected = { format, onlyUntranslated ->
                showExportFormatDialog = false
                onExport(format, onlyUntranslated)
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.local_library_chapter_title_translations)) },
        text = {
            Column {
                TranslationAction(
                    label = stringResource(MR.strings.export_local_library_chapter_titles),
                    icon = Icons.Outlined.FileDownload,
                    onClick = { showExportFormatDialog = true },
                )
                TranslationAction(
                    label = stringResource(MR.strings.import_local_library_chapter_titles),
                    icon = Icons.Outlined.FileUpload,
                    onClick = onImport,
                )
                TranslationAction(
                    label = stringResource(MR.strings.import_multiple_manga_chapter_titles),
                    icon = Icons.Outlined.FileUpload,
                    onClick = onImportMangaFiles,
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
private fun ChapterTitleTranslationExportFormatDialog(
    onDismissRequest: () -> Unit,
    onFormatSelected: (ChapterTitleTranslationFormat, Boolean) -> Unit,
) {
    var onlyUntranslated by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.chapter_title_translation_export_format)) },
        text = {
            Column {
                TranslationAction(
                    label = stringResource(MR.strings.chapter_title_translation_format_json),
                    icon = Icons.Outlined.FileDownload,
                    onClick = { onFormatSelected(ChapterTitleTranslationFormat.JSON, onlyUntranslated) },
                )
                TranslationAction(
                    label = stringResource(MR.strings.chapter_title_translation_format_csv),
                    icon = Icons.Outlined.FileDownload,
                    onClick = { onFormatSelected(ChapterTitleTranslationFormat.CSV, onlyUntranslated) },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onlyUntranslated = !onlyUntranslated }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Checkbox(
                        checked = onlyUntranslated,
                        onCheckedChange = { onlyUntranslated = it },
                    )
                    Text(text = stringResource(MR.strings.chapter_title_translation_export_only_empty))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun TranslationAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Text(text = label)
    }
}
