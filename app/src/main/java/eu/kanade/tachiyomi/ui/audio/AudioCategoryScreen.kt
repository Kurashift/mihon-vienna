package eu.kanade.tachiyomi.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.audio.AudioCategoryContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.audio.CircleItem
import eu.kanade.tachiyomi.data.audio.KikoeruApi
import eu.kanade.tachiyomi.data.audio.TagItem
import eu.kanade.tachiyomi.data.audio.VaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

enum class AudioCategoryType(
    val label: StringResource,
    val prefix: String,
) {
    CIRCLE(MR.strings.audio_filter_circles, "circle"),
    VA(MR.strings.audio_vas, "va"),
    TAG(MR.strings.audio_filter_tags, "tag"),
}

class AudioCategoryScreen(
    private val initialType: AudioCategoryType = AudioCategoryType.CIRCLE,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<AudioCategoryViewModel>()
        val state by viewModel.state.collectAsState()

        AudioCategoryContent(
            state = state,
            initialType = initialType,
            bottomBar = { AudioMiniPlayerNavigationBar() },
            navigateUp = navigator::pop,
            onRetry = viewModel::load,
            onSelect = { type, name ->
                navigator.push(
                    AudioBrowseScreen(
                        categoryTitle = name,
                        initialFilter = "\$${type.prefix}:$name\$",
                    ),
                )
            },
        )
    }
}

data class AudioCategoryState(
    val loading: Boolean = false,
    val error: Boolean = false,
    val circles: List<CircleItem> = emptyList(),
    val vas: List<VaItem> = emptyList(),
    val tags: List<TagItem> = emptyList(),
)

class AudioCategoryViewModel(
    private val api: KikoeruApi = Injekt.get(),
) : ViewModel() {

    private val _state = MutableStateFlow(AudioCategoryState())
    val state: StateFlow<AudioCategoryState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launchIO {
            _state.update { it.copy(loading = true, error = false) }
            try {
                _state.update {
                    it.copy(
                        loading = false,
                        error = false,
                        circles = api.fetchCircles().sortedByDescending { c -> c.count },
                        vas = api.fetchVas().sortedByDescending { v -> v.count },
                        tags = api.fetchTags().sortedByDescending { t -> t.count },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = true) }
            }
        }
    }

}
