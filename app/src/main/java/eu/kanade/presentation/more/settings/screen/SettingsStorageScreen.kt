package eu.kanade.presentation.more.settings.screen

import android.text.format.Formatter
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.data.StorageInfo
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.source.local.image.LocalChapterCoverGenerationState
import tachiyomi.source.local.image.LocalChapterCoverManager
import tachiyomi.source.local.image.LocalChapterCoverStats
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsStorageScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.settings_storage

    @Composable
    override fun getPreferences(): List<Preference> {
        val storagePreferences = Injekt.get<StoragePreferences>()
        return getStorageLocationItems(storagePreferences) + listOf(
            getDataGroup(),
            coverMaintenanceGroup(),
        )
    }

    @Composable
    private fun getStorageLocationItems(
        storagePreferences: StoragePreferences,
    ): List<Preference> {
        val context = LocalContext.current
        val pickStorageLocation = SettingsDataScreen.storageLocationPicker(storagePreferences.baseStorageDirectory)

        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_storage_location),
                subtitle = SettingsDataScreen.storageLocationText(storagePreferences.baseStorageDirectory),
                onClick = {
                    try {
                        pickStorageLocation.launch(null)
                    } catch (e: android.content.ActivityNotFoundException) {
                        context.toast(MR.strings.file_picker_error)
                    }
                },
            ),
            Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_storage_location_info)),
        )
    }

    @Composable
    private fun coverMaintenanceGroup(): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val chapterCoverManager = remember { Injekt.get<LocalChapterCoverManager>() }
        val generationState by chapterCoverManager.generationState.collectAsState()
        var coverStats by remember { mutableStateOf(LocalChapterCoverStats(0, 0L)) }
        var showClearCoversDialog by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(generationState) {
            if (generationState !is LocalChapterCoverGenerationState.Running) {
                coverStats = withIOContext { chapterCoverManager.stats() }
            }
        }

        val coverStatus = when (val state = generationState) {
            LocalChapterCoverGenerationState.Idle -> stringResource(
                MR.strings.local_chapter_covers_idle,
                coverStats.count,
                Formatter.formatFileSize(context, coverStats.size),
            )
            is LocalChapterCoverGenerationState.Running -> stringResource(
                MR.strings.local_chapter_covers_progress,
                state.completed,
                state.total,
            )
            is LocalChapterCoverGenerationState.Finished -> stringResource(
                MR.strings.local_chapter_covers_finished,
                state.result.generated,
                state.result.skipped,
                state.result.failed,
                state.result.removed,
            )
            LocalChapterCoverGenerationState.Failed -> stringResource(MR.strings.local_chapter_covers_failed)
        }
        val isGenerating = generationState is LocalChapterCoverGenerationState.Running

        if (showClearCoversDialog) {
            val dismiss = { showClearCoversDialog = false }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(text = stringResource(MR.strings.local_chapter_covers_clear)) },
                text = { Text(text = stringResource(MR.strings.local_chapter_covers_clear_confirm)) },
                dismissButton = {
                    TextButton(onClick = dismiss) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dismiss()
                            scope.launch {
                                val cleared = withIOContext { chapterCoverManager.clear() }
                                coverStats = withIOContext { chapterCoverManager.stats() }
                                context.toast(
                                    context.stringResource(MR.strings.local_chapter_covers_cleared, cleared),
                                )
                            }
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_delete))
                    }
                },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.local_chapter_covers),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.local_chapter_covers_generate),
                        subtitle = coverStatus,
                        enabled = !isGenerating,
                        onClick = chapterCoverManager::startGenerateAll,
                    ),
                )
                if (isGenerating) {
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(MR.strings.local_chapter_covers_cancel),
                            onClick = chapterCoverManager::cancelGeneration,
                        ),
                    )
                }
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.local_chapter_covers_clear),
                        enabled = !isGenerating && coverStats.count > 0,
                        onClick = { showClearCoversDialog = true },
                    ),
                )
            },
        )
    }

    @Composable
    private fun getDataGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }

        val chapterCache = remember { Injekt.get<ChapterCache>() }
        var cacheReadableSizeSema by remember { mutableIntStateOf(0) }
        val cacheReadableSize = remember(cacheReadableSizeSema) { chapterCache.readableSize }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_storage_usage),
            preferenceItems = listOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_storage_usage),
                ) {
                    BasePreferenceWidget(
                        subcomponent = {
                            StorageInfo(
                                modifier = Modifier.padding(horizontal = PrefsHorizontalPadding),
                            )
                        },
                    )
                },

                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_chapter_cache),
                    subtitle = stringResource(MR.strings.used_cache, cacheReadableSize),
                    onClick = {
                        scope.launchNonCancellable {
                            try {
                                val deletedFiles = chapterCache.clear()
                                withUIContext {
                                    context.toast(context.stringResource(MR.strings.cache_deleted, deletedFiles))
                                    cacheReadableSizeSema++
                                }
                            } catch (e: Throwable) {
                                logcat(LogPriority.ERROR, e)
                                withUIContext { context.toast(MR.strings.cache_delete_error) }
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.autoClearChapterCache,
                    title = stringResource(MR.strings.pref_auto_clear_chapter_cache),
                ),
            ),
        )
    }
}
