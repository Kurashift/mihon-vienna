package eu.kanade.presentation.browse.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import kotlin.math.abs

@Composable
internal fun BrowseSourceLazyColumn(
    fastScroll: Boolean,
    state: LazyListState,
    contentPadding: PaddingValues,
    onThumbDraggedChanged: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    if (fastScroll) {
        FastScrollLazyColumn(
            modifier = modifier,
            state = state,
            alwaysVisible = true,
            showEndMarker = true,
            onThumbDraggedChanged = onThumbDraggedChanged,
            contentPadding = contentPadding,
            content = content,
        )
    } else {
        LazyColumn(
            modifier = modifier,
            state = state,
            contentPadding = contentPadding,
            content = content,
        )
    }
}

@Composable
internal fun BrowseSourceLazyVerticalGrid(
    fastScroll: Boolean,
    state: LazyGridState,
    columns: GridCells,
    contentPadding: PaddingValues,
    onThumbDraggedChanged: ((Boolean) -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical,
    horizontalArrangement: Arrangement.Horizontal,
    modifier: Modifier = Modifier,
    // Grids add their own decorative spacing around the cards, which would otherwise drag the
    // scroller inward and leave it off-axis with the list and detail screens. Pass the container
    // padding the caller started from so all three land on the same line.
    scrollerEndPadding: Dp? = null,
    content: LazyGridScope.() -> Unit,
) {
    if (fastScroll) {
        FastScrollLazyVerticalGrid(
            modifier = modifier,
            state = state,
            columns = columns,
            alwaysVisible = true,
            showEndMarker = true,
            onThumbDraggedChanged = onThumbDraggedChanged,
            contentPadding = contentPadding,
            endContentPadding = scrollerEndPadding,
            verticalArrangement = verticalArrangement,
            horizontalArrangement = horizontalArrangement,
            content = content,
        )
    } else {
        LazyVerticalGrid(
            modifier = modifier,
            state = state,
            columns = columns,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            horizontalArrangement = horizontalArrangement,
            content = content,
        )
    }
}

private const val LOCATE_JUMP_FADE_OUT_MILLIS = 90
private const val LOCATE_JUMP_FADE_IN_MILLIS = 210

internal class BrowseSourceLocateTransition {
    val alpha = Animatable(1f)
    val translationY = Animatable(0f)
}

@Composable
internal fun rememberBrowseSourceLocateTransition(): BrowseSourceLocateTransition {
    return remember { BrowseSourceLocateTransition() }
}

internal fun Modifier.browseSourceLocateTransition(
    transition: BrowseSourceLocateTransition,
): Modifier = graphicsLayer {
    alpha = transition.alpha.value
    translationY = transition.translationY.value
}

private suspend fun BrowseSourceLocateTransition.reset() {
    alpha.snapTo(1f)
    translationY.snapTo(0f)
}

private suspend fun BrowseSourceLocateTransition.animateOut(
    direction: Float,
    slideDistancePx: Float,
) = coroutineScope {
    launch {
        alpha.animateTo(
            0f,
            tween(LOCATE_JUMP_FADE_OUT_MILLIS, easing = FastOutLinearInEasing),
        )
    }
    launch {
        translationY.animateTo(
            -direction * slideDistancePx,
            tween(LOCATE_JUMP_FADE_OUT_MILLIS, easing = FastOutLinearInEasing),
        )
    }
}

private suspend fun BrowseSourceLocateTransition.animateIn(
    direction: Float,
    slideDistancePx: Float,
) {
    translationY.snapTo(direction * slideDistancePx)
    coroutineScope {
        launch {
            alpha.animateTo(
                1f,
                tween(LOCATE_JUMP_FADE_IN_MILLIS, easing = LinearOutSlowInEasing),
            )
        }
        launch {
            translationY.animateTo(
                0f,
                tween(LOCATE_JUMP_FADE_IN_MILLIS, easing = LinearOutSlowInEasing),
            )
        }
    }
}

internal suspend fun LazyListState.smoothLocateToItem(
    index: Int,
    transition: BrowseSourceLocateTransition,
) {
    transition.reset()
    try {
        val totalItems = layoutInfo.totalItemsCount
        if (totalItems == 0) return
        val targetIndex = index.coerceIn(0, totalItems - 1)
        val currentIndex = firstVisibleItemIndex.coerceAtLeast(0)
        val distance = targetIndex - currentIndex
        val directScrollDistance = layoutInfo.visibleItemsInfo.size.coerceIn(16, 32)
        if (abs(distance) > directScrollDistance) {
            val direction = if (distance > 0) 1f else -1f
            val slideDistancePx = (layoutInfo.viewportSize.height * 0.09f).coerceIn(72f, 180f)
            transition.animateOut(direction, slideDistancePx)
            scrollToItem(targetIndex)
            withFrameNanos { }
            scrollTargetSlightlyAboveCenter(targetIndex, animated = false)
            withFrameNanos { }
            transition.animateIn(direction, slideDistancePx)
        } else {
            animateScrollToItem(targetIndex)
            withFrameNanos { }
            scrollTargetSlightlyAboveCenter(targetIndex, animated = true)
        }
    } finally {
        withContext(NonCancellable) { transition.reset() }
    }
}

internal suspend fun LazyGridState.smoothLocateToItem(
    index: Int,
    transition: BrowseSourceLocateTransition,
) {
    transition.reset()
    try {
        val totalItems = layoutInfo.totalItemsCount
        if (totalItems == 0) return
        val targetIndex = index.coerceIn(0, totalItems - 1)
        val currentIndex = firstVisibleItemIndex.coerceAtLeast(0)
        val distance = targetIndex - currentIndex
        val directScrollDistance = layoutInfo.visibleItemsInfo.size.coerceIn(16, 32)
        if (abs(distance) > directScrollDistance) {
            val direction = if (distance > 0) 1f else -1f
            val slideDistancePx = (layoutInfo.viewportSize.height * 0.09f).coerceIn(72f, 180f)
            transition.animateOut(direction, slideDistancePx)
            scrollToItem(targetIndex)
            withFrameNanos { }
            scrollTargetSlightlyAboveCenter(targetIndex, animated = false)
            withFrameNanos { }
            transition.animateIn(direction, slideDistancePx)
        } else {
            animateScrollToItem(targetIndex)
            withFrameNanos { }
            scrollTargetSlightlyAboveCenter(targetIndex, animated = true)
        }
    } finally {
        withContext(NonCancellable) { transition.reset() }
    }
}

/**
 * Nudges the target item so it sits slightly above the viewport center. Reading the target's
 * real size after [scrollToItem] keeps the offset accurate for items of any height.
 */
private suspend fun LazyListState.scrollTargetSlightlyAboveCenter(index: Int, animated: Boolean) {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewport = layoutInfo.viewportSize.height
    val centerOffset = (viewport - item.size) / 2f
    val targetOffset = (centerOffset - viewport * LOCATE_ABOVE_CENTER_FRACTION).coerceAtLeast(0f)
    val delta = item.offset - targetOffset
    if (abs(delta) > 1f) {
        if (animated) animateScrollBy(delta) else scrollBy(delta)
    }
}

private suspend fun LazyGridState.scrollTargetSlightlyAboveCenter(index: Int, animated: Boolean) {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewport = layoutInfo.viewportSize.height
    val centerOffset = (viewport - item.size.height) / 2f
    val targetOffset = (centerOffset - viewport * LOCATE_ABOVE_CENTER_FRACTION).coerceAtLeast(0f)
    val delta = item.offset.y - targetOffset
    if (abs(delta) > 1f) {
        if (animated) animateScrollBy(delta) else scrollBy(delta)
    }
}

private const val LOCATE_ABOVE_CENTER_FRACTION = 0.18f
