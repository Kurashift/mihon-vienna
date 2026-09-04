package tachiyomi.presentation.core.components

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastLastOrNull
import androidx.compose.ui.util.fastMaxBy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import tachiyomi.presentation.core.components.Scroller.STICKY_HEADER_KEY_PREFIX
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Draws vertical fast scroller to a lazy list
 *
 * Set key with [STICKY_HEADER_KEY_PREFIX] prefix to any sticky header item in the list.
 */
@Composable
fun VerticalFastScroller(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    thumbAllowed: () -> Boolean = { true },
    onScrollingChanged: ((Boolean) -> Unit)? = null,
    onThumbDraggedChanged: ((Boolean) -> Unit)? = null,
    alwaysVisible: Boolean = false,
    showEndMarker: Boolean = false,
    stickyThumb: Boolean = false,
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    topContentPadding: Dp = Dp.Hairline,
    bottomContentPadding: Dp = Dp.Hairline,
    endContentPadding: Dp = Dp.Hairline,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val contentPlaceable = subcompose("content", content).map { it.measure(constraints) }
        val contentHeight = contentPlaceable.fastMaxBy { it.height }?.height ?: 0
        val contentWidth = contentPlaceable.fastMaxBy { it.width }?.width ?: 0

        val scrollerConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val scrollerPlaceable = subcompose("scroller") {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.visibleItemsInfo.isEmpty() || layoutInfo.totalItemsCount == 0) return@subcompose

            val thumbTopPadding = with(LocalDensity.current) { topContentPadding.toPx() }
            var thumbOffsetY by remember(thumbTopPadding) { mutableFloatStateOf(thumbTopPadding) }
            val thumbAnchor = remember { ThumbAnchor() }
            var targetProgress by remember { mutableFloatStateOf(0f) }

            val dragInteractionSource = remember { MutableInteractionSource() }
            val isThumbDragged by dragInteractionSource.collectIsDraggedAsState()
            val scrolled = remember {
                MutableSharedFlow<Unit>(
                    extraBufferCapacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }

            // listState.isScrollInProgress occasionally flickers
            val scrollStateTracker = remember { MutableData(listState.isScrollInProgress) }
            val stableScrollInProgress = scrollStateTracker.value || listState.isScrollInProgress
            scrollStateTracker.value = listState.isScrollInProgress
            val anyScrollInProgress = stableScrollInProgress || isThumbDragged
            LaunchedEffect(anyScrollInProgress) {
                onScrollingChanged?.invoke(anyScrollInProgress)
            }
            LaunchedEffect(isThumbDragged) {
                onThumbDraggedChanged?.invoke(isThumbDragged)
            }

            val thumbBottomPadding = with(LocalDensity.current) { bottomContentPadding.toPx() }
            // A collapsing bottom bar hands its height back to the content box frame by frame.
            // Every measurement below is taken against the bar's resting height instead, so the
            // bar and the scroller animate independently and the thumb only ever tracks the
            // real scroll progress.
            val restingHeightPx = contentHeight.toFloat() -
                with(LocalDensity.current) { LocalFastScrollerBottomInset.current.toPx() }
            val heightPx = restingHeightPx -
                thumbTopPadding -
                thumbBottomPadding -
                listState.layoutInfo.afterContentPadding
            val thumbHeightPx = with(LocalDensity.current) { ThumbLength.toPx() }
            val trackHeightPx = heightPx - thumbHeightPx
            val endMarkerGapPx = with(LocalDensity.current) { EndMarkerGap.toPx() }
            val scrollHeightPx = restingHeightPx -
                listState.layoutInfo.beforeContentPadding -
                listState.layoutInfo.afterContentPadding -
                thumbBottomPadding

            val visibleItems = layoutInfo.visibleItemsInfo
            val topItem = visibleItems.fastFirstOrNull {
                it.bottom >= 0 &&
                    (it.key as? String)?.startsWith(STICKY_HEADER_KEY_PREFIX)?.not() ?: true
            } ?: visibleItems.first()
            val bottomItem = visibleItems.fastLastOrNull {
                it.top <= scrollHeightPx &&
                    (it.key as? String)?.startsWith(STICKY_HEADER_KEY_PREFIX)?.not() ?: true
            } ?: visibleItems.last()

            val topHiddenProportion = -1f * topItem.top / topItem.size.coerceAtLeast(1)
            val bottomHiddenProportion = (bottomItem.bottom - scrollHeightPx) / bottomItem.size.coerceAtLeast(1)
            val previousSections = topHiddenProportion + topItem.index
            val remainingSections = bottomHiddenProportion + (layoutInfo.totalItemsCount - (bottomItem.index + 1))
            val scrollableSections = previousSections + remainingSections
            val itemCountTracker = remember { MutableData(layoutInfo.totalItemsCount) }

            // Recalibrate the estimate only when the list content was really replaced. Watching
            // the measured scrollable span instead fires whenever the viewport merely resizes -
            // a collapsing bottom bar resizes it every frame - and recalibrating then drops the
            // high-water mark below the real span. [estimateConfidence] climbs again from there,
            // so [maxRemainingSections] is recomputed every frame from a span that is still
            // fluctuating with the bar, and the thumb jumps instead of tracking the scroll.
            //
            // The item count is immune to the viewport, but a trailing load indicator is added
            // and removed as pages stream in, so ignore single-item steps - recalibrating on
            // those drops the high-water mark while the list never changed. Hold off entirely
            // while the thumb is dragged: recalibrating mid-drag moves the mapping under the
            // finger. Freezing the tracker keeps the whole change visible on release, when the
            // correction is not felt.
            val previousItemCount = itemCountTracker.value
            if (!isThumbDragged) {
                itemCountTracker.value = layoutInfo.totalItemsCount
            }
            val layoutChanged = !isThumbDragged &&
                abs(layoutInfo.totalItemsCount - previousItemCount) > 1

            val estimateConfidence = remember { MutableData(remainingSections) }
            if (layoutChanged) estimateConfidence.value = remainingSections
            val maxRemainingSections = remember(estimateConfidence.value) { scrollableSections }
            estimateConfidence.value = max(estimateConfidence.value, remainingSections)

            if (maxRemainingSections < 0.5) return@subcompose

            // When thumb dragged
            LaunchedEffect(thumbOffsetY) {
                if (layoutInfo.totalItemsCount == 0 || !isThumbDragged) return@LaunchedEffect
                val thumbProportion = (thumbOffsetY - thumbTopPadding) / trackHeightPx
                if (thumbProportion <= 0.001f) {
                    estimateConfidence.value = -1f
                    listState.scrollToItem(index = 0, scrollOffset = 0)
                    scrolled.tryEmit(Unit)
                    return@LaunchedEffect
                }
                val scrollRemainingSections = (1f - thumbProportion) * maxRemainingSections
                val currentSection = layoutInfo.totalItemsCount - scrollRemainingSections
                val scrollSectionIndex = currentSection.toInt().coerceAtMost(layoutInfo.totalItemsCount)
                val expectedScrollItem = visibleItems.find { it.index == scrollSectionIndex } ?: visibleItems.first()
                val scrollRelativeOffset = expectedScrollItem.size * (currentSection - scrollSectionIndex)
                val scrollSectionOffset = (scrollRelativeOffset - scrollHeightPx).roundToInt()
                val scrollItemIndex = scrollSectionIndex.coerceIn(0, layoutInfo.totalItemsCount - 1)
                val scrollItemOffset = scrollSectionOffset + (scrollSectionIndex - scrollItemIndex) * bottomItem.size
                listState.scrollToItem(index = scrollItemIndex, scrollOffset = scrollItemOffset)
                scrolled.tryEmit(Unit)
            }

            // Where the list really sits on the track, before the anchor has its say.
            val measuredProgress = (1f - remainingSections / maxRemainingSections).coerceIn(0f, 1f)
            val thumbProgress = ((thumbOffsetY - thumbTopPadding) / trackHeightPx.coerceAtLeast(1f))
                .coerceIn(0f, 1f)

            if (isThumbDragged) {
                // Dragging re-anchors too: letting go has to leave the thumb where the finger
                // left it, not wherever the next page load thinks it belongs.
                targetProgress = thumbProgress
                thumbAnchor.snapped = false
                thumbAnchor.reset(measuredProgress, layoutInfo.totalItemsCount, thumbProgress)
            } else if (layoutInfo.totalItemsCount != 0) {
                targetProgress = thumbAnchor.resolve(
                    sticky = stickyThumb,
                    measured = measuredProgress,
                    itemCount = layoutInfo.totalItemsCount,
                    canScrollBackward = listState.canScrollBackward,
                    canScrollForward = listState.canScrollForward,
                    fallbackProgress = thumbProgress,
                )
            }

            val animatedProgress by animateFloatAsState(
                targetValue = targetProgress,
                animationSpec = tween(
                    durationMillis = if (thumbAnchor.snapped) ThumbSnapDurationMillis else 0,
                    easing = FastOutSlowInEasing,
                ),
                label = "listThumbProgress",
            )
            if (!isThumbDragged) {
                thumbOffsetY = trackHeightPx * animatedProgress + thumbTopPadding
            }

            // Keeps the thumb alight while the list moves. Recomposition alone must not emit,
            // or the bar would never fade out again.
            LaunchedEffect(listState.firstVisibleItemScrollOffset, layoutInfo.totalItemsCount) {
                if (stableScrollInProgress) scrolled.tryEmit(Unit)
            }

            // Thumb alpha
            val alpha = remember(alwaysVisible) { Animatable(if (alwaysVisible) 1f else 0f) }
            val isThumbVisible = alpha.value > 0f
            LaunchedEffect(scrolled, alpha, alwaysVisible) {
                if (alwaysVisible) {
                    alpha.snapTo(if (thumbAllowed()) 1f else 0f)
                    return@LaunchedEffect
                }
                scrolled
                    .sample(0.1.seconds)
                    .collectLatest {
                        if (thumbAllowed()) {
                            alpha.snapTo(1f)
                            delay(ScrollBarVisibilityDuration)
                            alpha.animateTo(0f, animationSpec = ImmediateFadeOutAnimationSpec)
                        } else {
                            alpha.animateTo(0f, animationSpec = ImmediateFadeOutAnimationSpec)
                        }
                    }
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(0, thumbOffsetY.roundToInt()) }
                    .then(
                        // Recompose opts
                        if (isThumbVisible && !listState.isScrollInProgress) {
                            Modifier.draggable(
                                interactionSource = dragInteractionSource,
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    val newOffsetY = thumbOffsetY + delta
                                    thumbOffsetY = newOffsetY.coerceIn(
                                        thumbTopPadding,
                                        thumbTopPadding + trackHeightPx,
                                    )
                                },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        // Exclude thumb from gesture area only when needed
                        if (isThumbVisible && !isThumbDragged && !listState.isScrollInProgress) {
                            Modifier.systemGestureExclusion()
                        } else {
                            Modifier
                        },
                    )
                    .height(ThumbLength)
                    .padding(start = ThumbStartPadding, end = ThumbEndMargin)
                    .padding(end = endContentPadding)
                    .width(ThumbThickness)
                    .alpha(alpha.value)
                    .background(color = thumbColor, shape = ThumbShape),
            )

            if (showEndMarker) {
                EndReachedTrackMarker(
                    scrollEvents = scrolled,
                    thumbColor = thumbColor,
                    modifier = Modifier
                        .offset {
                            IntOffset(0, (thumbTopPadding + heightPx + endMarkerGapPx).roundToInt())
                        }
                        .height(EndMarkerLength)
                        .padding(start = EndMarkerStartPadding, end = EndMarkerEndPadding)
                        .padding(end = endContentPadding)
                        .width(EndMarkerWidth),
                )
            }
        }.map { it.measure(scrollerConstraints) }
        val scrollerWidth = scrollerPlaceable.fastMaxBy { it.width }?.width ?: 0

        layout(contentWidth, contentHeight) {
            contentPlaceable.fastForEach {
                it.place(0, 0)
            }
            scrollerPlaceable.fastForEach {
                it.placeRelative(contentWidth - scrollerWidth, 0)
            }
        }
    }
}

/**
 * Short bar sitting at the bottom end of the track.
 *
 * The fast scroller keeps its resting length while a collapsing bottom bar animates away, so
 * the thumb stops short of the freed strip even when the list is scrolled all the way down.
 * Without a marker that strip just looks like missing track. It is shown while the thumb is
 * moving so the end of the track stays visible, and fades back out once scrolling settles.
 *
 * Visibility is driven by scroll pulses rather than a "is scrolling" flag: a flag read during
 * composition needs one more recomposition to flip back once scrolling stops, and that frame is
 * not guaranteed to happen - the marker would then stay on screen forever. Pulses keep coming
 * while the list moves and simply stop when it settles, so the fade always runs.
 */
@Composable
private fun EndReachedTrackMarker(
    scrollEvents: Flow<Unit>,
    thumbColor: Color,
    modifier: Modifier = Modifier,
) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(scrollEvents, alpha) {
        scrollEvents
            .sample(0.1.seconds)
            .collectLatest {
                alpha.snapTo(EndMarkerMaxAlpha)
                delay(EndMarkerHoldDuration)
                alpha.animateTo(0f, animationSpec = EndMarkerFadeOutAnimationSpec)
            }
    }
    Box(
        modifier = modifier
            .alpha(alpha.value)
            .background(color = thumbColor, shape = EndMarkerShape),
    )
}

@Composable
private fun rememberColumnWidthSums(
    columns: GridCells,
    horizontalArrangement: Arrangement.Horizontal,
    contentPadding: PaddingValues,
) = remember<Density.(Constraints) -> List<Int>>(
    columns,
    horizontalArrangement,
    contentPadding,
) {
    { constraints ->
        require(constraints.maxWidth != Constraints.Infinity) {
            "LazyVerticalGrid's width should be bound by parent"
        }
        val horizontalPadding = contentPadding.calculateStartPadding(LayoutDirection.Ltr) +
            contentPadding.calculateEndPadding(LayoutDirection.Ltr)
        val gridWidth = constraints.maxWidth - horizontalPadding.roundToPx()
        with(columns) {
            calculateCrossAxisCellSizes(
                gridWidth,
                horizontalArrangement.spacing.roundToPx(),
            ).toMutableList().apply {
                for (i in 1..<size) {
                    this[i] += this[i - 1]
                }
            }
        }
    }
}

/*
    VerticalGridFastScroller was written with a regularity assumption, so it is slightly inaccurate for layouts with
    varying row sizes.
 */
// TODO: Ideally rewrite VerticalGridFastScroller to use similar logic as VerticalFastScroller
@Composable
fun VerticalGridFastScroller(
    state: LazyGridState,
    columns: GridCells,
    arrangement: Arrangement.Horizontal,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    thumbAllowed: () -> Boolean = { true },
    onScrollingChanged: ((Boolean) -> Unit)? = null,
    onThumbDraggedChanged: ((Boolean) -> Unit)? = null,
    alwaysVisible: Boolean = false,
    showEndMarker: Boolean = false,
    stickyThumb: Boolean = false,
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    topContentPadding: Dp = Dp.Hairline,
    bottomContentPadding: Dp = Dp.Hairline,
    endContentPadding: Dp = Dp.Hairline,
    content: @Composable () -> Unit,
) {
    val slotSizesSums = rememberColumnWidthSums(
        columns = columns,
        horizontalArrangement = arrangement,
        contentPadding = contentPadding,
    )

    SubcomposeLayout(modifier = modifier) { constraints ->
        val contentPlaceable = subcompose("content", content).map { it.measure(constraints) }
        val contentHeight = contentPlaceable.fastMaxBy { it.height }?.height ?: 0
        val contentWidth = contentPlaceable.fastMaxBy { it.width }?.width ?: 0

        val scrollerConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val scrollerPlaceable = subcompose("scroller") {
            val layoutInfo = state.layoutInfo
            val showScroller = remember(columns, layoutInfo.totalItemsCount) {
                layoutInfo.visibleItemsInfo.size < layoutInfo.totalItemsCount
            }
            if (!showScroller) return@subcompose
            val thumbTopPadding = with(LocalDensity.current) { topContentPadding.toPx() }
            var thumbOffsetY by remember(thumbTopPadding) { mutableFloatStateOf(thumbTopPadding) }
            val thumbAnchor = remember { ThumbAnchor() }
            var targetProgress by remember { mutableFloatStateOf(0f) }

            val dragInteractionSource = remember { MutableInteractionSource() }
            val isThumbDragged by dragInteractionSource.collectIsDraggedAsState()
            val scrolled = remember {
                MutableSharedFlow<Unit>(
                    extraBufferCapacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }
            val scrollStateTracker = remember { MutableData(state.isScrollInProgress) }
            val stableScrollInProgress = scrollStateTracker.value || state.isScrollInProgress
            scrollStateTracker.value = state.isScrollInProgress
            val anyScrollInProgress = stableScrollInProgress || isThumbDragged
            LaunchedEffect(anyScrollInProgress) {
                onScrollingChanged?.invoke(anyScrollInProgress)
            }
            LaunchedEffect(isThumbDragged) {
                onThumbDraggedChanged?.invoke(isThumbDragged)
            }

            val thumbBottomPadding = with(LocalDensity.current) { bottomContentPadding.toPx() }
            // Same resting-height trick as the list variant: the grid's own scroll range and
            // offset are derived from the visible items, so pinning the viewport keeps the
            // track and the thumb still while the bar animates.
            val restingHeightPx = contentHeight.toFloat() -
                with(LocalDensity.current) { LocalFastScrollerBottomInset.current.toPx() }
            // The grid is handed its bottom padding through [bottomContentPadding], which is the
            // very same span the lazy grid reports as afterContentPadding. Subtracting both
            // shortens the track by a whole bottom padding, so the thumb stops short of the end
            // marker and never reaches the divider above the navigation bar. The list variant is
            // not handed [bottomContentPadding] and keeps measuring its bottom from
            // afterContentPadding.
            val heightPx = restingHeightPx -
                thumbTopPadding -
                thumbBottomPadding
            val thumbHeightPx = with(LocalDensity.current) { ThumbLength.toPx() }
            val trackHeightPx = heightPx - thumbHeightPx
            val endMarkerGapPx = with(LocalDensity.current) { EndMarkerGap.toPx() }

            val columnCount = remember(columns) { slotSizesSums(constraints).size.coerceAtLeast(1) }

            // When thumb dragged
            LaunchedEffect(thumbOffsetY) {
                if (layoutInfo.totalItemsCount == 0 || !isThumbDragged) return@LaunchedEffect
                val visibleItems = state.layoutInfo.visibleItemsInfo
                if (visibleItems.isEmpty()) return@LaunchedEffect
                val startChild = visibleItems.first()
                val endChild = visibleItems.last()
                val laidOutArea = (endChild.offset.y + endChild.size.height) - startChild.offset.y
                val laidOutRows = 1 + abs(endChild.index - startChild.index) / columnCount
                val avgSizePerRow = laidOutArea.toFloat() / laidOutRows

                // Recomputed live, never cached: see the matching note below.
                val scrollRange = computeGridScrollRange(state = state, columnCount = columnCount)
                val scrollRatio = (thumbOffsetY - thumbTopPadding) / trackHeightPx
                val scrollAmt = scrollRatio * (scrollRange.toFloat() - heightPx).coerceAtLeast(1f)
                val rowNumber = (scrollAmt / avgSizePerRow).toInt()
                val rowOffset = scrollAmt - rowNumber * avgSizePerRow

                state.scrollToItem(index = columnCount * rowNumber, scrollOffset = rowOffset.roundToInt())
                scrolled.tryEmit(Unit)
            }

            // Where the list really sits on the track, before the anchor has its say.
            val measuredProgress = if (layoutInfo.totalItemsCount == 0) {
                0f
            } else {
                // Both sides have to come from the same measurement. Caching the range against
                // the column count froze it at whatever rows happened to be visible when it was
                // built, while the offset kept tracking the live rows; pages streaming in swap
                // filled items for loading placeholders, which are not the same height, so the
                // stale range no longer matches the live offset and the thumb stops short of the
                // end. Sharing one average row height cancels it out of the ratio.
                val scrollOffset = computeGridScrollOffset(state = state, columnCount = columnCount)
                val scrollRange = computeGridScrollRange(state = state, columnCount = columnCount)
                /*
                    LazyGridItemInfo doesn't always give the accurate height of the object, so we clamp the proportion
                    at 1 to ensure that there are no issues due to this -- ideally we would correctly compute the value
                 */
                val extraScrollRange = (scrollRange.toFloat() - heightPx).coerceAtLeast(1f)
                (scrollOffset.toFloat() / extraScrollRange).coerceIn(0f, 1f)
            }
            val thumbProgress = ((thumbOffsetY - thumbTopPadding) / trackHeightPx.coerceAtLeast(1f))
                .coerceIn(0f, 1f)

            if (isThumbDragged) {
                // Dragging re-anchors too: letting go has to leave the thumb where the finger
                // left it, not wherever the next page load thinks it belongs.
                targetProgress = thumbProgress
                thumbAnchor.snapped = false
                thumbAnchor.reset(measuredProgress, layoutInfo.totalItemsCount, thumbProgress)
            } else if (layoutInfo.totalItemsCount != 0) {
                targetProgress = thumbAnchor.resolve(
                    sticky = stickyThumb,
                    measured = measuredProgress,
                    itemCount = layoutInfo.totalItemsCount,
                    canScrollBackward = state.canScrollBackward,
                    canScrollForward = state.canScrollForward,
                    fallbackProgress = thumbProgress,
                )
            }

            val animatedProgress by animateFloatAsState(
                targetValue = targetProgress,
                animationSpec = tween(
                    durationMillis = if (thumbAnchor.snapped) ThumbSnapDurationMillis else 0,
                    easing = FastOutSlowInEasing,
                ),
                label = "gridThumbProgress",
            )
            if (!isThumbDragged) {
                thumbOffsetY = trackHeightPx * animatedProgress + thumbTopPadding
            }

            // Keeps the thumb alight while the list moves. Recomposition alone must not emit,
            // or the bar would never fade out again.
            LaunchedEffect(state.firstVisibleItemScrollOffset, layoutInfo.totalItemsCount) {
                if (layoutInfo.totalItemsCount == 0 || isThumbDragged) return@LaunchedEffect
                scrolled.tryEmit(Unit)
            }

            // Thumb alpha
            val alpha = remember(alwaysVisible) { Animatable(if (alwaysVisible) 1f else 0f) }
            val isThumbVisible = alpha.value > 0f
            LaunchedEffect(scrolled, alpha, alwaysVisible) {
                if (alwaysVisible) {
                    alpha.snapTo(if (thumbAllowed()) 1f else 0f)
                    return@LaunchedEffect
                }
                scrolled
                    .sample(0.1.seconds)
                    .collectLatest {
                        if (thumbAllowed()) {
                            alpha.snapTo(1f)
                            delay(ScrollBarVisibilityDuration)
                            alpha.animateTo(0f, animationSpec = ImmediateFadeOutAnimationSpec)
                        } else {
                            alpha.animateTo(0f, animationSpec = ImmediateFadeOutAnimationSpec)
                        }
                    }
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(0, thumbOffsetY.roundToInt()) }
                    .then(
                        // Recompose opts
                        if (isThumbVisible && !state.isScrollInProgress) {
                            Modifier.draggable(
                                interactionSource = dragInteractionSource,
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    val newOffsetY = thumbOffsetY + delta
                                    thumbOffsetY = newOffsetY.coerceIn(
                                        thumbTopPadding,
                                        thumbTopPadding + trackHeightPx,
                                    )
                                },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        // Exclude thumb from gesture area only when needed
                        if (isThumbVisible && !isThumbDragged && !state.isScrollInProgress) {
                            Modifier.systemGestureExclusion()
                        } else {
                            Modifier
                        },
                    )
                    .height(ThumbLength)
                    .padding(start = ThumbStartPadding, end = ThumbEndMargin)
                    .padding(end = endContentPadding)
                    .width(ThumbThickness)
                    .alpha(alpha.value)
                    .background(color = thumbColor, shape = ThumbShape),
            )

            if (showEndMarker) {
                EndReachedTrackMarker(
                    scrollEvents = scrolled,
                    thumbColor = thumbColor,
                    modifier = Modifier
                        .offset {
                            IntOffset(0, (thumbTopPadding + heightPx + endMarkerGapPx).roundToInt())
                        }
                        .height(EndMarkerLength)
                        .padding(start = EndMarkerStartPadding, end = EndMarkerEndPadding)
                        .padding(end = endContentPadding)
                        .width(EndMarkerWidth),
                )
            }
        }.map { it.measure(scrollerConstraints) }
        val scrollerWidth = scrollerPlaceable.fastMaxBy { it.width }?.width ?: 0

        layout(contentWidth, contentHeight) {
            contentPlaceable.fastForEach {
                it.place(0, 0)
            }
            scrollerPlaceable.fastForEach {
                it.placeRelative(contentWidth - scrollerWidth, 0)
            }
        }
    }
}

// TODO: not sure why abs corrections are in the following functions; these can probably be removed

private fun computeGridScrollOffset(state: LazyGridState, columnCount: Int): Int {
    // The count can be non-zero while nothing is laid out yet (mid-swap, or a page dropped
    // behind the viewport); these are now read live on every scroll event, so guard them.
    if (state.layoutInfo.totalItemsCount == 0) return 0
    val visibleItems = state.layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return 0
    val startChild = visibleItems.first()
    val endChild = visibleItems.last()
    val laidOutArea = (endChild.offset.y + endChild.size.height) - startChild.offset.y
    val laidOutRows = 1 + abs(endChild.index - startChild.index) / columnCount
    val avgSizePerRow = laidOutArea.toFloat() / laidOutRows

    val rowsBefore = min(startChild.index, endChild.index).coerceAtLeast(0) / columnCount
    return (rowsBefore * avgSizePerRow - startChild.offset.y).roundToInt()
}

private fun computeGridScrollRange(state: LazyGridState, columnCount: Int): Int {
    if (state.layoutInfo.totalItemsCount == 0) return 0
    val visibleItems = state.layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return 0
    val startChild = visibleItems.first()
    val endChild = visibleItems.last()
    val laidOutArea = (endChild.offset.y + endChild.size.height) - startChild.offset.y
    val laidOutRows = 1 + abs(endChild.index - startChild.index) / columnCount
    val avgSizePerRow = laidOutArea.toFloat() / laidOutRows

    val totalRows = 1 + (state.layoutInfo.totalItemsCount - 1) / columnCount
    val endSpacing = avgSizePerRow - endChild.size.height
    return (endSpacing + (laidOutArea.toFloat() / laidOutRows) * totalRows).roundToInt()
}

private class MutableData<T>(var value: T)

/**
 * Holds the thumb still while a paged list grows underneath it.
 *
 * Mapping the thumb straight onto `scrolled / total` walks it backwards every time a page
 * lands: the denominator grows while the scrolled distance stays put, so a thumb parked at
 * the middle of the first page is shoved back to a quarter of the track the moment the
 * second page arrives. The jump is worst right at the top, where a single page doubles the
 * total, and shrinks as the list grows - which is why it reads as random rather than as
 * "more content arrived".
 *
 * Holding the thumb where it is says what actually happened: the content came to you, you
 * did not move. The distance held back is paid back at the ends, where the real scroll
 * position is known exactly and snapping onto the end of the track costs nothing.
 */
private class ThumbAnchor {
    var initialized = false
    var itemCount = 0
    var measured = 0f
    var progress = 0f
    var snapped = false

    fun reset(measured: Float, itemCount: Int, progress: Float) {
        this.measured = measured
        this.itemCount = itemCount
        this.progress = progress
        this.initialized = true
    }
}

/**
 * Item-count steps the pager makes on its own: a trailing load indicator appearing or
 * leaving. Anything larger is the list really gaining or losing entries.
 */
private const val THUMB_ANCHOR_COUNT_TOLERANCE = 1

private fun ThumbAnchor.resolve(
    sticky: Boolean,
    measured: Float,
    itemCount: Int,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    fallbackProgress: Float,
): Float {
    snapped = false
    if (!sticky || !initialized) {
        reset(measured, itemCount, measured)
        return measured
    }
    // The list was swapped rather than appended to - a refresh, or a different listing.
    if (itemCount < this.itemCount - THUMB_ANCHOR_COUNT_TOLERANCE) {
        reset(measured, itemCount, measured)
        return measured
    }
    // A page landed: hold the thumb exactly where it stands and re-anchor from there.
    if (itemCount > this.itemCount + THUMB_ANCHOR_COUNT_TOLERANCE) {
        // Unless it is parked on the end of the track. There the reader asked for the end of
        // the list and there is still more to come, so holding still would claim the list is
        // finished and leave the thumb stuck on the end with nowhere left to drag. Fall back
        // to where the list really is - visibly, so it reads as "still loading" - and let the
        // next drag carry it further down.
        if (this.progress >= 1f) {
            snapped = true
            reset(measured, itemCount, measured)
            return measured
        }
        reset(measured, itemCount, fallbackProgress)
        return fallbackProgress
    }
    // The ends are the only places the real position is known, so that is where the distance
    // held back gets paid back.
    if (!canScrollBackward) {
        snapped = true
        reset(measured, itemCount, 0f)
        return 0f
    }
    if (!canScrollForward) {
        snapped = true
        reset(measured, itemCount, 1f)
        return 1f
    }
    // Ordinary scrolling: advance by however far the list actually moved since the anchor.
    val moved = (progress + (measured - this.measured)).coerceIn(0f, 1f)
    reset(measured, itemCount, moved)
    return moved
}

object Scroller {
    const val STICKY_HEADER_KEY_PREFIX = "sticky:"
}

/**
 * Bottom space a collapsing bottom bar has handed back to the content at this moment.
 *
 * A bottom bar that collapses on scroll is a scaffold animation, so the height the scroller
 * is laid out with grows frame by frame as the bar leaves. The track would then stretch along
 * with it: the thumb drifts away from the scroll progress it is meant to represent, and
 * dragging it maps to a different offset depending on how far the bar has animated. Giving
 * back exactly what the bar released keeps the track at the bar's resting height, so the bar
 * and the scroller animate independently while the content keeps filling the freed space.
 *
 * Provided by the host scaffold. Screens without a collapsing bottom bar leave it at zero.
 */
val LocalFastScrollerBottomInset: ProvidableCompositionLocal<Dp> = staticCompositionLocalOf { 0.dp }

private val ThumbLength = 48.dp
private val ThumbThickness = 12.dp
// Gap between the thumb and the edge the content itself stops at. The scroller box is laid out
// flush against the content's end, so this - not the start padding - is what decides how close
// the bar sits to the screen edge: a bar's trailing gap always equals its own end padding.
private val ThumbEndMargin = 2.dp
// Width of the strip that grabs the thumb, held constant. Moving the bar closer to the edge only
// converts outer margin into grab room on its leading side ([ThumbStartPadding] absorbs the
// difference), so retuning the margin never shrinks the touch target.
private val ThumbTouchWidth = 40.dp
private val ThumbStartPadding = ThumbTouchWidth - ThumbThickness - ThumbEndMargin
private val ThumbShape = RoundedCornerShape(ThumbThickness / 2)
// Only ever spent on the end-of-track snap: the thumb catching up with a distance it held
// back while pages were loading. Everything else tracks the finger or the scroll instantly.
private const val ThumbSnapDurationMillis = 180
private val ScrollBarVisibilityDuration = 2.seconds
private val ImmediateFadeOutAnimationSpec = tween<Float>(
    durationMillis = ViewConfiguration.getScrollBarFadeDuration(),
)
private val EndMarkerLength = 3.dp
// Spans exactly the thumb's width and both of its edges. Insetting the leading side made the
// marker a narrower stub that stopped short of where the thumb starts, which read as the two
// bars being out of line; matching the thumb on both sides reads as one bar with its last
// segment left behind at the end of the track.
private val EndMarkerWidth = ThumbThickness
private val EndMarkerStartPadding = ThumbStartPadding
private val EndMarkerEndPadding = ThumbEndMargin
private val EndMarkerGap = 2.dp
private val EndMarkerShape = RoundedCornerShape(EndMarkerLength / 2)
// Sits at a fraction of the thumb's opacity: present enough to close off the track, quiet
// enough not to compete with the thumb itself.
private val EndMarkerMaxAlpha = 0.4f
// Grace period after the last scroll pulse. Flings keep emitting while they coast, so this
// only starts counting once the list has really stopped.
private val EndMarkerHoldDuration = 500.milliseconds
private val EndMarkerFadeOutAnimationSpec = tween<Float>(
    durationMillis = 280,
    easing = LinearOutSlowInEasing,
)

private val LazyListItemInfo.top: Int
    get() = offset

private val LazyListItemInfo.bottom: Int
    get() = offset + size
