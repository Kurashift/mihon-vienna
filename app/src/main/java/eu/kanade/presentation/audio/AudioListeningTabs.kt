package eu.kanade.presentation.audio

import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** The two halves of the listening surface: what is queued next, then what was heard. */
enum class AudioListeningTab(val label: StringResource) {
    QUEUE(MR.strings.audio_playlist),
    HISTORY(MR.strings.audio_history),
}

@Composable
fun AudioListeningTabs(
    selected: AudioListeningTab,
    onSelect: (AudioListeningTab) -> Unit,
) {
    val tabs = AudioListeningTab.entries
    PrimaryTabRow(selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0)) {
        tabs.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { if (tab != selected) onSelect(tab) },
                text = { Text(stringResource(tab.label)) },
            )
        }
    }
}
