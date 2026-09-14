package eu.kanade.presentation.manga

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.ui.manga.ChapterTitleTranslationFormat
import eu.kanade.tachiyomi.ui.manga.LocalLibraryChapterTitleImportPlan
import eu.kanade.tachiyomi.ui.manga.LocalLibraryChapterTitleTranslations
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Owns the document pickers, result toasts and dialog for local library chapter title translation
 * import/export. The settings screen and the local library toolbar share this instead of each
 * keeping their own copy of the launchers.
 */
@Composable
fun LocalLibraryChapterTitleTranslationsHost(
    visible: Boolean,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val translations = remember { LocalLibraryChapterTitleTranslations(context = context) }
    // Remembered between the format dialog and the document picker result so the launcher
    // callbacks can forward the "only untranslated" choice to the exporter.
    var pendingExportOnlyUntranslated by remember { mutableStateOf(false) }

    fun showExportResult(result: Result<Pair<Int, Int>>) {
        result
            .onSuccess { (mangaCount, chapterCount) ->
                context.toast(
                    context.stringResource(
                        MR.strings.local_library_chapter_title_translations_exported,
                        mangaCount,
                        chapterCount,
                    ),
                )
            }
            .onFailure { error ->
                context.logcat(LogPriority.ERROR, error)
                context.toast(MR.strings.chapter_title_translation_export_failed)
            }
    }

    fun showImportResult(result: Result<LocalLibraryChapterTitleImportPlan>) {
        result
            .onSuccess { plan -> showTranslationImportResult(context, plan.importedCount, plan.ignoredCount) }
            .onFailure { error ->
                context.logcat(LogPriority.ERROR, error)
                context.toast(MR.strings.chapter_title_translation_import_failed)
            }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ChapterTitleTranslationFormat.JSON.mimeType),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            showExportResult(
                runCatching {
                    translations.export(uri, ChapterTitleTranslationFormat.JSON, pendingExportOnlyUntranslated)
                },
            )
        }
    }
    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ChapterTitleTranslationFormat.CSV.mimeType),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            showExportResult(
                runCatching {
                    translations.export(uri, ChapterTitleTranslationFormat.CSV, pendingExportOnlyUntranslated)
                },
            )
        }
    }
    val importLibraryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { showImportResult(runCatching { translations.importLibrary(uri) }) }
    }
    val importMangaFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch { showImportResult(runCatching { translations.importMangaFiles(uris) }) }
    }

    if (!visible) return

    LocalLibraryChapterTitleTranslationDialog(
        onDismissRequest = onDismissRequest,
        onExport = { format, onlyUntranslated ->
            onDismissRequest()
            pendingExportOnlyUntranslated = onlyUntranslated
            val scopeSuffix = if (onlyUntranslated) "_未译名" else ""
            when (format) {
                ChapterTitleTranslationFormat.JSON -> {
                    exportJsonLauncher.launch(
                        "mihon_local_library_chapter_translations$scopeSuffix.${format.fileExtension}",
                    )
                }
                ChapterTitleTranslationFormat.CSV -> {
                    exportCsvLauncher.launch(
                        "mihon_local_library_chapter_translations$scopeSuffix.${format.fileExtension}",
                    )
                }
            }
        },
        onImport = {
            onDismissRequest()
            importLibraryLauncher.launch("*/*")
        },
        onImportMangaFiles = {
            onDismissRequest()
            importMangaFilesLauncher.launch("*/*")
        },
    )
}

private fun showTranslationImportResult(context: Context, imported: Int, ignored: Int) {
    context.toast(
        context.stringResource(
            MR.strings.local_library_chapter_title_translations_imported,
            imported,
            ignored,
        ),
    )
}
