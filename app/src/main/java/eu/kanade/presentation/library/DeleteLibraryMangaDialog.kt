package eu.kanade.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Confirmation for clearing the downloaded chapters of the selected works.
 *
 * Taking works off the shelf has its own button in the shelf row, so it is not offered here: this
 * dialog is left with the filesystem action, which is the one the row paints red and names for
 * what it erases.
 */
@Composable
fun DeleteLibraryMangaDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm()
                },
            ) {
                Text(text = stringResource(MR.strings.action_delete))
            }
        },
        // 警告图标排在标题左侧同一行里，而不是走 M3 的 icon 槽位：那个槽位把图标居中放在
        // 标题上方并独占一行高度，弹窗会明显变高、标题也会被推离左边缘。和标题同行居左
        // 更紧凑，视线也从警告直接接上「删的是什么」。
        //
        // 标题字号沿用 M3 的 headlineSmall，只在断行上做手脚：这条标题短（"删除已下载的
        // 篇目？"），本来一行放得下，但译文变长时不该为了凑两行宽度而在词组中间断开。
        // WordBreak.Phrase 按词组边界断行，得到的是完整的词，而不是"删除已下载的 / 篇目？"
        // 那种均分效果（LineBreak.Heading 就那样，已弃用）。
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    // headlineSmall 行高 32dp、图标 24dp，补 4dp 让图标落在首行的光学中心。
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = stringResource(MR.strings.delete_downloaded_chapters_title),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        lineBreak = LineBreak(
                            strategy = LineBreak.Strategy.Simple,
                            strictness = LineBreak.Strictness.Default,
                            wordBreak = LineBreak.WordBreak.Phrase,
                        ),
                    ),
                )
            }
        },
        text = {
            Text(text = stringResource(MR.strings.delete_downloaded_chapters_confirmation))
        },
    )
}
