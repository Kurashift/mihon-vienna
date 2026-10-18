package eu.kanade.presentation.audio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.tachiyomi.ui.audio.AudioAccountState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The account page: what the backend holds about the signed-in user, plus the two changes the app
 * can make to it on its own.
 *
 * Everything here is one request. Binding an email is not: the backend wants the user to send a
 * mail from their own mailbox and then polls for it, so that half is handed to the browser
 * ([onOpenWebSettings]) where the mailbox and the saved password already are.
 */
@Composable
fun AudioAccountContent(
    state: AudioAccountState,
    bottomBar: @Composable () -> Unit,
    navigateUp: () -> Unit,
    onRetry: () -> Unit,
    onChangePassword: (String) -> Unit,
    onUnlinkEmail: () -> Unit,
    onOpenWebSettings: () -> Unit,
    onLogout: () -> Unit,
    /** Closes the password dialog, dropping the outcome so reopening starts clean. */
    onDismissPasswordResult: () -> Unit,
    onDismissActionError: () -> Unit,
) {
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showUnlinkDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                titleContent = { AppBarTitle(stringResource(MR.strings.audio_account)) },
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = bottomBar,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            AccountRow(
                icon = Icons.Outlined.Person,
                title = stringResource(MR.strings.audio_username),
                value = state.username,
            )
            HorizontalDivider()

            AccountRow(
                icon = Icons.Outlined.Email,
                title = stringResource(MR.strings.audio_account_email),
                value = when {
                    state.loading -> stringResource(MR.strings.audio_account_loading)
                    state.email != null -> state.email
                    else -> stringResource(MR.strings.audio_account_email_none)
                },
                action = if (state.email != null) {
                    stringResource(MR.strings.audio_account_email_unlink)
                } else {
                    stringResource(MR.strings.audio_account_email_link)
                },
                actionEnabled = !state.guest && !state.unlinking && !state.loading,
                onAction = {
                    if (state.email != null) showUnlinkDialog = true else onOpenWebSettings()
                },
            )
            HorizontalDivider()

            AccountRow(
                icon = Icons.Outlined.Lock,
                title = stringResource(MR.strings.audio_account_password),
                value = null,
                action = stringResource(MR.strings.audio_account_change),
                actionEnabled = !state.guest && !state.loading,
                onAction = {
                    onDismissActionError()
                    showPasswordDialog = true
                },
            )
            HorizontalDivider()

            AccountRow(
                icon = Icons.AutoMirrored.Outlined.Logout,
                title = stringResource(MR.strings.audio_logout),
                value = null,
                action = null,
                onAction = onLogout,
            )

            // The two refusals share one line at the bottom rather than living in the rows: both
            // actions write the same account and either can be the one that failed.
            if (state.actionError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (state.actionErrorFromServer && state.actionError.isNotBlank()) {
                        state.actionError
                    } else {
                        stringResource(MR.strings.audio_account_action_failed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (state.loadFailed) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(MR.strings.audio_account_load_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(MR.strings.action_retry))
                    }
                }
            }
            if (state.guest) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(MR.strings.audio_account_guest_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    if (showPasswordDialog) {
        ChangePasswordDialog(
            state = state,
            onDismiss = {
                showPasswordDialog = false
                onDismissPasswordResult()
            },
            onSubmit = onChangePassword,
        )
    }

    if (showUnlinkDialog) {
        val email = state.email.orEmpty()
        AlertDialog(
            onDismissRequest = { showUnlinkDialog = false },
            title = { Text(stringResource(MR.strings.audio_account_email_unlink)) },
            text = { Text(stringResource(MR.strings.audio_account_email_unlink_confirm, email)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnlinkDialog = false
                        onUnlinkEmail()
                    },
                ) {
                    Text(stringResource(MR.strings.audio_account_email_unlink))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlinkDialog = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

/**
 * One line of the account list: an icon, what the row is, what it currently holds, and at most one
 * action. The action is a text button rather than a chevron because the three rows do three
 * different things, and a chevron would promise a page behind each of them.
 */
@Composable
private fun AccountRow(
    icon: ImageVector,
    title: String,
    value: String?,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onAction != null) {
                    Modifier.clickable(enabled = actionEnabled, onClick = onAction)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (action != null && onAction != null) {
            TextButton(onClick = onAction, enabled = actionEnabled) {
                Text(action)
            }
        }
    }
}

/**
 * Changing the password: two fields and a submit, plus the one outcome the backend cannot report
 * by itself — success, which switches the dialog to a single "done" button.
 *
 * There is no field for the current password because the endpoint does not take one; the token is
 * the whole proof. Offering the field anyway would suggest a check that never happens.
 */
@Composable
private fun ChangePasswordDialog(
    state: AudioAccountState,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    if (state.passwordChanged) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(MR.strings.audio_account_password)) },
            text = { Text(stringResource(MR.strings.audio_account_password_changed)) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(MR.strings.action_done))
                }
            },
        )
        return
    }

    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    val longEnough = password.length >= MIN_AUTH_FIELD_LENGTH
    val matches = password == confirmation
    val canSubmit = !state.changingPassword && longEnough && matches

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.audio_account_password)) },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(MR.strings.audio_account_password_new)) },
                    singleLine = true,
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    // Registration signs the account in on the spot and there is no reset flow in
                    // the app, so a typo here is a password nobody knows. One tap to read it back
                    // is cheaper than any recovery.
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                                contentDescription = null,
                            )
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text(stringResource(MR.strings.audio_account_password_confirm)) },
                    singleLine = true,
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(MR.strings.audio_account_password_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (confirmation.isNotEmpty() && !matches) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(MR.strings.audio_account_password_mismatch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.actionError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (state.actionErrorFromServer && state.actionError.isNotBlank()) {
                            state.actionError
                        } else {
                            stringResource(MR.strings.audio_account_action_failed)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(password) },
                enabled = canSubmit,
            ) {
                if (state.changingPassword) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(MR.strings.audio_account_password))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
