package eu.kanade.tachiyomi.ui.browse.source.local

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.LocalScrollToTopRequests
import eu.kanade.tachiyomi.ui.home.HomeScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource

/**
 * The tab itself is written into the navigator's saved state, so the request channel cannot be
 * one of its fields: a flow is not serializable and breaks the whole save on the next stop.
 */
private val localSourceScrollToTopRequests = MutableStateFlow(0L)

data object LocalSourceTab : Tab {

    private val screen = BrowseSourceScreen(
        sourceId = LocalSource.ID,
        listingQuery = null,
        isRoot = true,
    )

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 3u,
            title = stringResource(MR.strings.label_local_library),
            icon = rememberVectorPainter(Icons.Outlined.FolderOpen),
        )

    override suspend fun onReselect(navigator: Navigator) {
        localSourceScrollToTopRequests.update { it + 1 }
    }

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val threshold = with(LocalDensity.current) { 24.dp.toPx() }
        val navigationVisible = remember { mutableStateOf(true) }
        val accumulatedScroll = remember { mutableFloatStateOf(0f) }
        val scrollConnection = remember(threshold) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero

                    val previous = accumulatedScroll.floatValue
                    if (previous != 0f && previous * available.y < 0f) {
                        accumulatedScroll.floatValue = 0f
                    }
                    accumulatedScroll.floatValue += available.y

                    when {
                        accumulatedScroll.floatValue <= -threshold && navigationVisible.value -> {
                            navigationVisible.value = false
                            accumulatedScroll.floatValue = 0f
                            scope.launch { HomeScreen.showBottomNav(false) }
                        }
                        accumulatedScroll.floatValue >= threshold && !navigationVisible.value -> {
                            navigationVisible.value = true
                            accumulatedScroll.floatValue = 0f
                            scope.launch { HomeScreen.showBottomNav(true) }
                        }
                    }
                    return Offset.Zero
                }
            }
        }

        DisposableEffect(Unit) {
            scope.launch { HomeScreen.showBottomNav(true) }
            onDispose {
                scope.launch { HomeScreen.showBottomNav(true) }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollConnection),
        ) {
            CompositionLocalProvider(LocalScrollToTopRequests provides localSourceScrollToTopRequests) {
                screen.Content()
            }
        }
    }
}
