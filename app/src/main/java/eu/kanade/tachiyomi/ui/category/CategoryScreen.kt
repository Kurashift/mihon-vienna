package eu.kanade.tachiyomi.ui.category

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.CategoryScreen
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.CategoryRenameDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.showUndoSnackbar
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.LoadingScreen

class CategoryScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<CategoryViewModel>()
        val snackbarHostState = remember { SnackbarHostState() }

        val state by viewModel.state.collectAsStateWithLifecycle()

        if (state is CategoryScreenState.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as CategoryScreenState.Success

        CategoryScreen(
            state = successState,
            snackbarHostState = snackbarHostState,
            onClickCreate = { viewModel.showDialog(CategoryDialog.Create) },
            onClickRename = { viewModel.showDialog(CategoryDialog.Rename(it)) },
            onClickDelete = { viewModel.deleteCategory(it.id) },
            onChangeOrder = viewModel::changeOrder,
            navigateUp = navigator::pop,
        )

        when (val dialog = successState.dialog) {
            null -> {}
            CategoryDialog.Create -> {
                CategoryCreateDialog(
                    onDismissRequest = viewModel::dismissDialog,
                    onCreate = viewModel::createCategory,
                    categories = successState.categories.fastMap { it.name },
                )
            }
            is CategoryDialog.Rename -> {
                CategoryRenameDialog(
                    onDismissRequest = viewModel::dismissDialog,
                    onRename = { viewModel.renameCategory(dialog.category, it) },
                    categories = successState.categories.fastMap { it.name },
                    category = dialog.category.name,
                )
            }
        }

        LaunchedEffect(Unit) {
            viewModel.events.collectLatest { event ->
                when (event) {
                    is CategoryEvent.LocalizedMessage -> context.toast(event.stringRes)
                    is CategoryEvent.Deleted -> {
                        val snapshot = event.snapshot
                        snackbarHostState.showUndoSnackbar(
                            message = context.stringResource(MR.strings.undo_category_deleted, snapshot.category.name),
                            undoLabel = context.stringResource(MR.strings.action_undo),
                        ) {
                            viewModel.restoreCategory(snapshot)
                        }
                    }
                }
            }
        }
    }
}
