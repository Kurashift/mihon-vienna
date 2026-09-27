package eu.kanade.presentation.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.markdown.model.markdownDimens
import eu.kanade.presentation.manga.components.MarkdownRender
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

private val DialogCornerRadius = 28.dp

/**
 * Ceiling for the dialog on wide screens, so it does not stretch into a single line of text on a
 * tablet or in landscape. The width itself is requested by the content — see [NewUpdateContent].
 */
private val DialogMaxWidth = 640.dp

/**
 * Share of the screen the dialog asks for.
 *
 * It has to ask: without [DialogProperties.usePlatformDefaultWidth] turned off, the window is sized
 * by the platform dialog theme at roughly two thirds of the screen, and any width set inside is only
 * an upper bound that the window never grows to meet. The release notes are the reason this matters
 * — they carry Markdown tables, and at the default width their second column was cut off entirely.
 */
private const val DialogWidthFraction = 0.95f

/** Padding between the card edge and its content, notes and button row alike. */
private val ContentHorizontalPadding = 16.dp

/**
 * The dialog that announces a new release, and shows what is in it.
 *
 * The body is whatever the release notes say, rendered as Markdown. Anything that has to be read
 * before installing therefore belongs *in those notes*, written in plain Markdown: this dialog is
 * drawn by the version already on the device, so a warning only reaches the people it is for if the
 * release they are running can already render it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewUpdateScreen(
    versionName: String,
    changelogInfo: String,
    onOpenInBrowser: () -> Unit,
    onAcceptUpdate: () -> Unit,
    onRejectUpdate: () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onRejectUpdate,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NewUpdateContent(
            versionName = versionName,
            changelogInfo = changelogInfo,
            onOpenInBrowser = onOpenInBrowser,
            onAcceptUpdate = onAcceptUpdate,
            onRejectUpdate = onRejectUpdate,
        )
    }
}

@Composable
private fun NewUpdateContent(
    versionName: String,
    changelogInfo: String,
    onOpenInBrowser: () -> Unit,
    onAcceptUpdate: () -> Unit,
    onRejectUpdate: () -> Unit,
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    Surface(
        modifier = Modifier
            .fillMaxWidth(DialogWidthFraction)
            .widthIn(max = DialogMaxWidth)
            .heightIn(max = maxHeight),
        shape = RoundedCornerShape(DialogCornerRadius),
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Column {
            UpdateHeader(versionName = versionName)

            Column(
                modifier = Modifier
                    .weight(weight = 1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (changelogInfo.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = ContentHorizontalPadding,
                                vertical = MaterialTheme.padding.medium,
                            ),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        MarkdownRender(
                            content = changelogInfo,
                            flavour = remember { GFMFlavourDescriptor() },
                            // Narrower cells than the library's 160dp default, which is sized for a
                            // full-width page: a two column table asked for 320dp, and a table that
                            // does not fit is put in a horizontally scrolling box that the card's
                            // rounded corners then clip — the second column simply vanished.
                            dimens = markdownDimens(tableCellWidth = 120.dp),
                            modifier = Modifier.padding(MaterialTheme.padding.medium),
                        )
                    }
                }

                TextButton(
                    onClick = onOpenInBrowser,
                    modifier = Modifier.padding(start = MaterialTheme.padding.medium),
                ) {
                    Text(text = stringResource(MR.strings.update_check_open))
                    Spacer(modifier = Modifier.width(MaterialTheme.padding.extraSmall))
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = ContentHorizontalPadding,
                        vertical = MaterialTheme.padding.medium,
                    ),
                horizontalArrangement = Arrangement.spacedBy(
                    space = MaterialTheme.padding.small,
                    alignment = Alignment.End,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRejectUpdate) {
                    Text(text = stringResource(MR.strings.action_not_now))
                }
                Button(onClick = onAcceptUpdate) {
                    Text(text = stringResource(MR.strings.update_check_confirm))
                }
            }
        }
    }
}

@Composable
private fun UpdateHeader(
    versionName: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    topStart = DialogCornerRadius,
                    topEnd = DialogCornerRadius,
                ),
            )
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ),
            )
            .padding(
                horizontal = MaterialTheme.padding.large,
                vertical = MaterialTheme.padding.large,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.NewReleases,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(MaterialTheme.padding.medium))

            Text(
                text = stringResource(MR.strings.update_check_notification_update_available),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(MaterialTheme.padding.small))

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            ) {
                Text(
                    text = versionName,
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.padding.medium,
                        vertical = MaterialTheme.padding.extraSmall,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun NewUpdateScreenPreview() {
    TachiyomiPreviewTheme {
        Surface {
            NewUpdateContent(
                versionName = "v0.99.9",
                changelogInfo = """
                    ## 装之前先备份

                    这一版不能直接覆盖安装，需要先卸载再重装。

                    | 步骤 | 操作 |
                    | --- | --- |
                    | 1 | 设置 → 数据与存储 → 创建备份 |
                    | 2 | 卸载旧版 |
                    | 3 | 安装新版 |
                    | 4 | 从备份恢复 |
                """.trimIndent(),
                onOpenInBrowser = {},
                onAcceptUpdate = {},
                onRejectUpdate = {},
            )
        }
    }
}
