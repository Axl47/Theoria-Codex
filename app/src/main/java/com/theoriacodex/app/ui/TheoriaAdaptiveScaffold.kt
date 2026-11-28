package com.theoriacodex.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.app.ui.undo.UndoPill

internal val screenBottomInsets: WindowInsets
    @Composable get() = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Bottom)

@Composable
internal fun TheoriaAdaptiveScaffold(
    snackbarHostState: SnackbarHostState,
    snackbarLift: Dp = 0.dp,
    containerColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable (Modifier) -> Unit,
) {
    val lift by animateDpAsState(snackbarLift, tween(TheoriaMotion.SCREEN_MS), label = "snackbarLift")
    Scaffold(
        containerColor = containerColor,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = {
            Column(Modifier.padding(bottom = lift), horizontalAlignment = Alignment.CenterHorizontally) {
                UndoPill(UndoAnchor.App)
                SnackbarHost(snackbarHostState)
            }
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        val screenPadding = PaddingValues(
            start = padding.calculateStartPadding(direction),
            top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(direction),
        )
        content(Modifier.fillMaxSize().padding(screenPadding))
    }
}

@Composable
internal fun TheoriaNavigationChrome(
    selectedIndex: Int,
    bottomBarHeight: Dp,
    bottomBarIconSize: Dp,
    bottomBarWindowInsets: WindowInsets,
    destinations: List<TopLevelDestination>,
    onDestinationSelected: (TopLevelDestination) -> Unit,
    marked: Set<TopLevelDestination> = emptySet(),
    windowWidthDp: Float = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density,
    content: @Composable (Modifier) -> Unit,
) {
    val rail = useNavigationRail(windowWidthDp)
    Row(modifier = Modifier.fillMaxSize()) {
        if (rail) NavigationRail(
            modifier = Modifier.testTag("navigation-rail"),
            windowInsets = screenBottomInsets,
        ) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                destinations.forEachIndexed { index, destination ->
                    NavigationRailItem(
                        selected = selectedIndex == index,
                        onClick = { onDestinationSelected(destination) },
                        icon = { destination.TabIcon(destination in marked, 24.dp) },
                        label = { Text(destination.label) },
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f).fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                content(Modifier.fillMaxSize().then(if (rail) Modifier.padding(screenBottomInsets.asPaddingValues()) else Modifier))
            }
            if (!rail) TheoriaBottomNavigation(
                selectedIndex, bottomBarHeight, bottomBarIconSize, bottomBarWindowInsets, destinations, onDestinationSelected, marked,
            )
        }
    }
}

internal suspend fun navigateTopLevelDestination(
    navController: androidx.navigation.NavHostController,
    pager: androidx.compose.foundation.pager.PagerState,
    destination: TopLevelDestination,
) {
    val target = TopLevelDestination.entries.indexOf(destination)
    if (navController.currentBackStackEntry?.destination?.route != AppRoute.Home) {
        navController.navigate(AppRoute.Home) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        pager.scrollToPage(target)
    } else if (tabTap(destination, pager.targetPage, pager.settledPage, pager.isScrollInProgress) == TabTap.Move) {
        if (pager.currentPage == target && pager.currentPageOffsetFraction == 0f) pager.scrollToPage(target)
        else pager.animateScrollToPage(target, animationSpec = tween(TheoriaMotion.SCREEN_MS))
    }
}
