package eu.kanade.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.tachiyomi.ui.reader.ReaderPageDeletionService
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.PillSnackbar
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun ReaderPageDeletionHost(modifier: Modifier = Modifier) {
    val service = remember { Injekt.get<ReaderPageDeletionService>() }
    val pending by service.pendingSnackbar.collectAsStateWithLifecycle()

    ReaderPageDeletionSnackbar(
        count = pending?.count ?: 0,
        onUndo = service::undoDeferredDeletion,
        onSwipeDismiss = service::commitDeferredDeletion,
        onPressedChange = service::setDeferredDeletionPressed,
        modifier = modifier,
    )
}

@Composable
fun ReaderPageDeletionSnackbar(
    count: Int,
    onUndo: () -> Unit,
    onSwipeDismiss: () -> Unit,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = count > 0,
            enter = fadeIn(tween(120)) + slideInVertically(tween(160)) { it / 2 },
            exit = fadeOut(tween(100)) + slideOutVertically(tween(140)) { it / 3 },
        ) {
            PillSnackbar(
                message = stringResource(MR.strings.reader_pages_deleted, count),
                actionLabel = stringResource(MR.strings.action_undo),
                onAction = onUndo,
                onDismiss = onSwipeDismiss,
                onPressedChange = onPressedChange,
            )
        }
    }
}
