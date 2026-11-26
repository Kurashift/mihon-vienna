package eu.kanade.presentation.reader.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.SeekDirection
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

enum class ChapterNavigatorType {
    HORIZONTAL_LTR,
    HORIZONTAL_RTL,
    VERTICAL_LEFT,
    VERTICAL_RIGHT,
    ;

    fun isHorizontal() = this == HORIZONTAL_LTR || this == HORIZONTAL_RTL
}

internal val chapterArrowSize = 48.dp
internal val sideRailEndPadding = 4.dp
internal val sideRailMinimumTrack = 72.dp
internal val sideRailTrackPadding = 11.dp

internal fun sideRailHeight(
    availableHeight: Int,
    heightFraction: Float,
    minimumHeight: Int,
    hasTrack: Boolean,
): Int {
    if (availableHeight < minimumHeight) return 0
    if (!hasTrack) return minimumHeight
    return (availableHeight * heightFraction.coerceIn(0f, 1f)).roundToInt().coerceAtLeast(minimumHeight)
}

@Composable
internal fun ChapterArrow(previous: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val icon = if (previous) Icons.Outlined.KeyboardDoubleArrowUp else Icons.Outlined.KeyboardDoubleArrowDown
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(chapterArrowSize),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(
                if (previous) MR.strings.action_previous_chapter else MR.strings.action_next_chapter,
            ),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
internal fun ChapterNavigator(
    sliderOnLeft: Boolean,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    onPageIndexChangeFinished: () -> Unit,
    backgroundColor: Color,
    railWidth: Dp,
    modifier: Modifier = Modifier,
) {
    var dragPage by remember { mutableStateOf<Int?>(null) }
    val textColor = MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = modifier.width(railWidth),
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
    ) {
        Column(
            modifier = Modifier.padding(vertical = sideRailEndPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ChapterArrow(previous = true, enabled = enabledPrevious, onClick = onPreviousChapter)
            if (totalPages > 1) {
                Text(
                    text = (dragPage ?: currentPage).toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    maxLines = 1,
                )
                ReaderPageSeekBar(
                    currentPage = currentPage,
                    totalPages = totalPages,
                    onPageIndexChange = { index ->
                        dragPage = index + 1
                        onPageIndexChange(index)
                    },
                    onPageIndexChangeFinished = {
                        dragPage = null
                        onPageIndexChangeFinished()
                    },
                    direction = if (sliderOnLeft) SeekDirection.LeftRail else SeekDirection.RightRail,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = sideRailTrackPadding),
                )
                Text(
                    text = totalPages.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    maxLines = 1,
                )
            }
            ChapterArrow(previous = false, enabled = enabledNext, onClick = onNextChapter)
        }
    }
}
