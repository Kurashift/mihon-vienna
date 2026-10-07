package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.seekBarGestures
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

private val readerBarsSlideAnimationSpec = tween<IntOffset>(200)
private val readerBarsFadeAnimationSpec = tween<Float>(150)

private val FINE_THUMB_RADIUS = 7.dp

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

    chapterPadOffsetX: Float,
    onChapterPadOffsetXChange: (Float) -> Unit,

    audioAvailable: Boolean,
    audioVisible: Boolean,
    onClickAudio: () -> Unit,
    onClickSettings: () -> Unit,
) {
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)

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

        if (!chapterNavigatorType.isHorizontal()) {
            val sliderOnLeft = chapterNavigatorType == ChapterNavigatorType.VERTICAL_LEFT
            CompositionLocalProvider(
                LocalLayoutDirection provides if (sliderOnLeft) LayoutDirection.Ltr else LayoutDirection.Rtl,
            ) {
                Row(modifier = Modifier.weight(1f)) {
                    AnimatedVisibility(
                        visible = visible,
                        enter = slideInHorizontally(readerBarsSlideAnimationSpec) { if (sliderOnLeft) -it else it } +
                            fadeIn(readerBarsFadeAnimationSpec),
                        exit = slideOutHorizontally(readerBarsSlideAnimationSpec) { if (sliderOnLeft) -it else it } +
                            fadeOut(readerBarsFadeAnimationSpec),
                    ) {
                        Row {
                            Spacer(modifier = Modifier.width(MaterialTheme.padding.small))
                            Box(
                                modifier = Modifier.fillMaxHeight(),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                ChapterNavigator(
                                    modifier = Modifier.fillMaxHeight(verticalNavigatorHeight),
                                    onNextChapter = onNextChapter,
                                    enabledNext = enabledNext,
                                    onPreviousChapter = onPreviousChapter,
                                    enabledPrevious = enabledPrevious,
                                    currentPage = currentPage,
                                    totalPages = totalPages,
                                    onPageIndexChange = onPageIndexChange,
                                    onPageIndexChangeFinished = onPageIndexChangeFinished,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
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
                if (chapterNavigatorType.isHorizontal()) {
                    ChapterPadRow(
                        isRtl = chapterNavigatorType == ChapterNavigatorType.HORIZONTAL_RTL,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        offsetX = chapterPadOffsetX,
                        onOffsetXChange = onChapterPadOffsetXChange,
                    )
                }
                audioControls?.invoke()
                if (chapterNavigatorType.isHorizontal()) {
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

/**
 * Previous/next chapter buttons, stacked vertically and parked on the left above the audio bar.
 *
 * The stack gives each button the full pad width, so a thumb aiming at one cannot catch the other.
 * The pad is a member of the bottom column rather than a floating overlay: an overlay drew on top
 * of the audio bar and hid its controls, and it could not be pushed up by it.
 *
 * It still slides horizontally, so it can be moved out from under a thumb that rests on the left.
 * The offset is hoisted because the reader hides its menu on every page turn, which would
 * otherwise reset the pad to the left edge each time.
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
    offsetX: Float,
    onOffsetXChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = 0.9f)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val availableWidthPx = constraints.maxWidth
        var padWidthPx by remember { mutableStateOf(0) }
        // The gesture below is keyed on Unit so it never restarts mid-drag, which means it would
        // otherwise capture the offset from the first composition and reset the pad to the left
        // edge on every event. Reading through rememberUpdatedState keeps it live.
        val currentOffsetX by rememberUpdatedState(offsetX)
        val currentOnOffsetXChange by rememberUpdatedState(onOffsetXChange)

        Surface(
            modifier = Modifier
                .padding(start = MaterialTheme.padding.small)
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .onSizeChanged {
                    padWidthPx = it.width
                    val maxOffset = (availableWidthPx - it.width).coerceAtLeast(0)
                    onOffsetXChange(offsetX.coerceIn(0f, maxOffset.toFloat()))
                }
                .pointerInput(Unit) {
                    var dragOffset = 0f
                    detectDragGestures(
                        onDragStart = { dragOffset = currentOffsetX },
                    ) { change, dragAmount ->
                        change.consume()
                        val maxOffset = (availableWidthPx - padWidthPx).coerceAtLeast(0)
                        dragOffset = (dragOffset + dragAmount.x).coerceIn(0f, maxOffset.toFloat())
                        currentOnOffsetXChange(dragOffset)
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
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    // The page number follows the finger during a drag, for the same reason the thumb does: the
    // reported page arrives asynchronously, so a long chapter made the number lag and jump.
    var dragPage by remember { mutableStateOf<Int?>(null) }

    Row(
        modifier = modifier
            .height(48.dp)
            .background(backgroundColor)
            .padding(horizontal = MaterialTheme.padding.small)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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

        SlimSeekBar(
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
            progressColor = MaterialTheme.colorScheme.primary,
            trackColor = contentColor.copy(alpha = 0.24f),
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
private fun SlimSeekBar(
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    onPageIndexChangeFinished: () -> Unit,
    progressColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    // Declared before the single-page early return so the remember slots do not shift if the
    // page count ever crosses one.
    val latestPage by rememberUpdatedState(currentPage)
    val latestTotal by rememberUpdatedState(totalPages)
    val latestOnChange by rememberUpdatedState(onPageIndexChange)
    val latestOnFinished by rememberUpdatedState(onPageIndexChangeFinished)
    var fineSeek by remember { mutableStateOf(false) }
    // While a drag is in flight the thumb follows the finger. Acting on a seek is asynchronous, so
    // a thousand-page chapter reports pages faster than the reader settles on them; drawing from
    // the reported page made the thumb snap back to a stale position and fight the finger, which
    // is what read as the thumb and page number shaking.
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    if (totalPages <= 1) {
        Spacer(modifier = modifier)
        return
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .seekBarGestures(
                thumbFraction = { latestPage.toFloat() / latestTotal.toFloat().coerceAtLeast(1f) },
                valueAt = { fraction ->
                    (fraction * latestTotal).roundToInt().coerceIn(1, latestTotal)
                },
                onValue = { page -> latestOnChange(page - 1) },
                onFinished = latestOnFinished,
                onFineSeekChange = { fineSeek = it },
                onDragFraction = { dragFraction = it },
            ),
    ) {
        val fraction = dragFraction ?: (currentPage.toFloat() / totalPages)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterStart),
        ) {
            val trackHeight = 4.dp.toPx()
            val y = size.height / 2f
            val corner = CornerRadius(trackHeight / 2f)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, y - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = corner,
            )
            if (fraction > 0f) {
                drawRoundRect(
                    color = progressColor,
                    topLeft = Offset(0f, y - trackHeight / 2f),
                    size = Size(size.width * fraction, trackHeight),
                    cornerRadius = corner,
                )
                val center = Offset(size.width * fraction, y)
                // A larger thumb plus a halo marks fine seeking, which is otherwise invisible.
                if (fineSeek) {
                    drawCircle(
                        color = progressColor.copy(alpha = 0.25f),
                        radius = 11.dp.toPx(),
                        center = center,
                    )
                }
                drawCircle(
                    color = progressColor,
                    radius = (if (fineSeek) FINE_THUMB_RADIUS else 4.5.dp).toPx(),
                    center = center,
                )
            }
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
            .height(48.dp)
            .background(backgroundColor)
            .padding(horizontal = MaterialTheme.padding.small)
            .windowInsetsPadding(WindowInsets.navigationBars),
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
