package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderSettingsDialog(
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
    viewModel: ReaderSettingsViewModel,
) {
    val tabTitles = listOf(
        stringResource(MR.strings.pref_category_reading),
        stringResource(MR.strings.reader_group_display),
        stringResource(MR.strings.reader_panel_controls),
    )
    val pagerState = rememberPagerState { tabTitles.size }
    val showMenus by rememberUpdatedState(onShowMenus)
    val hideMenus by rememberUpdatedState(onHideMenus)

    BoxWithConstraints {
        TabbedDialog(
            modifier = Modifier.heightIn(max = maxHeight * 0.85f),
            onDismissRequest = {
                onDismissRequest()
                onShowMenus()
            },
            tabTitles = tabTitles,
            pagerState = pagerState,
        ) { page ->
            val currentPage = pagerState.settledPage
            if (page == currentPage) {
                val window = (LocalView.current.parent as? DialogWindowProvider)?.window
                LaunchedEffect(currentPage, window) {
                    val preview = currentPage == DISPLAY_TAB
                    window?.setDimAmount(if (preview) 0f else 0.5f)
                    if (preview) hideMenus() else showMenus()
                }
            }

            Column(
                modifier = Modifier
                    .padding(top = TabbedDialogPaddings.Vertical, bottom = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (page) {
                    0 -> ReadingModePage(viewModel)
                    DISPLAY_TAB -> DisplaySettingsPage(viewModel.preferences)
                    2 -> ControlSettingsPage(viewModel)
                }
            }
        }
    }
}

private const val DISPLAY_TAB = 1
