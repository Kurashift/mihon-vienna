package eu.kanade.presentation.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.tachiyomi.data.local.LocalDeletionReport
import eu.kanade.tachiyomi.data.local.LocalDeletionReportCsv
import eu.kanade.tachiyomi.data.local.LocalDeletionUnit
import eu.kanade.tachiyomi.data.local.LocalEntryDeletionService
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hosts the two dialogs a local delete can raise, at the root of the app so every entry point
 * shares them and the result survives the page that started the run.
 *
 * The progress dialog waits [PROGRESS_DELAY_MILLIS] before showing: a single chapter takes a few
 * milliseconds, and a dialog that appears and vanishes in the same breath is worse than none.
 */
@Composable
fun LocalDeletionHost() {
    val context = LocalContext.current
    val service = remember { Injekt.get<LocalEntryDeletionService>() }
    val progress by service.progress.collectAsStateWithLifecycle()
    val report by service.lastReport.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var progressVisible by remember { mutableStateOf(false) }
    val running = progress != null
    LaunchedEffect(running) {
        if (running) {
            delay(PROGRESS_DELAY_MILLIS)
            progressVisible = true
        } else {
            progressVisible = false
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val snapshot = service.lastReport.value ?: return@rememberLauncherForActivityResult
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                LocalDeletionReportCsv.write(context, uri, snapshot)
            }
            if (written) {
                service.clearReport()
                context.toast(MR.strings.local_delete_exported)
            } else {
                context.toast(MR.strings.local_delete_export_failed)
            }
        }
    }

    val currentProgress = progress
    if (progressVisible && currentProgress != null) {
        LocalDeletionProgressDialog(
            progress = currentProgress,
            onStop = service::requestStop,
        )
    }

    val currentReport = report
    // One entry, all of it gone, nobody pressed stop: the name is the whole receipt.
    val singleSuccess = currentReport?.takeIf { it.isSingleSuccess }
    val singleText = singleSuccess?.let {
        stringResource(MR.strings.local_delete_one, it.entries.first().name)
    }
    LaunchedEffect(singleSuccess?.id) {
        if (singleText != null) {
            context.toast(singleText)
            service.clearReport()
        }
    }

    if (currentReport != null && singleSuccess == null) {
        LocalDeletionResultDialog(
            report = currentReport,
            onExport = { exportLauncher.launch(exportFileName()) },
            onDismiss = service::clearReport,
        )
    }
}

@Composable
private fun LocalDeletionProgressDialog(
    progress: LocalEntryDeletionService.Progress,
    onStop: () -> Unit,
) {
    // Dismissing by tapping outside would look like a cancel but leave the run going, so the only
    // way out is the button.
    AlertDialog(
        onDismissRequest = {},
        title = { Text(text = stringResource(MR.strings.local_delete_progress)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress.phase == LocalEntryDeletionService.Progress.Phase.COUNTING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(MR.strings.local_delete_counting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        progress = {
                            if (progress.total <= 0) {
                                0f
                            } else {
                                (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
                            }
                        },
                    )
                    Text(
                        text = String.format(Locale.US, "%,d / %,d", progress.done, progress.total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onStop) {
                Text(text = stringResource(MR.strings.action_stop))
            }
        },
    )
}

@Composable
private fun LocalDeletionResultDialog(
    report: LocalDeletionReport,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when {
        report.cancelled -> stringResource(MR.strings.local_delete_stopped)
        report.entries.isEmpty() -> stringResource(MR.strings.local_delete_unavailable)
        report.deletedCount == 0 -> stringResource(MR.strings.local_delete_failed)
        report.failedCount > 0 -> stringResource(
            MR.strings.local_delete_partial,
            report.deletedCount,
            report.failedCount,
        )
        report.unit == LocalDeletionUnit.COLLECTION -> stringResource(
            MR.strings.local_delete_done_collections,
            report.deletedCount,
        )
        else -> stringResource(MR.strings.local_delete_done_chapters, report.deletedCount)
    }

    val summary = if (report.cancelled) {
        stringResource(
            MR.strings.local_delete_stopped_summary,
            report.deletedCount,
            report.skippedCount,
        )
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = summary?.let { text -> { Text(text = text) } },
        dismissButton = {
            TextButton(
                enabled = report.entries.isNotEmpty(),
                onClick = onExport,
            ) {
                Text(text = stringResource(MR.strings.action_export_list))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(MR.strings.action_done))
            }
        },
    )
}

private fun exportFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return "deleted-$stamp.csv"
}

private const val PROGRESS_DELAY_MILLIS = 500L
