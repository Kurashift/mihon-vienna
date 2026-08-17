package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ActionButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderPageActionsDialog(
    onDismissRequest: () -> Unit,
    onSetAsCover: () -> Unit,
    onSetAsChapterCover: (() -> Unit)? = null,
    onShare: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    var showSetCoverDialog by remember { mutableStateOf(false) }
    var showSetChapterCoverDialog by remember { mutableStateOf(false) }

    // Compact single-row action bar: every action gets an equal slice, so labels stay short
    // and the sheet stays one line tall even with five actions.
    val rowLabelStyle = MaterialTheme.typography.labelSmall
    val rowContentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp)

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.action_cover),
                    icon = Icons.Outlined.Photo,
                    onClick = { showSetCoverDialog = true },
                    labelStyle = rowLabelStyle,
                    contentPadding = rowContentPadding,
                )
                if (onSetAsChapterCover != null) {
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        title = stringResource(MR.strings.action_chapter_cover),
                        icon = Icons.Outlined.Photo,
                        onClick = { showSetChapterCoverDialog = true },
                        labelStyle = rowLabelStyle,
                        contentPadding = rowContentPadding,
                    )
                }
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.action_copy),
                    icon = Icons.Outlined.ContentCopy,
                    onClick = {
                        onShare(true)
                        onDismissRequest()
                    },
                    labelStyle = rowLabelStyle,
                    contentPadding = rowContentPadding,
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.action_share),
                    icon = Icons.Outlined.Share,
                    onClick = {
                        onShare(false)
                        onDismissRequest()
                    },
                    labelStyle = rowLabelStyle,
                    contentPadding = rowContentPadding,
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.action_save),
                    icon = Icons.Outlined.Save,
                    onClick = {
                        onSave()
                        onDismissRequest()
                    },
                    labelStyle = rowLabelStyle,
                    contentPadding = rowContentPadding,
                )
            }
        }
    }

    if (showSetCoverDialog) {
        SetCoverDialog(
            text = stringResource(MR.strings.confirm_set_image_as_cover),
            onConfirm = {
                onSetAsCover()
                showSetCoverDialog = false
                onDismissRequest()
            },
            onDismiss = { showSetCoverDialog = false },
        )
    }
    if (showSetChapterCoverDialog) {
        SetCoverDialog(
            text = stringResource(MR.strings.confirm_set_image_as_chapter_cover),
            onConfirm = {
                onSetAsChapterCover?.invoke()
                showSetChapterCoverDialog = false
                onDismissRequest()
            },
            onDismiss = { showSetChapterCoverDialog = false },
        )
    }
}

@Composable
private fun SetCoverDialog(
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        text = {
            Text(text)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
        onDismissRequest = onDismiss,
    )
}
