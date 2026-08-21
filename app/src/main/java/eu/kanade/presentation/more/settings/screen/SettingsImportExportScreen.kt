package eu.kanade.presentation.more.settings.screen

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.manga.LocalLibraryChapterTitleTranslationDialog
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.export.LibraryExporter
import eu.kanade.tachiyomi.data.export.LibraryExporter.ExportOptions
import eu.kanade.tachiyomi.ui.manga.ChapterTitleTranslationFormat
import eu.kanade.tachiyomi.ui.manga.LocalLibraryChapterTitleTranslations
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsImportExportScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.settings_import_export

    @Composable
    override fun getPreferences(): List<Preference> {
        return listOf(
            getExportGroup(),
            getChapterTitleTranslationsGroup(),
        )
    }

    @Composable
    private fun getChapterTitleTranslationsGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val translations = remember { LocalLibraryChapterTitleTranslations(context = context) }
        var showDialog by remember { mutableStateOf(false) }
        // Remembered between the format dialog and the document picker result so the launcher
        // callbacks can forward the "only untranslated" choice to the exporter.
        var pendingExportOnlyUntranslated by remember { mutableStateOf(false) }

        val exportJsonLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(ChapterTitleTranslationFormat.JSON.mimeType),
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                    translations.export(uri, ChapterTitleTranslationFormat.JSON, pendingExportOnlyUntranslated)
                }
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
                        logcat(LogPriority.ERROR, error)
                        context.toast(MR.strings.chapter_title_translation_export_failed)
                    }
            }
        }
        val exportCsvLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(ChapterTitleTranslationFormat.CSV.mimeType),
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                    translations.export(uri, ChapterTitleTranslationFormat.CSV, pendingExportOnlyUntranslated)
                }
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
                        logcat(LogPriority.ERROR, error)
                        context.toast(MR.strings.chapter_title_translation_export_failed)
                    }
            }
        }
        val importLibraryLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                runCatching { translations.importLibrary(uri) }
                    .onSuccess { plan -> showTranslationImportResult(context, plan.updates.size, plan.ignoredCount) }
                    .onFailure { error ->
                        logcat(LogPriority.ERROR, error)
                        context.toast(MR.strings.chapter_title_translation_import_failed)
                    }
            }
        }
        val importMangaFilesLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetMultipleContents(),
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching { translations.importMangaFiles(uris) }
                    .onSuccess { plan -> showTranslationImportResult(context, plan.updates.size, plan.ignoredCount) }
                    .onFailure { error ->
                        logcat(LogPriority.ERROR, error)
                        context.toast(MR.strings.chapter_title_translation_import_failed)
                    }
            }
        }

        if (showDialog) {
            LocalLibraryChapterTitleTranslationDialog(
                onDismissRequest = { showDialog = false },
                onExport = { format, onlyUntranslated ->
                    showDialog = false
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
                    showDialog = false
                    importLibraryLauncher.launch("*/*")
                },
                onImportMangaFiles = {
                    showDialog = false
                    importMangaFilesLauncher.launch("*/*")
                },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.chapter_title_translations),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.local_library_chapter_title_translations),
                    onClick = { showDialog = true },
                ),
            ),
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

    @Composable
    private fun getExportGroup(): Preference.PreferenceGroup {
        var showDialog by remember { mutableStateOf(false) }
        var exportOptions by remember {
            mutableStateOf(
                ExportOptions(
                    includeTitle = true,
                    includeAuthor = true,
                    includeArtist = true,
                ),
            )
        }

        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val getFavorites = remember { Injekt.get<GetFavorites>() }
        var favorites by remember { mutableStateOf<List<Manga>>(emptyList()) }
        LaunchedEffect(Unit) {
            favorites = getFavorites.await()
        }

        val saveFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            uri?.let {
                scope.launch {
                    LibraryExporter.exportToCsv(
                        context = context,
                        uri = it,
                        favorites = favorites,
                        options = exportOptions,
                        onExportComplete = {
                            scope.launch(Dispatchers.Main) {
                                context.toast(MR.strings.library_exported)
                            }
                        },
                    )
                }
            }
        }

        if (showDialog) {
            ColumnSelectionDialog(
                options = exportOptions,
                onConfirm = { options ->
                    exportOptions = options
                    saveFileLauncher.launch("mihon_library.csv")
                },
                onDismissRequest = { showDialog = false },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.export),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.library_list),
                    onClick = { showDialog = true },
                ),
            ),
        )
    }

    @Composable
    private fun ColumnSelectionDialog(
        options: ExportOptions,
        onConfirm: (ExportOptions) -> Unit,
        onDismissRequest: () -> Unit,
    ) {
        var titleSelected by remember { mutableStateOf(options.includeTitle) }
        var authorSelected by remember { mutableStateOf(options.includeAuthor) }
        var artistSelected by remember { mutableStateOf(options.includeArtist) }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = {
                Text(text = stringResource(MR.strings.migration_dialog_what_to_include))
            },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = titleSelected,
                            onCheckedChange = { checked ->
                                titleSelected = checked
                                if (!checked) {
                                    authorSelected = false
                                    artistSelected = false
                                }
                            },
                        )
                        Text(text = stringResource(MR.strings.title))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = authorSelected,
                            onCheckedChange = { authorSelected = it },
                            enabled = titleSelected,
                        )
                        Text(text = stringResource(MR.strings.author))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = artistSelected,
                            onCheckedChange = { artistSelected = it },
                            enabled = titleSelected,
                        )
                        Text(text = stringResource(MR.strings.artist))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirm(
                            ExportOptions(
                                includeTitle = titleSelected,
                                includeAuthor = authorSelected,
                                includeArtist = artistSelected,
                            ),
                        )
                        onDismissRequest()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}
