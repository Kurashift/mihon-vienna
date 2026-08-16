package tachiyomi.presentation.core.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

@Composable
fun FastScrollLazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    thumbAllowed: () -> Boolean = { true },
    onScrollingChanged: ((Boolean) -> Unit)? = null,
    onThumbDraggedChanged: ((Boolean) -> Unit)? = null,
    alwaysVisible: Boolean = false,
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    userScrollEnabled: Boolean = true,
    content: LazyGridScope.() -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    VerticalGridFastScroller(
        state = state,
        columns = columns,
        arrangement = horizontalArrangement,
        contentPadding = contentPadding,
        modifier = modifier,
        thumbAllowed = thumbAllowed,
        onScrollingChanged = onScrollingChanged,
        onThumbDraggedChanged = onThumbDraggedChanged,
        alwaysVisible = alwaysVisible,
        thumbColor = thumbColor,
        topContentPadding = contentPadding.calculateTopPadding(),
        bottomContentPadding = contentPadding.calculateBottomPadding(),
        endContentPadding = contentPadding.calculateEndPadding(layoutDirection),
    ) {
        LazyVerticalGrid(
            columns = columns,
            state = state,
            contentPadding = contentPadding,
            reverseLayout = reverseLayout,
            verticalArrangement = verticalArrangement,
            horizontalArrangement = horizontalArrangement,
            userScrollEnabled = userScrollEnabled,
            content = content,
        )
    }
}
