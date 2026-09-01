package eu.kanade.presentation.audio

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Switch for the floating subtitle window, drawn as a single glyph inside a ring.
 *
 * A single glyph is the way this toggle reads in desktop-lyrics apps, and the ring is what sets it
 * apart from the plain icon buttons beside it: the system CJK font cannot be rounded on its own,
 * and shipping a font file for one character is not worth what it costs the APK. It is outlined
 * while off and filled while on, so the fill — the strongest signal in the row — is what tells the
 * state apart. A ring that is filled either way reads as a control that is already on.
 *
 * The permission detour is part of the toggle rather than of its callers, so the player screen and
 * the reader's floating bar cannot drift apart: asking to turn the window on without the
 * draw-over-other-apps permission explains itself first, and coming back granted finishes what the
 * user asked for instead of leaving the switch off.
 *
 * [toggleSize] is the ring's diameter and is set to match whatever the surrounding buttons draw:
 * the player's row uses 32dp icons and the reader's floating bar 20dp ones, and a ring sized for
 * one would visibly bulge out of the other.
 */
@Composable
fun FloatingSubtitleToggle(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    toggleSize: Dp = SUBTITLE_TOGGLE_SIZE_DEFAULT,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showPermissionDialog by remember { mutableStateOf(false) }
    // Set when the system screen is opened, so coming back can finish what the user asked for
    // instead of leaving the switch off after they granted it. Saved because the system screen
    // is a separate task that can get this process restarted while it is in front.
    var awaitingPermission by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (
                event == Lifecycle.Event.ON_RESUME &&
                awaitingPermission &&
                Settings.canDrawOverlays(context)
            ) {
                awaitingPermission = false
                onToggle()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val label = stringResource(MR.strings.audio_subtitle)
    val stateLabel = stringResource(if (enabled) MR.strings.on else MR.strings.off)

    IconButton(
        onClick = {
            if (enabled || Settings.canDrawOverlays(context)) {
                onToggle()
            } else {
                showPermissionDialog = true
            }
        },
        modifier = modifier.semantics {
            // The glyph alone is too terse to announce, so the full word is read instead even
            // though only one character is drawn. The state goes with it: the fill is the only
            // thing that carries it on screen.
            contentDescription = label
            role = Role.Checkbox
            stateDescription = stateLabel
        },
    ) {
        // Filling and emptying is the whole state change, so it eases rather than snaps, which is
        // what made the swap look like a glitch.
        val fillColor by animateColorAsState(
            targetValue = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.Transparent
            },
            label = "subtitle toggle fill",
        )
        val contentColor by animateColorAsState(
            targetValue = if (enabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            label = "subtitle toggle content",
        )
        // outline rather than outlineVariant: one step up in contrast, which the ring needs to
        // hold its own as the only thing marking the off state once the fill is gone.
        // outlineVariant fades out against the dark surface.
        val ringColor by animateColorAsState(
            targetValue = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
            label = "subtitle toggle ring",
        )
        Box(
            modifier = Modifier
                .size(toggleSize)
                .border(width = 1.dp, color = ringColor, shape = CircleShape)
                .background(color = fillColor, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(MR.strings.audio_floating_subtitle_short),
                // Scaled off the ring, so shrinking it for a denser row shrinks the glyph with it
                // instead of leaving the character crowding the outline.
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = with(LocalDensity.current) { (toggleSize * GLYPH_SIZE_RATIO).toSp() },
                ),
                // Bold so a lone glyph carries as much weight as the icons around it, which are
                // solid shapes rather than text.
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
        }
    }

    if (showPermissionDialog) {
        OverlayPermissionDialog(
            onDismiss = { showPermissionDialog = false },
            onConfirm = {
                showPermissionDialog = false
                awaitingPermission = true
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:${context.packageName}".toUri(),
                    ),
                )
            },
        )
    }
}

/**
 * The draw-over-other-apps permission can only be granted on a system screen, and a service has no
 * way to start one, so the detour is explained here where there is an activity to leave from.
 */
@Composable
private fun OverlayPermissionDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.audio_floating_subtitle_permission_title)) },
        text = { Text(stringResource(MR.strings.audio_floating_subtitle_permission_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(MR.strings.action_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

/** Default diameter of the circle behind the single-glyph floating subtitle toggle. */
private val SUBTITLE_TOGGLE_SIZE_DEFAULT = 32.dp

/**
 * Share of the ring's diameter the glyph takes up. Half leaves the same breathing room at any
 * ring size, which is what keeps the 24dp ring in the reader bar from looking cramped.
 */
private const val GLYPH_SIZE_RATIO = 0.5f
