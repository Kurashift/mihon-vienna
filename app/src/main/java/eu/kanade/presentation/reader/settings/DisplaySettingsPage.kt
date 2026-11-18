package eu.kanade.presentation.reader.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

private val backgroundSwatches = listOf(
    Triple(1, MR.strings.black_background, Color.Black),
    Triple(2, MR.strings.gray_background, Color.DarkGray),
    Triple(0, MR.strings.white_background, Color.White),
)

private val filterChannels = listOf(
    MR.strings.color_filter_r_value to 16,
    MR.strings.color_filter_g_value to 8,
    MR.strings.color_filter_b_value to 0,
    MR.strings.color_filter_a_value to 24,
)

private val blendModes = ReaderPreferences.ColorFilterMode.mapIndexed { index, (label, _) -> index to label }

@Composable
internal fun DisplaySettingsPage(prefs: ReaderPreferences) {
    val customBrightness by prefs.customBrightness.collectAsState()
    PanelSwitch(MR.strings.pref_custom_brightness, prefs.customBrightness)
    if (customBrightness) {
        val brightness by prefs.customBrightnessValue.collectAsState()
        SliderItem(
            value = brightness,
            valueRange = -75..100,
            steps = 0,
            label = stringResource(MR.strings.reader_panel_brightness),
            onChange = prefs.customBrightnessValue::set,
        )
    }
    BackgroundSettings(prefs)
    PanelSwitch(MR.strings.pref_fullscreen, prefs.fullscreen)
    PanelSwitch(MR.strings.pref_show_page_number, prefs.showPageNumber)

    PanelGroup(MR.strings.custom_filter) {
        PanelSwitch(MR.strings.pref_grayscale, prefs.grayscale)
        PanelSwitch(MR.strings.pref_inverted_colors, prefs.invertedColors)
        ColorFilterSettings(prefs)
    }
    PanelGroup(MR.strings.reader_panel_screen_options) {
        PanelSwitch(MR.strings.reader_panel_keep_screen_on, prefs.keepScreenOn)
        val fullscreen by prefs.fullscreen.collectAsState()
        if (fullscreen && LocalActivity.current?.hasDisplayCutout() == true) {
            PanelSwitch(MR.strings.reader_panel_cutout, prefs.drawUnderCutout)
        }
    }
}

@Composable
private fun BackgroundSettings(prefs: ReaderPreferences) {
    val theme by prefs.readerTheme.collectAsState()
    val shape = MaterialTheme.shapes.small
    val outline = MaterialTheme.colorScheme.outline
    HeadingItem(MR.strings.reader_panel_background)
    FlowRow(
        modifier = Modifier
            .padding(horizontal = ReaderPanelInset)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        backgroundSwatches.forEach { (value, label, color) ->
            val selected = theme == value || (value == 1 && theme !in 0..3)
            val description = stringResource(label)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(shape)
                    .selectable(selected = selected, role = Role.RadioButton) { prefs.readerTheme.set(value) }
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(color, shape)
                        .border(1.dp, outline, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        val checkColor = if (color == Color.White) Color.Black else Color.White
                        Icon(Icons.Default.Check, contentDescription = null, tint = checkColor)
                    }
                }
            }
        }
        val automatic = theme == 3
        Box(
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clip(shape)
                .selectable(selected = automatic, role = Role.RadioButton) { prefs.readerTheme.set(3) },
            contentAlignment = Alignment.Center,
        ) {
            val colors = MaterialTheme.colorScheme
            Row(
                modifier = Modifier
                    .background(if (automatic) colors.secondaryContainer else Color.Transparent, shape)
                    .border(1.dp, if (automatic) Color.Transparent else outline, shape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val contentColor = if (automatic) colors.onSecondaryContainer else colors.onSurface
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = if (automatic) contentColor else Color.Transparent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(MR.strings.automatic_background),
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ColorFilterSettings(prefs: ReaderPreferences) {
    val enabled by prefs.colorFilter.collectAsState()
    PanelSwitch(MR.strings.pref_custom_color_filter, prefs.colorFilter)
    if (enabled) {
        val color by prefs.colorFilterValue.collectAsState()
        filterChannels.forEach { (label, shift) ->
            SliderItem(
                value = (color ushr shift) and 0xff,
                valueRange = 0..255,
                steps = 0,
                label = stringResource(label),
                onChange = { component ->
                    prefs.colorFilterValue.getAndSet { (it and (0xff shl shift).inv()) or (component shl shift) }
                },
            )
        }
        PanelChoice(MR.strings.pref_color_filter_mode, prefs.colorFilterMode, blendModes)
    }
}
