package eu.kanade.presentation.reader.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.SeekDirection
import eu.kanade.presentation.components.seekBarGestures
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

@Composable
internal fun ReaderPageSeekBar(
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    onPageIndexChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    direction: SeekDirection = SeekDirection.Horizontal,
) {
    val latestPage by rememberUpdatedState(currentPage)
    val latestTotal by rememberUpdatedState(totalPages)
    val latestOnChange by rememberUpdatedState(onPageIndexChange)
    val latestOnFinished by rememberUpdatedState(onPageIndexChangeFinished)
    var fineSeek by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    if (totalPages <= 1) {
        Spacer(modifier)
        return
    }

    val progressColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
    val vertical = direction != SeekDirection.Horizontal
    if (vertical) {
        val haptic = LocalHapticFeedback.current
        var lastPage by remember { mutableIntStateOf(currentPage) }
        val dragging = dragFraction != null
        LaunchedEffect(currentPage, dragging) {
            if (dragging && lastPage != currentPage) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            lastPage = currentPage
        }
    }
    val description = stringResource(MR.strings.chapter_progress, currentPage)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(
                    currentPage.coerceIn(1, totalPages).toFloat(),
                    1f..totalPages.toFloat(),
                    steps = totalPages - 2,
                )
                setProgress { target ->
                    val page = target.roundToInt().coerceIn(1, totalPages)
                    if (page == currentPage) {
                        false
                    } else {
                        onPageIndexChange(page - 1)
                        onPageIndexChangeFinished()
                        true
                    }
                }
            }
            .seekBarGestures(
                thumbFraction = { latestPage.toFloat() / latestTotal.coerceAtLeast(1) },
                valueAt = { fraction ->
                    val total = latestTotal.coerceAtLeast(1)
                    (fraction * total).roundToInt().coerceIn(1, total)
                },
                onValue = { page -> latestOnChange(page - 1) },
                onFinished = { latestOnFinished() },
                onFineSeekChange = { fineSeek = it },
                onDragFraction = { dragFraction = it },
                direction = direction,
                finishOnCancel = true,
            ),
    ) {
        val fraction = dragFraction ?: (currentPage.toFloat() / totalPages).coerceIn(0f, 1f)
        Canvas(Modifier.fillMaxSize()) {
            val thickness = 4.dp.toPx()
            val corner = CornerRadius(thickness / 2)
            val middle = if (vertical) size.width / 2 else size.height / 2
            val length = if (vertical) size.height else size.width
            val head = length * fraction
            val start = if (vertical) Offset(middle - thickness / 2, 0f) else Offset(0f, middle - thickness / 2)
            drawRoundRect(
                color = trackColor,
                topLeft = start,
                size = if (vertical) Size(thickness, length) else Size(length, thickness),
                cornerRadius = corner,
            )
            if (head > 0f) {
                drawRoundRect(
                    color = progressColor,
                    topLeft = start,
                    size = if (vertical) Size(thickness, head) else Size(head, thickness),
                    cornerRadius = corner,
                )
                val center = if (vertical) Offset(middle, head) else Offset(head, middle)
                if (fineSeek) {
                    drawCircle(progressColor.copy(alpha = 0.25f), radius = 11.dp.toPx(), center = center)
                }
                drawCircle(progressColor, radius = (if (fineSeek) 7.dp else 4.5.dp).toPx(), center = center)
            }
        }
    }
}
