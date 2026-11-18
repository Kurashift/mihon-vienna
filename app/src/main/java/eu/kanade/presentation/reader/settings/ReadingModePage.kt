package eu.kanade.presentation.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.text.NumberFormat

internal val panelReadingModes = listOf(
    ReadingMode.LEFT_TO_RIGHT to MR.strings.reader_panel_paged_ltr,
    ReadingMode.RIGHT_TO_LEFT to MR.strings.reader_panel_paged_rtl,
    ReadingMode.VERTICAL to MR.strings.reader_panel_paged_vertical,
    ReadingMode.WEBTOON to MR.strings.webtoon_viewer,
    ReadingMode.CONTINUOUS_VERTICAL to MR.strings.reader_panel_strip_with_gaps,
)

private val orientations = ReaderOrientation.entries
    .filterNot { it == ReaderOrientation.DEFAULT }
    .map { it to it.stringRes }

private val imageScaleTypes = ReaderPreferences.ImageScaleType.mapIndexed { index, label -> index + 1 to label }
private val zoomStarts = ReaderPreferences.ZoomStart.mapIndexed { index, label -> index + 1 to label }

@Composable
internal fun ReadingModePage(viewModel: ReaderSettingsViewModel) {
    val prefs = viewModel.preferences
    val storedMode by prefs.defaultReadingMode.collectAsState()
    val readingMode = ReadingMode.fromPreference(storedMode)
        .takeUnless { it == ReadingMode.DEFAULT } ?: ReadingMode.RIGHT_TO_LEFT
    val storedOrientation by prefs.defaultOrientationType.collectAsState()
    val orientation = ReaderOrientation.fromPreference(storedOrientation)
        .takeUnless { it == ReaderOrientation.DEFAULT } ?: ReaderOrientation.FREE
    val viewer by viewModel.viewerFlow.collectAsState()
    val isWebtoon = viewer is WebtoonViewer

    HeadingItem(MR.strings.pref_category_global)
    PanelChoice(
        label = MR.strings.pref_category_reading_mode,
        value = readingMode,
        options = panelReadingModes,
        onChange = viewModel.onChangeReadingMode,
    )
    PanelChoice(
        label = MR.strings.rotation_type,
        value = orientation,
        options = orientations,
        onChange = viewModel.onChangeOrientation,
    )
    PanelSwitch(MR.strings.pref_crop_borders, if (isWebtoon) prefs.cropBordersWebtoon else prefs.cropBorders)
    if (isWebtoon) {
        val padding by prefs.webtoonSidePadding.collectAsState()
        val percentFormat = remember { NumberFormat.getPercentInstance() }
        SliderItem(
            value = padding,
            valueRange = ReaderPreferences.WEBTOON_PADDING_MIN..ReaderPreferences.WEBTOON_PADDING_MAX,
            label = stringResource(MR.strings.pref_webtoon_side_padding),
            valueString = percentFormat.format(padding / 100f),
            onChange = prefs.webtoonSidePadding::set,
        )
    } else {
        PanelChoice(MR.strings.reader_panel_image_fit, prefs.imageScaleType, imageScaleTypes)
        PanelSwitch(MR.strings.reader_panel_always_transition, prefs.alwaysShowChapterTransition)
    }

    WidePageSettings(
        split = if (isWebtoon) prefs.dualPageSplitWebtoon else prefs.dualPageSplitPaged,
        invert = if (isWebtoon) prefs.dualPageInvertWebtoon else prefs.dualPageInvertPaged,
        rotate = if (isWebtoon) prefs.dualPageRotateToFitWebtoon else prefs.dualPageRotateToFit,
        invertRotation = if (isWebtoon) prefs.dualPageRotateToFitInvertWebtoon else prefs.dualPageRotateToFitInvert,
    )
    PanelGroup(MR.strings.reader_group_zoom) {
        if (isWebtoon) {
            PanelSwitch(MR.strings.pref_double_tap_zoom, prefs.webtoonDoubleTapZoomEnabled)
            PanelSwitch(MR.strings.pref_webtoon_disable_zoom_out, prefs.webtoonDisableZoomOut)
        } else {
            PanelChoice(MR.strings.pref_zoom_start, prefs.zoomStart, zoomStarts)
            PanelSwitch(MR.strings.pref_landscape_zoom, prefs.landscapeZoom)
            PanelSwitch(MR.strings.pref_navigate_pan, prefs.navigateToPan)
        }
    }
}

@Composable
private fun WidePageSettings(
    split: Preference<Boolean>,
    invert: Preference<Boolean>,
    rotate: Preference<Boolean>,
    invertRotation: Preference<Boolean>,
) {
    PanelGroup(MR.strings.reader_panel_wide_pages) {
        val splitEnabled by split.collectAsState()
        val rotateEnabled by rotate.collectAsState()
        PanelSwitch(MR.strings.pref_dual_page_split, split)
        if (splitEnabled) {
            PanelSwitch(MR.strings.pref_dual_page_invert, invert)
        }
        PanelSwitch(MR.strings.pref_page_rotate, rotate)
        if (rotateEnabled) {
            PanelSwitch(MR.strings.pref_page_rotate_invert, invertRotation)
        }
    }
}
