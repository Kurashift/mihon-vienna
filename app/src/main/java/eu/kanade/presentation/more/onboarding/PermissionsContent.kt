package eu.kanade.presentation.more.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.kanade.presentation.util.rememberRequestPackageInstallsPermissionState
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.system.PERMISSION_GET_INSTALLED_APPS
import eu.kanade.tachiyomi.util.system.isAppListPermissionDefined
import eu.kanade.tachiyomi.util.system.isAppListPermissionGranted
import eu.kanade.tachiyomi.util.system.launchAllFilesAccessPermission
import eu.kanade.tachiyomi.util.system.launchAppDetailsSettings
import eu.kanade.tachiyomi.util.system.launchNotificationSettings
import eu.kanade.tachiyomi.util.system.launchRequestPackageInstallsPermission
import eu.kanade.tachiyomi.util.system.telemetryIncluded
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The permission list, shared by the onboarding's permission step and the standalone
 * "App permissions" screen. It carries no chrome of its own: the caller supplies the scroll
 * container and any surrounding frame.
 *
 * Every row is a switch that can be tapped either way. Which page a row opens when it is already
 * granted is decided per permission, because Android only provides a prompt for runtime
 * permissions; the special ones (install apps, all files, battery) can only be changed in system
 * settings, and the ROMs that gate package visibility behind their own permission refuse to
 * deep-link to that entry, so those land on the app's own details page instead.
 */
@Composable
internal fun PermissionList(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val privacyPreferences = remember { Injekt.get<PrivacyPreferences>() }
    val extensionManager = remember { Injekt.get<ExtensionManager>() }

    var notificationGranted by remember { mutableStateOf(false) }
    var batteryGranted by remember { mutableStateOf(false) }
    var allFilesAccessGranted by remember { mutableStateOf(false) }
    var appListGranted by remember { mutableStateOf(false) }

    val installGranted = rememberRequestPackageInstallsPermissionState()

    val appListPermissionDefined = remember { context.isAppListPermissionDefined() }

    DisposableEffect(lifecycleOwner.lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
                batteryGranted = context.getSystemService<PowerManager>()!!
                    .isIgnoringBatteryOptimizations(context.packageName)
                allFilesAccessGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    true
                }
                // Granted while the app was in the background: rescan so the extensions the ROM was
                // hiding show up without a restart.
                val appListNowGranted = appListPermissionDefined && context.isAppListPermissionGranted()
                if (appListNowGranted && !appListGranted) {
                    extensionManager.reloadExtensions()
                }
                appListGranted = appListNowGranted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(modifier = modifier) {
        PermissionToggleRow(
            title = stringResource(MR.strings.onboarding_permission_install_apps),
            subtitle = stringResource(MR.strings.onboarding_permission_install_apps_description),
            granted = installGranted,
            onClick = {
                if (installGranted) {
                    context.launchAppDetailsSettings()
                } else {
                    context.launchRequestPackageInstallsPermission()
                }
            },
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionRequester = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = {
                    // no-op. resulting checks is being done on resume
                },
            )
            PermissionToggleRow(
                title = stringResource(MR.strings.onboarding_permission_notifications),
                subtitle = stringResource(MR.strings.onboarding_permission_notifications_description),
                granted = notificationGranted,
                onClick = {
                    if (notificationGranted) {
                        context.launchNotificationSettings()
                    } else {
                        permissionRequester.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        }

        PermissionToggleRow(
            title = stringResource(MR.strings.onboarding_permission_ignore_battery_opts),
            subtitle = stringResource(MR.strings.onboarding_permission_ignore_battery_opts_description),
            granted = batteryGranted,
            onClick = {
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:${context.packageName}".toUri()
                }
                context.startActivity(intent)
            },
        )

        if (appListPermissionDefined) {
            val appListRequester = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    appListGranted = true
                    extensionManager.reloadExtensions()
                }
                // A denial is handled by the row still reading false; the user can tap again.
            }
            PermissionToggleRow(
                title = stringResource(MR.strings.onboarding_permission_app_list),
                subtitle = stringResource(MR.strings.onboarding_permission_app_list_description),
                granted = appListGranted,
                onClick = {
                    if (appListGranted) {
                        context.launchAppDetailsSettings()
                    } else {
                        appListRequester.launch(PERMISSION_GET_INSTALLED_APPS)
                    }
                },
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            PermissionToggleRow(
                title = stringResource(MR.strings.onboarding_permission_all_files_access),
                subtitle = stringResource(MR.strings.onboarding_permission_all_files_access_description),
                granted = allFilesAccessGranted,
                onClick = {
                    context.launchAllFilesAccessPermission()
                },
            )
        }

        if (!telemetryIncluded) return@Column

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )

        val crashlyticsPref = privacyPreferences.crashlytics
        val crashlytics by crashlyticsPref.collectAsState()
        PermissionSwitch(
            title = stringResource(MR.strings.onboarding_permission_crashlytics),
            subtitle = stringResource(MR.strings.onboarding_permission_crashlytics_description),
            granted = crashlytics,
            onToggleChange = crashlyticsPref::set,
        )

        val analyticsPref = privacyPreferences.analytics
        val analytics by analyticsPref.collectAsState()
        PermissionSwitch(
            title = stringResource(MR.strings.onboarding_permission_analytics),
            subtitle = stringResource(MR.strings.onboarding_permission_analytics_description),
            granted = analytics,
            onToggleChange = analyticsPref::set,
        )
    }
}

@Composable
private fun PermissionToggleRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        trailingContent = {
            Switch(
                checked = granted,
                onCheckedChange = null,
            )
        },
        supportingContent = { Text(text = subtitle) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        content = { Text(text = title) },
    )
}

@Composable
private fun PermissionSwitch(
    title: String,
    subtitle: String,
    granted: Boolean,
    modifier: Modifier = Modifier,
    onToggleChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = modifier,
        trailingContent = {
            Switch(
                checked = granted,
                onCheckedChange = onToggleChange,
            )
        },
        supportingContent = { Text(text = subtitle) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        content = { Text(text = title) },
    )
}
