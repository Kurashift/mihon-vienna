package eu.kanade.presentation.more.settings.screen

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.data.CreateBackupScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsBackupScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.label_backup

    @Composable
    override fun getPreferences(): List<Preference> {
        val backupPreferences = Injekt.get<BackupPreferences>()
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val lastAutoBackup by backupPreferences.lastAutoBackupTimestamp.collectAsState()

        val chooseBackup = rememberLauncherForActivityResult(
            object : ActivityResultContracts.GetContent() {
                override fun createIntent(context: Context, input: String): Intent {
                    val intent = super.createIntent(context, input)
                    return Intent.createChooser(intent, context.stringResource(MR.strings.file_select_backup))
                }
            },
        ) {
            if (it != null) navigator.push(RestoreBackupScreen(it.toString()))
        }

        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_create_backup),
                onClick = { navigator.push(CreateBackupScreen()) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_restore_backup),
                onClick = {
                    if (BackupRestoreJob.isRunning(context)) {
                        context.toast(MR.strings.restore_in_progress)
                    } else {
                        if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                            context.toast(MR.strings.restore_miui_warning)
                        }
                        chooseBackup.launch("*/*")
                    }
                },
            ),
            Preference.PreferenceItem.ListPreference(
                preference = backupPreferences.backupInterval,
                entries = mapOf(
                    0 to stringResource(MR.strings.off),
                    6 to stringResource(MR.strings.update_6hour),
                    12 to stringResource(MR.strings.update_12hour),
                    24 to stringResource(MR.strings.update_24hour),
                    48 to stringResource(MR.strings.update_48hour),
                    168 to stringResource(MR.strings.update_weekly),
                ),
                title = stringResource(MR.strings.pref_backup_interval),
                subtitleProvider = { value, entries ->
                    listOfNotNull(
                        entries[value],
                        if (lastAutoBackup > 0) {
                            stringResource(MR.strings.last_auto_backup_info, relativeTimeSpanString(lastAutoBackup))
                        } else {
                            null
                        },
                    ).joinToString("\n")
                },
                onValueChanged = {
                    BackupCreateJob.setupTask(context, it)
                    true
                },
            ),
            Preference.PreferenceItem.InfoPreference(
                stringResource(MR.strings.backup_info),
            ),
        )
    }
}
