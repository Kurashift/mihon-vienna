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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ChapterTitleTranslationDialog(
    onDismissRequest: () -> Unit,
    onExportCurrentManga: () -> Unit,
    onImportCurrentManga: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.current_manga_chapter_title_translations)) },
        text = {
            Column {
                TranslationAction(
                    label = stringResource(MR.strings.export_current_manga_chapter_titles),
                    icon = Icons.Outlined.FileDownload,
                    onClick = onExportCurrentManga,
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
    onExport: () -> Unit,
    onImport: () -> Unit,
    onImportMangaFiles: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.local_library_chapter_title_translations)) },
        text = {
            Column {
                TranslationAction(
                    label = stringResource(MR.strings.export_local_library_chapter_titles),
                    icon = Icons.Outlined.FileDownload,
                    onClick = onExport,
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
