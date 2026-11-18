package eu.kanade.presentation.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.text.NumberFormat

private val pagerTapZones = ReaderPreferences.TapZoneValuesPager.map { it to ReaderPreferences.TapZones[it] }
private val webtoonTapZones = ReaderPreferences.TapZoneValuesWebtoon.map { it to ReaderPreferences.TapZones[it] }
private val tapInversions = ReaderPreferences.TappingInvertMode.entries.map { it to it.titleRes }

private val flashColors = listOf(
    ReaderPreferences.FlashColor.BLACK to MR.strings.pref_flash_style_black,
    ReaderPreferences.FlashColor.WHITE to MR.strings.pref_flash_style_white,
    ReaderPreferences.FlashColor.WHITE_BLACK to MR.strings.pref_flash_style_white_black,
)

@Composable
internal fun ControlSettingsPage(viewModel: ReaderSettingsViewModel) {
    val prefs = viewModel.preferences
    val viewer by viewModel.viewerFlow.collectAsState()
    val isWebtoon = viewer is WebtoonViewer
    val tapZones = if (isWebtoon) prefs.navigationModeWebtoon else prefs.navigationModePager
    val tapZone by tapZones.collectAsState()

    PanelChoice(
        label = MR.strings.pref_viewer_nav,
        value = tapZone,
        options = if (isWebtoon) webtoonTapZones else pagerTapZones,
        onChange = tapZones::set,
    )
    PanelSwitch(MR.strings.reader_panel_long_press, prefs.readWithLongTap)
    PanelSwitch(MR.strings.pref_page_transitions, prefs.pageTransitions)
    PanelSwitch(MR.strings.reader_panel_always_transition, prefs.alwaysShowChapterTransition)

    if (tapZone != ReaderPreferences.DISABLED_NAV_MODE) {
        PanelGroup(MR.strings.reader_panel_tap_invert) {
            PanelChoice(
                label = MR.strings.reader_panel_invert_direction,
                pref = if (isWebtoon) prefs.webtoonNavInverted else prefs.pagerNavInverted,
                options = tapInversions,
            )
        }
    }
    PanelGroup(MR.strings.reader_panel_side_navigator) {
        SideNavigatorSettings(prefs)
    }
    PanelGroup(MR.strings.reader_group_flash) {
        FlashSettings(prefs)
    }
}

@Composable
private fun SideNavigatorSettings(prefs: ReaderPreferences) {
    val modes by prefs.verticalNavigator.collectAsState()
    HeadingItem(MR.strings.reader_panel_active_modes)
    panelReadingModes.forEach { (mode, label) ->
        PanelCheckbox(label = label, checked = mode in modes) { checked ->
            prefs.verticalNavigator.getAndSet { if (checked) it + mode else it - mode }
        }
    }
    if (modes.isNotEmpty()) {
        val height by prefs.verticalNavigatorHeight.collectAsState()
        val percentFormat = remember { NumberFormat.getPercentInstance() }
        PanelSwitch(MR.strings.reader_panel_navigator_left, prefs.verticalNavigatorOnLeft)
        SliderItem(
            label = stringResource(MR.strings.reader_panel_navigator_height),
            value = height,
            valueRange = 65..100,
            steps = 6,
            valueString = percentFormat.format(height / 100f),
            onChange = prefs.verticalNavigatorHeight::set,
        )
    }
}

@Composable
private fun FlashSettings(prefs: ReaderPreferences) {
    val enabled by prefs.flashOnPageChange.collectAsState()
    PanelSwitch(MR.strings.pref_flash_page, prefs.flashOnPageChange)
    if (enabled) {
        val duration by prefs.flashDurationMillis.collectAsState()
        val interval by prefs.flashPageInterval.collectAsState()
        SliderItem(
            label = stringResource(MR.strings.pref_flash_duration),
            value = duration / ReaderPreferences.MILLI_CONVERSION,
            valueRange = 1..15,
            valueString = stringResource(MR.strings.pref_flash_duration_summary, duration),
            onChange = { prefs.flashDurationMillis.set(it * ReaderPreferences.MILLI_CONVERSION) },
        )
        SliderItem(
            label = stringResource(MR.strings.pref_flash_page_interval),
            value = interval,
            valueRange = 1..10,
            valueString = pluralStringResource(MR.plurals.pref_pages, interval, interval),
            onChange = prefs.flashPageInterval::set,
        )
        PanelChoice(MR.strings.pref_flash_with, prefs.flashColor, flashColors)
    }
}
