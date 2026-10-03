package eu.kanade.tachiyomi.util.system

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.delegate.ThemingDelegate
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.lang.truncateCenter
import logcat.LogPriority
import rikka.shizuku.ShizukuProvider
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Copies a string to clipboard
 *
 * @param label Label to show to the user describing the content
 * @param content the actual text to copy to the board
 */
fun Context.copyToClipboard(label: String, content: String) {
    if (content.isBlank()) return

    try {
        val clipboard = getSystemService<ClipboardManager>()!!
        clipboard.setPrimaryClip(ClipData.newPlainText(label, content))

        toast(stringResource(MR.strings.copied_to_clipboard, content.truncateCenter(50)))
    } catch (e: Throwable) {
        logcat(LogPriority.ERROR, e)
        toast(MR.strings.clipboard_copy_error)
    }
}

val Context.powerManager: PowerManager
    get() = getSystemService()!!

fun Context.openInBrowser(url: String, forceDefaultBrowser: Boolean = false) {
    this.openInBrowser(url.toUri(), forceDefaultBrowser)
}

fun Context.openInBrowser(uri: Uri, forceDefaultBrowser: Boolean = false) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            // Force default browser so that verified extensions don't re-open Tachiyomi
            if (forceDefaultBrowser) {
                defaultBrowserPackageName()?.let { setPackage(it) }
            }
        }
        startActivity(intent)
    } catch (e: Exception) {
        toast(e.message)
    }
}

private fun Context.defaultBrowserPackageName(): String? {
    val browserIntent = Intent(Intent.ACTION_VIEW, "http://".toUri())
    val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.resolveActivity(
            browserIntent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )
    } else {
        packageManager.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
    }
    return resolveInfo
        ?.activityInfo?.packageName
        ?.takeUnless { it in DeviceUtil.invalidDefaultBrowsers }
}

fun Context.createFileInCacheDir(name: String): File {
    val file = File(externalCacheDir, name)
    if (file.exists()) {
        file.delete()
    }
    file.createNewFile()
    return file
}

/**
 * Creates night mode Context depending on reader theme/background
 *
 * Context wrapping method obtained from AppCompatDelegateImpl
 * https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:appcompat/appcompat/src/main/java/androidx/appcompat/app/AppCompatDelegateImpl.java;l=348;drc=e28752c96fc3fb4d3354781469a1af3dbded4898
 */
fun Context.createReaderThemeContext(): Context {
    val preferences = Injekt.get<UiPreferences>()
    val readerPreferences = Injekt.get<ReaderPreferences>()
    val themeMode = preferences.themeMode.get()
    val isDarkBackground = when (readerPreferences.readerTheme.get()) {
        1, 2 -> true // Black, Gray
        3 -> when (themeMode) { // Automatic bg uses activity background by default
            ThemeMode.SYSTEM -> applicationContext.isNightMode()
            else -> themeMode == ThemeMode.DARK
        }
        else -> false // White
    }
    val expected = if (isDarkBackground) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != expected) {
        val overrideConf = Configuration()
        overrideConf.setTo(resources.configuration)
        overrideConf.uiMode = (overrideConf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or expected

        val wrappedContext = ContextThemeWrapper(this, R.style.Theme_Tachiyomi)
        wrappedContext.applyOverrideConfiguration(overrideConf)
        ThemingDelegate.getThemeResIds(preferences.appTheme.get(), preferences.themeDarkAmoled.get())
            .forEach { wrappedContext.theme.applyStyle(it, true) }
        return wrappedContext
    }
    return this
}

/**
 * Gets document size of provided [Uri]
 *
 * @return document size of [uri] or null if size can't be obtained
 */
fun Context.getUriSize(uri: Uri): Long? {
    return UniFile.fromUri(this, uri)?.length()?.takeIf { it >= 0 }
}

/**
 * Returns true if [packageName] is installed.
 */
fun Context.isPackageInstalled(packageName: String): Boolean {
    return try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

val Context.hasMiuiPackageInstaller get() = isPackageInstalled("com.miui.packageinstaller")

/**
 * Vendor permission (TAF TTAF 108-2022) that some ROMs — MIUI/HyperOS, ColorOS, OriginOS,
 * HarmonyOS — require before [PackageManager.getInstalledPackages] returns anything but a
 * stub list. Declared in the manifest, but AOSP does not define it, so it must be probed.
 */
const val PERMISSION_GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS"

/**
 * Returns true if the system defines [PERMISSION_GET_INSTALLED_APPS]. Only such ROMs gate
 * package visibility behind it; everywhere else the permission is absent and package
 * visibility works the AOSP way via `QUERY_ALL_PACKAGES`.
 */
fun Context.isAppListPermissionDefined(): Boolean {
    return try {
        packageManager.getPermissionInfo(PERMISSION_GET_INSTALLED_APPS, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

/**
 * Returns true if [PERMISSION_GET_INSTALLED_APPS] is granted to this app. Always false where
 * the permission is not defined, so callers should check [isAppListPermissionDefined] first.
 */
fun Context.isAppListPermissionGranted(): Boolean {
    return checkSelfPermission(PERMISSION_GET_INSTALLED_APPS) == PackageManager.PERMISSION_GRANTED
}

/**
 * Opens the ROM's per-app permission editor. Some ROMs silently refuse the runtime prompt for
 * [PERMISSION_GET_INSTALLED_APPS], so this is the fallback that always lands somewhere the user
 * can toggle it. Falls back to the app's own details page if the vendor screen is missing.
 */
fun Context.launchAppListPermissionSettings() {
    val miuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
        putExtra("extra_pkgname", packageName)
    }
    runCatching { startActivity(miuiIntent) }
        .recoverCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
            )
        }
}

val Context.isShizukuInstalled: Boolean
    get() = try {
        packageManager.getPermissionInfo(ShizukuProvider.PERMISSION, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

fun Context.launchRequestPackageInstallsPermission() {
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
        data = "package:$packageName".toUri()
        startActivity(this)
    }
}

fun Context.launchAppDetailsSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
    )
}

fun Context.launchNotificationSettings() {
    startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        },
    )
}

fun Context.launchAllFilesAccessPermission() {
    val intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        "package:$packageName".toUri(),
    )
    runCatching { startActivity(intent) }
        .recoverCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
}
