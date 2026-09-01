package eu.kanade.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.isTabletUi

/**
 * Height of the bottom navigation bar, matching the row inside
 * [tachiyomi.presentation.core.components.material.NavigationBar].
 */
private val BottomNavBarHeight: Dp = 80.dp

/**
 * How far the bottom-start floating control has to be lifted to sit 16 dp above the bottom
 * navigation bar's resting position, so that it lines up with the same control on screens
 * that have no bar of their own.
 *
 * Tablet UI moves the navigation into a side rail, so there is no bar to clear.
 */
val BottomNavFabLift: Dp
    @Composable
    @ReadOnlyComposable
    get() = if (isTabletUi()) 0.dp else BottomNavBarHeight

/**
 * Extra bottom padding the bottom-start floating control needs to stay anchored to the
 * bottom navigation bar's resting position.
 *
 * [eu.kanade.tachiyomi.ui.home.HomeScreen] provides it. Hiding the bar is a scaffold bottom
 * bar animation, so the bar's height leaves the scaffold content padding frame by frame;
 * without the lift the control sinks along with it, and lifting it in a single step as soon
 * as the animation starts makes it jump ahead of the bar.
 *
 * Defaults to zero outside the home scaffold, where the padding around the control already
 * keeps it clear of the system bar.
 */
val LocalBottomNavFabPadding: ProvidableCompositionLocal<Dp> =
    staticCompositionLocalOf { 0.dp }
