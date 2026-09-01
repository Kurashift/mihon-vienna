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
import eu.kanade.tachiyomi.data.audio.AudioCategoryCache
import eu.kanade.tachiyomi.data.audio.AudioCategoryField
import eu.kanade.tachiyomi.data.audio.AudioCategoryRef
import eu.kanade.tachiyomi.data.audio.CircleItem
import eu.kanade.tachiyomi.data.audio.KikoeruApi
import eu.kanade.tachiyomi.data.audio.TagItem
import eu.kanade.tachiyomi.data.audio.VaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections

enum class AudioCategoryType(
    val label: StringResource,
    /** Which backend dictionary this tab lists, and therefore which id endpoint filters by it. */
    val field: AudioCategoryField,
) {
    CIRCLE(MR.strings.audio_filter_circles, AudioCategoryField.CIRCLE),
    VA(MR.strings.audio_vas, AudioCategoryField.VA),
    TAG(MR.strings.audio_filter_tags, AudioCategoryField.TAG),
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
            onSelectTab = { viewModel.selectField(it.field) },
            onRetry = viewModel::retry,
            onSelect = { type, id, name ->
                navigator.push(
                    AudioBrowseScreen(
                        categoryTitle = name,
                        initialCategory = AudioCategoryRef(type.field, id, name),
                    ),
                )
            },
        )
    }
}

data class AudioCategoryState(
    /** Dictionaries currently being fetched; drives the per-tab spinner / refresh bar. */
    val loadingFields: Set<AudioCategoryField> = emptySet(),
    /** Dictionaries whose fetch failed with nothing cached to fall back on. */
    val errorFields: Set<AudioCategoryField> = emptySet(),
    val circles: List<CircleItem> = emptyList(),
    val vas: List<VaItem> = emptyList(),
    val tags: List<TagItem> = emptyList(),
)

class AudioCategoryViewModel(
    private val api: KikoeruApi = Injekt.get(),
    private val cache: AudioCategoryCache = Injekt.get(),
) : ViewModel() {

    private val _state = MutableStateFlow(AudioCategoryState())
    val state: StateFlow<AudioCategoryState> = _state.asStateFlow()

    /** The tab currently on screen; only its dictionary is fetched on demand. */
    private var selectedField: AudioCategoryField = AudioCategoryField.CIRCLE

    /**
     * Dictionaries confirmed to be up to date, either from a fresh on-disk snapshot at startup or
     * a successful fetch this session. Anything outside this set is refetched when its tab is
     * opened, so one stale field can never be masked by a sibling being refreshed.
     *
     * Synchronized because it is written from the IO thread (startup read, fetch result) while the
     * main thread reads it on every tab switch, and the startup read of the tag dictionary is long
     * enough for a tab switch to land in the middle of it.
     */
    private val freshFields = Collections.synchronizedSet(mutableSetOf<AudioCategoryField>())

    init {
        viewModelScope.launchIO {
            val cached = cache.read()
            if (cached != null) {
                _state.update {
                    it.copy(
                        circles = cached.circles,
                        vas = cached.vas,
                        tags = cached.tags,
                    )
                }
                // Trust whatever the snapshot can still vouch for; stale fields are pulled in
                // lazily when their tab is opened instead of all three blocking first paint.
                AudioCategoryField.entries
                    .filter { cache.isFieldFresh(cached, it) }
                    .forEach(freshFields::add)
                if (selectedField in freshFields) return@launchIO
            }
            // No usable snapshot (or the open tab is stale): fetch only the tab that is actually
            // open. The other two are pulled in when their tab is selected.
            fetchField(selectedField)
        }
    }

    /** Called when the user switches tab: pulls the dictionary only when this one is missing or stale. */
    fun selectField(field: AudioCategoryField) {
        selectedField = field
        val current = _state.value
        val hasData = itemsOf(field, current).isNotEmpty()
        // Data already on screen stays visible while a fetch runs; a stale snapshot gets a
        // background refresh instead of blanking the list.
        if (!hasData || field !in freshFields) fetchField(field)
    }

    fun retry(field: AudioCategoryField) {
        fetchField(field)
    }

    private fun fetchField(field: AudioCategoryField) {
        if (field in _state.value.loadingFields) return
        viewModelScope.launchIO {
            _state.update { it.copy(loadingFields = it.loadingFields + field) }
            try {
                when (field) {
                    AudioCategoryField.CIRCLE -> {
                        val items = api.fetchCircles().sortedByDescending { it.count }
                        cache.write(circles = items)
                        _state.update {
                            it.copy(
                                loadingFields = it.loadingFields - field,
                                errorFields = it.errorFields - field,
                                circles = items,
                            )
                        }
                    }
                    AudioCategoryField.VA -> {
                        val items = api.fetchVas().sortedByDescending { it.count }
                        cache.write(vas = items)
                        _state.update {
                            it.copy(
                                loadingFields = it.loadingFields - field,
                                errorFields = it.errorFields - field,
                                vas = items,
                            )
                        }
                    }
                    AudioCategoryField.TAG -> {
                        val items = api.fetchTags().sortedByDescending { it.count }
                        cache.write(tags = items)
                        _state.update {
                            it.copy(
                                loadingFields = it.loadingFields - field,
                                errorFields = it.errorFields - field,
                                tags = items,
                            )
                        }
                    }
                }
                // This field is now confirmed fresh on disk; only this one is marked, so a stale
                // sibling tab still gets refetched when it is opened.
                freshFields += field
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Category dictionary fetch failed: $field" }
                _state.update { current ->
                    val hasData = itemsOf(field, current).isNotEmpty()
                    current.copy(
                        loadingFields = current.loadingFields - field,
                        // A stale dictionary beats an error state: only flag the tab when there
                        // is nothing cached to show instead.
                        errorFields = if (hasData) current.errorFields else current.errorFields + field,
                    )
                }
            }
        }
    }

    private fun itemsOf(field: AudioCategoryField, state: AudioCategoryState): List<*> = when (field) {
        AudioCategoryField.CIRCLE -> state.circles
        AudioCategoryField.VA -> state.vas
        AudioCategoryField.TAG -> state.tags
    }
}
