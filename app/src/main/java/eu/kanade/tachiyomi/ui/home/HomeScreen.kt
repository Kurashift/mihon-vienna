package eu.kanade.tachiyomi.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.lerp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabNavigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.BottomNavFabLift
import eu.kanade.presentation.components.LocalBottomNavFabPadding
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.browse.OnlineSourceCenterScreen
import eu.kanade.tachiyomi.ui.browse.source.local.LocalSourceTab
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import soup.compose.material.motion.animation.materialFadeThroughIn
import soup.compose.material.motion.animation.materialFadeThroughOut
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LocalFastScrollerBottomInset
import tachiyomi.presentation.core.components.material.NavigationBar
import tachiyomi.presentation.core.components.material.NavigationRail
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object HomeScreen : Screen() {

    private val librarySearchEvent = Channel<String>()
    private val openTabEvent = Channel<Tab>()
    private val showBottomNavEvent = Channel<Boolean>()

    @Suppress("ConstPropertyName")
    private const val TabFadeDuration = 90

    @Suppress("ConstPropertyName")
    private const val TabNavigatorKey = "HomeTabs"

    private val primaryTab = LibraryTab

    private val tabs = listOf(LibraryTab, UpdatesTab, LocalSourceTab, HistoryTab, MoreTab)

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val initialTab = remember {
            // Always launch into the library tab so the app is usable immediately without
            // triggering a cold load of the local source listing.
            LibraryTab
        }
        val bottomNavVisible by produceState(initialValue = true) {
            showBottomNavEvent.receiveAsFlow().collectLatest { value = it }
        }
        // Read before the scaffold content consumes the insets, so the value stays stable
        // no matter how much of the system bar the bottom bar is currently covering.
        val systemBarsBottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

        TabNavigator(
            tab = initialTab,
            key = TabNavigatorKey,
        ) { tabNavigator ->
            // Provide usable navigator to content screen
            CompositionLocalProvider(LocalNavigator provides navigator) {
                Scaffold(
                    startBar = {
                        if (isTabletUi()) {
                            NavigationRail {
                                tabs.fastForEach {
                                    NavigationRailItem(it)
                                }
                            }
                        }
                    },
                    bottomBar = {
                        if (!isTabletUi()) {
                            AnimatedVisibility(
                                visible = bottomNavVisible,
                                // Animate from the bar's own top edge: its bottom strip is the
                                // system bar padding, so growing from the bottom reveals an
                                // empty band first and the items only pop in at the end.
                                enter = expandVertically(
                                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                                    expandFrom = Alignment.Top,
                                ) + fadeIn(animationSpec = tween(160)),
                                exit = shrinkVertically(
                                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                                    shrinkTowards = Alignment.Top,
                                ) + fadeOut(animationSpec = tween(140)),
                            ) {
                                NavigationBar {
                                    tabs.fastForEach {
                                        NavigationBarItem(it)
                                    }
                                }
                            }
                        }
                    },
                    contentWindowInsets = WindowInsets(0),
                ) { contentPadding ->
                    // Hiding the bar is a scaffold animation: the height it gives back leaves
                    // the content padding frame by frame, so the content box grows as the bar
                    // collapses. Everything derived from that height - the fast scroller track,
                    // floating controls - is handed back what the bar released so it keeps the
                    // bar's resting position instead of breathing along with the animation.
                    val restingBottom = systemBarsBottom + BottomNavFabLift
                    // Keep the system bar strip reserved even while the bar is away. Inner
                    // scaffolds derive their own bottom padding from the insets we report as
                    // consumed; letting that report drop with the bar makes them grow a system
                    // bar's worth of padding mid-animation, which shifts the content box by an
                    // amount the compensation below cannot know about.
                    val contentBottom = maxOf(contentPadding.calculateBottomPadding(), systemBarsBottom)
                    val collapsedSpace = (restingBottom - contentBottom).coerceAtLeast(0.dp)
                    val layoutDirection = LocalLayoutDirection.current
                    val contentInsets = PaddingValues(
                        start = contentPadding.calculateStartPadding(layoutDirection),
                        end = contentPadding.calculateEndPadding(layoutDirection),
                        top = contentPadding.calculateTopPadding(),
                        bottom = contentBottom,
                    )
                    val consumedInsets = PaddingValues(
                        start = contentInsets.calculateStartPadding(layoutDirection),
                        end = contentInsets.calculateEndPadding(layoutDirection),
                        top = contentInsets.calculateTopPadding(),
                        bottom = systemBarsBottom,
                    )
                    CompositionLocalProvider(
                        LocalBottomNavFabPadding provides collapsedSpace,
                        LocalFastScrollerBottomInset provides collapsedSpace,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(contentInsets)
                                .consumeWindowInsets(consumedInsets),
                        ) {
                            AnimatedContent(
                                targetState = tabNavigator.current,
                                transitionSpec = {
                                    materialFadeThroughIn(
                                        initialScale = 1f,
                                        durationMillis = TabFadeDuration,
                                    ) togetherWith
                                        materialFadeThroughOut(durationMillis = TabFadeDuration)
                                },
                                label = "tabContent",
                            ) {
                                tabNavigator.saveableState(key = "currentTab", it) {
                                    it.Content()
                                }
                            }
                        }
                    }
                }
            }

            val goToPrimaryTab = { tabNavigator.current = primaryTab }

            BackHandler(
                enabled = tabNavigator.current::class != primaryTab::class,
                onBack = goToPrimaryTab,
            )

            LaunchedEffect(Unit) {
                launch {
                    librarySearchEvent.receiveAsFlow().collectLatest {
                        tabNavigator.current = LibraryTab
                        LibraryTab.search(it)
                    }
                }
                launch {
                    openTabEvent.receiveAsFlow().collectLatest {
                        tabNavigator.current = when (it) {
                            Tab.Local -> LocalSourceTab
                            is Tab.Library -> LibraryTab
                            Tab.Updates -> UpdatesTab
                            Tab.History -> HistoryTab
                            is Tab.Browse -> {
                                if (it.toExtensions) {
                                    BrowseTab.showExtension()
                                }
                                navigator.push(OnlineSourceCenterScreen)
                                tabNavigator.current
                            }
                            is Tab.More -> MoreTab
                        }

                        if (it is Tab.Library && it.mangaIdToOpen != null) {
                            navigator.push(MangaScreen(it.mangaIdToOpen))
                        }
                        if (it is Tab.More && it.toDownloads) {
                            navigator.push(DownloadQueueScreen)
                        }
                    }
                }
            }

            LaunchedEffect(tabNavigator.current::class) {
                // Launch tab is fixed to the library; no last-tab persistence.
            }
        }
    }

    @Composable
    private fun RowScope.NavigationBarItem(tab: eu.kanade.presentation.util.Tab) {
        val tabNavigator = LocalTabNavigator.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val selected = tabNavigator.current::class == tab::class
        NavigationBarItem(
            selected = selected,
            onClick = {
                if (!selected) {
                    tabNavigator.current = tab
                } else {
                    scope.launch { tab.onReselect(navigator) }
                }
            },
            icon = { NavigationIconItem(tab, selected) },
            label = {
                Text(
                    text = tab.options.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            alwaysShowLabel = true,
        )
    }

    @Composable
    fun NavigationRailItem(tab: eu.kanade.presentation.util.Tab) {
        val tabNavigator = LocalTabNavigator.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val selected = tabNavigator.current::class == tab::class
        NavigationRailItem(
            selected = selected,
            onClick = {
                if (!selected) {
                    tabNavigator.current = tab
                } else {
                    scope.launch { tab.onReselect(navigator) }
                }
            },
            icon = { NavigationIconItem(tab, selected) },
            label = {
                Text(
                    text = tab.options.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            alwaysShowLabel = true,
        )
    }

    @Composable
    private fun NavigationIconItem(tab: eu.kanade.presentation.util.Tab, selected: Boolean) {
        BadgedBox(
            badge = {
                when {
                    tab is UpdatesTab -> {
                        val count by produceState(initialValue = 0) {
                            val pref = Injekt.get<LibraryPreferences>()
                            combine(
                                pref.newShowUpdatesCount.changes(),
                                pref.newUpdatesCount.changes(),
                            ) { show, count -> if (show) count else 0 }
                                .collectLatest { value = it }
                        }
                        if (count > 0) {
                            Badge {
                                val desc = pluralStringResource(
                                    MR.plurals.notification_chapters_generic,
                                    count = count,
                                    count,
                                )
                                Text(
                                    text = count.toString(),
                                    modifier = Modifier.semantics { contentDescription = desc },
                                )
                            }
                        }
                    }
                    BrowseTab::class.isInstance(tab) -> {
                        val count by produceState(initialValue = 0) {
                            Injekt.get<SourcePreferences>().extensionUpdatesCount.changes()
                                .collectLatest { value = it }
                        }
                        if (count > 0) {
                            Badge {
                                val desc = pluralStringResource(
                                    MR.plurals.update_check_notification_ext_updates,
                                    count = count,
                                    count,
                                )
                                Text(
                                    text = count.toString(),
                                    modifier = Modifier.semantics { contentDescription = desc },
                                )
                            }
                        }
                    }
                }
            },
        ) {
            Box {
                Icon(
                    painter = tab.options.icon!!,
                    contentDescription = tab.options.title,
                )
                if (tab is MoreTab) {
                    IncognitoGlassesMark(
                        selected = selected,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }
    }

    /**
     * Drawn inside the empty upper half of the "more" glyph, above its right dot, in the icon's
     * own colour: a variant of the icon rather than a badge stacked on top of it. It fades and
     * scales in when incognito is switched on, and rides that dot's bounce when the tab is
     * selected or left, so the two never move apart. Mark and glyph mirror together with the
     * layout direction, so the same dot stays under the mark either way.
     */
    @Composable
    private fun IncognitoGlassesMark(
        selected: Boolean,
        modifier: Modifier = Modifier,
    ) {
        val incognito by produceState(
            initialValue = Injekt.get<BasePreferences>().incognitoMode.get(),
        ) {
            Injekt.get<BasePreferences>().incognitoMode.changes()
                .collectLatest { value = it }
        }
        val dotOffsetDp = rememberMoreDotOffsetDp(selected)

        AnimatedVisibility(
            visible = incognito,
            enter = fadeIn(animationSpec = tween(220)) +
                scaleIn(initialScale = 0.6f, animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(140)) +
                scaleOut(targetScale = 0.6f, animationSpec = tween(140)),
            modifier = modifier,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_glasses_24dp),
                contentDescription = stringResource(MR.strings.pref_incognito_mode),
                modifier = Modifier
                    .offset(x = 2.dp, y = (-2).dp + dotOffsetDp.dp)
                    .size(12.dp),
                tint = LocalContentColor.current,
            )
        }
    }

    /**
     * Drives the mark along the bounce of that dot. `anim_more_enter.xml` counts its 833 ms
     * timeline in both directions — forwards on selection, backwards on leaving — so the driver
     * does the same and [moreDotOffsetDp] turns that clock into the offset.
     */
    @Composable
    private fun rememberMoreDotOffsetDp(selected: Boolean): Float {
        val time by animateFloatAsState(
            targetValue = if (selected) MoreGlyphDurationMillis else 0f,
            animationSpec = tween(durationMillis = MoreGlyphDurationMillis.toInt(), easing = LinearEasing),
            label = "moreDotOffset",
        )
        return moreDotOffsetDp(time)
    }

    /**
     * Vertical offset of the dot at [timeMillis] of that timeline, in dp. Read off the same file:
     * it holds until 217 ms, rises 128 units (1080 units across 24 dp, so 2.84 dp) by 317 ms,
     * dips 36 units past its resting spot by 450 ms and settles back by 517 ms.
     */
    private fun moreDotOffsetDp(timeMillis: Float): Float {
        val units = when {
            timeMillis < 217f -> 0f
            timeMillis < 317f -> lerp(0f, -128f, moreDotEase((timeMillis - 217f) / 100f))
            timeMillis < 450f -> lerp(-128f, 36f, moreDotEase((timeMillis - 317f) / 133f))
            timeMillis < 517f -> lerp(36f, 0f, moreDotEase((timeMillis - 450f) / 67f))
            else -> 0f
        }
        return units / 45f
    }

    private fun moreDotEase(fraction: Float): Float =
        MoreDotEasing.transform(fraction.coerceIn(0f, 1f))

    /** Timeline length of `anim_more_enter.xml`, in milliseconds. */
    @Suppress("ConstPropertyName")
    private const val MoreGlyphDurationMillis = 833f

    /**
     * Easing of every step of that bounce, from
     * `pathInterpolator: M 0,0 c0.5,0 0.5,1 1,1`.
     */
    private val MoreDotEasing = CubicBezierEasing(0.5f, 0f, 0.5f, 1f)

    suspend fun search(query: String) {
        librarySearchEvent.send(query)
    }

    suspend fun openTab(tab: Tab) {
        openTabEvent.send(tab)
    }

    suspend fun showBottomNav(show: Boolean) {
        showBottomNavEvent.send(show)
    }

    sealed interface Tab {
        data object Local : Tab
        data class Library(val mangaIdToOpen: Long? = null) : Tab
        data object Updates : Tab
        data object History : Tab
        data class Browse(val toExtensions: Boolean = false) : Tab
        data class More(val toDownloads: Boolean) : Tab
    }
}
