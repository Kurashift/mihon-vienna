package eu.kanade.presentation.more.settings.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
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
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsStorageScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.settings_storage

    @Composable
    override fun getPreferences(): List<Preference> {
        val storagePreferences = Injekt.get<StoragePreferences>()
        return listOf(
            getStorageLocationGroup(storagePreferences = storagePreferences),
            getDataGroup(),
        )
    }

    @Composable
    private fun getStorageLocationGroup(
        storagePreferences: StoragePreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val pickStorageLocation = SettingsDataScreen.storageLocationPicker(storagePreferences.baseStorageDirectory)

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_storage_location),
            preferenceItems = listOfNotNull(
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
                getLocalSourceDirectAccessPref(),
            ),
        )
    }

    @Composable
    private fun getLocalSourceDirectAccessPref(): Preference.PreferenceItem.TextPreference? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val context = LocalContext.current
        val granted = Environment.isExternalStorageManager()

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_local_source_direct_access),
            subtitle = stringResource(
                if (granted) {
                    MR.strings.pref_local_source_direct_access_granted
                } else {
                    MR.strings.pref_local_source_direct_access_summary
                },
            ),
            onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    "package:${context.packageName}".toUri(),
                )
                runCatching { context.startActivity(intent) }
                    .recoverCatching {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
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
