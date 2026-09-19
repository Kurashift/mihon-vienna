package eu.kanade.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Confirmation shown before local files are erased from disk.
 *
 * Lists what is about to go so the destructive nature of the action is explicit, unlike the
 * "remove from list" confirmations which only drop a list entry.
 *
 * The body is a set of distinct slots rather than free-form text on purpose: the erasure warning,
 * the affected scope, and the reassurances each carry a different tone, and folding them into one
 * string forced them to share a single colour — which is what made a reassurance read as a second
 * warning.
 */
@Composable
fun DeleteLocalEntriesDialog(
    title: String,
    entryNames: List<String>,
    subtitle: String? = null,
    notices: List<String> = emptyList(),
    inProgress: Boolean = false,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismissRequest() },
        dismissButton = {
            TextButton(enabled = !inProgress, onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !inProgress && entryNames.isNotEmpty(),
                onClick = onConfirm,
            ) {
                Text(
                    text = stringResource(
                        if (inProgress) MR.strings.local_delete_progress else MR.strings.action_delete,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        // 警告图标排在标题左侧同一行里，而不是走 M3 的 icon 槽位：那个槽位把图标居中放在
        // 标题上方并独占一行高度，弹窗会明显变高、标题也会被推离左边缘。和标题同行居左
        // 更紧凑，视线也从警告直接接上「删的是什么」。
        //
        // 标题字号沿用 M3 的 headlineSmall（AlertDialog 的默认标题样式），只在断行上做手脚。
        // 这些标题是一整句中文（"删除 1 个合集的本地文件？"），图标又占掉左侧约 32dp，
        // 在这台设备上标题可用宽约 256dp，24sp 下这句话要 288dp —— 一定会折成两行。
        //
        // 所以关键不是让它别折，而是让它折在对的地方：
        //   LineBreak.Heading（均分行宽）会为了两行等长而在词组中间下刀，断成
        //   "删除 1 个合集 / 的本地文件？"，比孤字更难读，不能用；
        //   默认断行会贪心填满第一行，末尾留下孤零零的"件？"，就是最初的问题；
        //   WordBreak.Phrase 按词组边界断行（Android 13+），得到
        //   "删除 1 个合集的 / 本地文件？"，两边都是完整词组。
        //
        // 漫画名不受调用方控制，可能非常长；maxLines 留 2 行并截断——用户就在那一页上
        // 点的删除，名字认得出来。
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                // 图标对齐首行而不是整块居中：标题折成两行时，居中会让图标落在两行中间，
                // 看起来像挂在文字旁边。首行对齐在单行和多行下都对。
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
                    text = title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                // Spacing is what separates the warning from the list; without it the blocks run
                // together into one paragraph.
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.local_delete_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entryNames.isNotEmpty()) {
                    Text(
                        text = condensedBulletList(entryNames),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Muted, not red. These say what will *not* happen or what survives; painting them
                // with the error colour made them look like further danger.
                notices.filterNot { it.isBlank() }.forEach { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}
