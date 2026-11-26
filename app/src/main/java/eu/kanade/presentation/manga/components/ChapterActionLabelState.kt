package eu.kanade.presentation.manga.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

internal enum class ChapterBottomAction {
    BOOKMARK,
    MARK,
    READ,
    DOWNLOAD,
    MOVE,
    TITLE,
    DELETE,
    DELETE_LOCAL,
}

internal class ChapterActionLabelState(private val scope: CoroutineScope) {
    private var visibleAction by mutableStateOf<ChapterBottomAction?>(null)
    private var resetJob: Job? = null

    fun isShowing(action: ChapterBottomAction): Boolean = visibleAction == action

    fun show(action: ChapterBottomAction) {
        visibleAction = action
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(1.seconds)
            if (isActive) visibleAction = null
        }
    }
}
