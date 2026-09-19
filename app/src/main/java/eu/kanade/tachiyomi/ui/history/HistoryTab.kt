package eu.kanade.tachiyomi.ui.history

import android.content.Context
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.history.HistoryScreen
import eu.kanade.presentation.history.components.HistoryDeleteAllDialog
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.showSnackbarReplacing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object HistoryTab : Tab {

    private val snackbarHostState = SnackbarHostState()

    private val resumeLastChapterReadEvent = Channel<Unit>()

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_history_enter)
            return TabOptions(
                index = 2u,
                title = stringResource(MR.strings.label_recent_manga),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        resumeLastChapterReadEvent.send(Unit)
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = viewModel<HistoryViewModel>()
        val state by viewModel.state.collectAsState()

        HistoryScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            onSearchQueryChange = viewModel::updateSearchQuery,
            onClickCover = { navigator.push(MangaScreen(it)) },
            onClickResume = viewModel::getNextChapterForManga,
            onClickDeleteSwipe = viewModel::deleteHistory,
            onToggleSelection = viewModel::toggleSelection,
            onClearSelection = viewModel::clearSelection,
            onSelectAll = viewModel::toggleAllSelection,
            onDeleteSelection = viewModel::deleteSelection,
            onDialogChange = viewModel::setDialog,
        )

        val onDismissRequest = { viewModel.setDialog(null) }
        when (state.dialog) {
            is HistoryViewModel.Dialog.DeleteAll -> {
                HistoryDeleteAllDialog(
                    onDismissRequest = onDismissRequest,
                    onDelete = viewModel::removeAllHistory,
                )
            }
            null -> {}
        }

        LaunchedEffect(state.list) {
            if (state.list != null) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            viewModel.events.collectLatest { e ->
                when (e) {
                    HistoryViewModel.Event.InternalError ->
                        snackbarHostState.showSnackbarReplacing(context.stringResource(MR.strings.internal_error))
                    HistoryViewModel.Event.HistoryCleared ->
                        snackbarHostState.showSnackbarReplacing(
                            context.stringResource(MR.strings.clear_history_completed),
                        )
                    is HistoryViewModel.Event.HistoryDeleted -> {
                        val result = snackbarHostState.showSnackbarReplacing(
                            message = context.stringResource(MR.strings.history_deleted_count, e.updates.size),
                            actionLabel = context.stringResource(MR.strings.action_undo),
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.restoreHistory(e.updates)
                        }
                    }
                    is HistoryViewModel.Event.OpenChapter -> openChapter(context, e.chapter)
                }
            }
        }

        LaunchedEffect(Unit) {
            resumeLastChapterReadEvent.receiveAsFlow().collectLatest {
                openChapter(context, viewModel.getNextChapter())
            }
        }
    }

    private suspend fun openChapter(context: Context, chapter: Chapter?) {
        if (chapter != null) {
            val intent = ReaderActivity.newIntent(context, chapter.mangaId, chapter.id)
            context.startActivity(intent)
        } else {
            snackbarHostState.showSnackbarReplacing(context.stringResource(MR.strings.no_next_chapter))
        }
    }
}
