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
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
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

    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
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
                                    type = chapterNavigatorType,
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
                audioControls?.invoke()
                if (chapterNavigatorType.isHorizontal()) {
                    MinimalChapterSlider(
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageIndexChange = onPageIndexChange,
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

@Composable
private fun MinimalChapterSlider(
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    onClickAudio: () -> Unit,
    audioAvailable: Boolean,
    audioVisible: Boolean,
    onClickSettings: () -> Unit,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .height(48.dp)
            .background(backgroundColor)
            .padding(horizontal = MaterialTheme.padding.small)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = currentPage.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )

        SlimSeekBar(
            currentPage = currentPage,
            totalPages = totalPages,
            onPageIndexChange = onPageIndexChange,
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
    progressColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    if (totalPages <= 1) {
        Spacer(modifier = modifier)
        return
    }

    Box(
        modifier = modifier
            .height(28.dp)
            .pointerInput(totalPages) {
                val widthPx = size.width.toFloat()
                fun seekAt(px: Float) {
                    val fraction = (px / widthPx).coerceIn(0f, 1f)
                    val page = (fraction * totalPages).roundToInt().coerceIn(1, totalPages)
                    onPageIndexChange(page - 1)
                }
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        seekAt(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            seekAt(change.position.x)
                        }
                    }
                }
            },
    ) {
        val fraction = currentPage.toFloat() / totalPages
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
                drawCircle(
                    color = progressColor,
                    radius = 4.5.dp.toPx(),
                    center = Offset(size.width * fraction, y),
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
