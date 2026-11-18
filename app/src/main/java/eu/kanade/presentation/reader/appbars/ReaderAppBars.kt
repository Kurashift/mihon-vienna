package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.reader.components.ChapterArrow
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.presentation.reader.components.ReaderPageSeekBar
import eu.kanade.presentation.reader.components.chapterArrowSize
import eu.kanade.presentation.reader.components.sideRailEndPadding
import eu.kanade.presentation.reader.components.sideRailHeight
import eu.kanade.presentation.reader.components.sideRailMinimumTrack
import eu.kanade.presentation.reader.components.sideRailTrackPadding
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

private val readerBarsSlideAnimationSpec = tween<IntOffset>(200)
private val readerBarsFadeAnimationSpec = tween<Float>(150)

@Composable
fun ReaderAppBars(
    visible: Boolean,
    audioControls: (@Composable () -> Unit)? = null,

    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    goodDoujinMarked: Boolean,
    onToggleGoodDoujin: (() -> Unit)?,
    onOpenManga: (() -> Unit)?,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,

    chapterNavigatorType: ChapterNavigatorType,
    verticalNavigatorHeight: Float,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    onPageIndexChangeFinished: () -> Unit,

    chapterPadOffsetFraction: Float,
    onChapterPadOffsetFractionChange: (Float) -> Unit,
    onChapterPadOffsetCommitted: () -> Unit,

    audioAvailable: Boolean,
    audioVisible: Boolean,
    onClickAudio: () -> Unit,
    onClickSettings: () -> Unit,
) {
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)
    val wantsSideRail = !chapterNavigatorType.isHorizontal()
    var measuredRailSpace by remember { mutableStateOf(IntSize.Zero) }
    var reservedFooterHeight by remember { mutableStateOf(0) }
    val hasTrack = totalPages > 1
    val labelSize = if (wantsSideRail && hasTrack) {
        rememberTextMeasurer().measure(
            "8".repeat(totalPages.toString().length),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        ).size
    } else {
        null
    }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val navigationInsets = WindowInsets.navigationBars
    val railHeight = with(density) {
        val buttons = 2 * (chapterArrowSize.roundToPx() + sideRailEndPadding.roundToPx())
        val track = if (labelSize != null) {
            sideRailMinimumTrack.roundToPx() + 2 * (labelSize.height + sideRailTrackPadding.roundToPx())
        } else {
            0
        }
        sideRailHeight(
            measuredRailSpace.height + reservedFooterHeight,
            verticalNavigatorHeight,
            buttons + track,
            hasTrack,
        ).toDp()
    }
    val railWidth = with(density) {
        ((labelSize?.width ?: 0).toDp() + 8.dp).coerceAtLeast(chapterArrowSize)
    }
    val showSideRail = wantsSideRail && railHeight > 0.dp
    val footerPadding = MaterialTheme.padding.small
    val splitFallback = wantsSideRail && !showSideRail && measuredRailSpace.width > 0 && with(density) {
        val insets = navigationInsets.getLeft(density, layoutDirection) +
            navigationInsets.getRight(density, layoutDirection)
        val tools = if (audioAvailable) 80.dp else 40.dp
        val fixedWidth = (chapterArrowSize * 2 + tools + footerPadding * 2 + 40.dp + 48.dp).roundToPx()
        measuredRailSpace.width < insets + fixedWidth + (labelSize?.width ?: 0) * 2
    }

    Column(modifier = Modifier.fillMaxHeight()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(readerBarsSlideAnimationSpec) { -it } + fadeIn(readerBarsFadeAnimationSpec),
            exit = slideOutVertically(readerBarsSlideAnimationSpec) { -it } + fadeOut(readerBarsFadeAnimationSpec),
        ) {
            ReaderTopBar(
                modifier = Modifier
                    .background(backgroundColor),
                mangaTitle = mangaTitle,
                chapterTitle = chapterTitle,
                navigateUp = navigateUp,
                goodDoujinMarked = goodDoujinMarked,
                onToggleGoodDoujin = onToggleGoodDoujin,
                onOpenManga = onOpenManga,
                onOpenInWebView = onOpenInWebView,
                onOpenInBrowser = onOpenInBrowser,
                onShare = onShare,
            )
        }

        if (wantsSideRail) {
            val sliderOnLeft = chapterNavigatorType == ChapterNavigatorType.VERTICAL_LEFT
            // Measure the space remaining after both bars (including the audio volume panel).
            // The wrapped footer's extra row is measured separately and added back to this budget.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { measuredRailSpace = it }
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = MaterialTheme.padding.small),
            ) {
                if (showSideRail) {
                    this@Column.AnimatedVisibility(
                        visible = visible,
                        modifier = Modifier.align(
                            if (sliderOnLeft) AbsoluteAlignment.BottomLeft else AbsoluteAlignment.BottomRight,
                        ),
                        enter = slideInHorizontally(readerBarsSlideAnimationSpec) { if (sliderOnLeft) -it else it } +
                            fadeIn(readerBarsFadeAnimationSpec),
                        exit = slideOutHorizontally(readerBarsSlideAnimationSpec) { if (sliderOnLeft) -it else it } +
                            fadeOut(readerBarsFadeAnimationSpec),
                    ) {
                        ChapterNavigator(
                            sliderOnLeft = sliderOnLeft,
                            modifier = Modifier.height(railHeight),
                            onNextChapter = onNextChapter,
                            enabledNext = enabledNext,
                            onPreviousChapter = onPreviousChapter,
                            enabledPrevious = enabledPrevious,
                            currentPage = currentPage,
                            totalPages = totalPages,
                            onPageIndexChange = onPageIndexChange,
                            onPageIndexChangeFinished = onPageIndexChangeFinished,
                            backgroundColor = backgroundColor,
                            railWidth = railWidth,
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(readerBarsSlideAnimationSpec) { it } + fadeIn(readerBarsFadeAnimationSpec),
            exit = slideOutVertically(readerBarsSlideAnimationSpec) { it } + fadeOut(readerBarsFadeAnimationSpec),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                // Order matters here. The column is bottom-anchored, so whatever sits closest to
                // the seek bar keeps that slot. The audio bar belongs there — it was there before
                // the chapter pad existed — so the pad goes above it and is pushed up by it,
                // taking its height out of the page area instead of displacing the audio bar.
                //
                // The pad lives in this column rather than as a floating overlay: as an overlay it
                // was drawn on top of the audio bar and covered its controls, which read as the
                // audio bar disappearing.
                if (!wantsSideRail) {
                    ChapterPadRow(
                        isRtl = chapterNavigatorType == ChapterNavigatorType.HORIZONTAL_RTL,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        offsetFraction = chapterPadOffsetFraction,
                        onOffsetFractionChange = onChapterPadOffsetFractionChange,
                        onOffsetCommitted = onChapterPadOffsetCommitted,
                    )
                }
                audioControls?.invoke()
                val fallbackArrows: (@Composable () -> Unit)? = if (wantsSideRail && !showSideRail) {
                    {
                        Row {
                            ChapterArrow(previous = true, enabled = enabledPrevious, onClick = onPreviousChapter)
                            ChapterArrow(previous = false, enabled = enabledNext, onClick = onNextChapter)
                        }
                    }
                } else {
                    null
                }
                Column {
                    if (wantsSideRail) {
                        Box(Modifier.onSizeChanged { reservedFooterHeight = it.height }) {
                            if (splitFallback) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(backgroundColor)
                                        .windowInsetsPadding(navigationInsets.only(WindowInsetsSides.Horizontal))
                                        .padding(horizontal = footerPadding),
                                ) {
                                    fallbackArrows?.invoke()
                                }
                            }
                        }
                    }
                    if (!showSideRail) {
                        MinimalChapterSlider(
                            currentPage = currentPage,
                            totalPages = totalPages,
                            onPageIndexChange = onPageIndexChange,
                            onPageIndexChangeFinished = onPageIndexChangeFinished,
                            onClickAudio = onClickAudio,
                            audioAvailable = audioAvailable,
                            audioVisible = audioVisible,
                            onClickSettings = onClickSettings,
                            backgroundColor = backgroundColor,
                            modifier = Modifier.fillMaxWidth(),
                            chapterArrows = fallbackArrows.takeUnless { splitFallback },
                        )
                    } else {
                        SettingsGearBar(
                            onClickAudio = onClickAudio,
                            audioAvailable = audioAvailable,
                            audioVisible = audioVisible,
                            onClickSettings = onClickSettings,
                            backgroundColor = backgroundColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Previous/next chapter buttons, stacked vertically and parked on the left above the audio bar.
 *
 * The stack gives each button the full pad width, so a thumb aiming at one cannot catch the other.
 * The pad is a member of the bottom column rather than a floating overlay: an overlay drew on top
 * of the audio bar and hid its controls, and it could not be pushed up by it.
 *
 * It still slides horizontally, so it can be moved out from under a thumb that rests on the left.
 * The offset is hoisted because the reader hides its menu on every page turn, which would
 * otherwise reset the pad to the left edge each time, and it is a fraction of the pad's travel
 * rather than a pixel offset so the same spot comes back after leaving the manga, restarting the
 * app, or rotating — the travel itself changes with the width, and a pixel offset would end up
 * clamped to an edge.
 *
 * The double chevron points up/down to match the stack and to stay distinct from the single
 * chevrons used for page turns. The pair swaps for right-to-left manga.
 */
@Composable
private fun ChapterPadRow(
    isRtl: Boolean,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    offsetFraction: Float,
    onOffsetFractionChange: (Float) -> Unit,
    onOffsetCommitted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = 0.9f)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val availableWidthPx = constraints.maxWidth
        var padWidthPx by remember { mutableStateOf(0) }
        // A pad that has not been measured yet has no travel; using its placeholder zero width
        // would put a restored offset at the far edge for the first frame.
        val travelPx = if (padWidthPx <= 0) {
            0f
        } else {
            (availableWidthPx - padWidthPx).coerceAtLeast(0).toFloat()
        }
        // The gesture below is keyed on Unit so it never restarts mid-drag, which means it would
        // otherwise capture the offset and the travel from the first composition and reset the pad
        // to the left edge on every event. Reading through rememberUpdatedState keeps them live,
        // including across a rotation, which this activity handles without being rebuilt.
        val currentOffsetFraction by rememberUpdatedState(offsetFraction)
        val currentTravelPx by rememberUpdatedState(travelPx)
        val currentOnOffsetFractionChange by rememberUpdatedState(onOffsetFractionChange)
        val currentOnOffsetCommitted by rememberUpdatedState(onOffsetCommitted)

        Surface(
            modifier = Modifier
                .padding(start = MaterialTheme.padding.small)
                .offset { IntOffset((offsetFraction * travelPx).roundToInt(), 0) }
                .onSizeChanged { padWidthPx = it.width }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { currentOnOffsetCommitted() },
                        onDragCancel = { currentOnOffsetCommitted() },
                    ) { change, dragAmount ->
                        change.consume()
                        // Nothing to travel means nothing to remember; a drag on a pad as wide as
                        // the screen has no position to land on.
                        val travel = currentTravelPx
                        if (travel <= 0f) return@detectDragGestures
                        currentOnOffsetFractionChange(
                            (currentOffsetFraction + dragAmount.x / travel).coerceIn(0f, 1f),
                        )
                    }
                },
            shape = RoundedCornerShape(24.dp),
            color = backgroundColor,
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconButton(
                    onClick = if (isRtl) onNextChapter else onPreviousChapter,
                    enabled = if (isRtl) enabledNext else enabledPrevious,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardDoubleArrowUp,
                        contentDescription = stringResource(
                            if (isRtl) MR.strings.action_next_chapter else MR.strings.action_previous_chapter,
                        ),
                        modifier = Modifier.size(26.dp),
                    )
                }

                IconButton(
                    onClick = if (isRtl) onPreviousChapter else onNextChapter,
                    enabled = if (isRtl) enabledPrevious else enabledNext,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardDoubleArrowDown,
                        contentDescription = stringResource(
                            if (isRtl) MR.strings.action_previous_chapter else MR.strings.action_next_chapter,
                        ),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MinimalChapterSlider(
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    onPageIndexChangeFinished: () -> Unit,
    onClickAudio: () -> Unit,
    audioAvailable: Boolean,
    audioVisible: Boolean,
    onClickSettings: () -> Unit,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    chapterArrows: (@Composable () -> Unit)? = null,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    // The page number follows the finger during a drag, for the same reason the thumb does: the
    // reported page arrives asynchronously, so a long chapter made the number lag and jump.
    var dragPage by remember { mutableStateOf<Int?>(null) }

    Row(
        modifier = modifier
            .background(backgroundColor)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(48.dp)
            .padding(horizontal = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chapterArrows?.invoke()
        // The label is padded to the width of the largest page number, so its width never changes
        // while scrubbing. Without that, crossing a digit boundary ("999" → "1000") widened the
        // label, which moved the seek bar — and because the drag reads touch positions relative to
        // the bar, the finger's coordinate jumped with it. Each jump moved the page, which changed
        // the digit count again, so the two fed each other and the thumb shook.
        Box(contentAlignment = Alignment.CenterEnd) {
            Text(
                text = (dragPage ?: currentPage).toString(),
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
            )
            // Takes up the width of the longest possible number while staying invisible.
            Text(
                text = totalPages.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = Color.Transparent,
            )
        }

        ReaderPageSeekBar(
            currentPage = currentPage,
            totalPages = totalPages,
            onPageIndexChange = { index ->
                // The gesture computes this synchronously, so it is the page under the finger.
                // Remembering it here is what lets the label track the drag instead of the
                // asynchronously reported page.
                dragPage = index + 1
                onPageIndexChange(index)
            },
            onPageIndexChangeFinished = {
                dragPage = null
                onPageIndexChangeFinished()
            },
            modifier = Modifier.weight(1f),
        )

        Text(
            text = totalPages.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )

        if (audioAvailable) {
            IconButton(
                onClick = onClickAudio,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Headphones,
                    contentDescription = stringResource(MR.strings.audio_quick_open),
                    tint = if (audioVisible) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        IconButton(
            onClick = onClickSettings,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SettingsGearBar(
    onClickAudio: () -> Unit,
    audioAvailable: Boolean,
    audioVisible: Boolean,
    onClickSettings: () -> Unit,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(backgroundColor)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(48.dp)
            .padding(horizontal = MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (audioAvailable) {
            IconButton(
                onClick = onClickAudio,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Headphones,
                    contentDescription = stringResource(MR.strings.audio_quick_open),
                    tint = if (audioVisible) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        IconButton(
            onClick = onClickSettings,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
