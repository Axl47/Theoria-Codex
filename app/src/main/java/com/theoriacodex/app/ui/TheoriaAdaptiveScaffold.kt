package com.theoriacodex.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.foundation.layout.Column
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.app.ui.undo.UndoPill
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.tween
import androidx.compose.material3.NavigationRail
import com.theoriacodex.app.ui.motion.TheoriaMotion
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One stable content slot preserves navigation owners and scroll state during window resizing. */
@Composable
internal fun TheoriaAdaptiveScaffold(
    navigationVisible: Boolean,
    selectedIndex: Int,
    bottomBarHeight: Dp,
    bottomBarIconSize: Dp,
    bottomBarWindowInsets: WindowInsets,
    snackbarHostState: SnackbarHostState,
    destinations: List<TopLevelDestination>,
    onDestinationSelected: (TopLevelDestination) -> Unit,
    marked: Set<TopLevelDestination> = emptySet(),
    containerColor: Color = MaterialTheme.colorScheme.background,
    windowWidthDp: Float = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density,
    content: @Composable (Modifier) -> Unit,
) {
    val rail = useNavigationRail(windowWidthDp)
    Scaffold(
        containerColor = containerColor,
        snackbarHost = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                UndoPill(UndoAnchor.App)
                SnackbarHost(snackbarHostState)
            }
        },
        bottomBar = {
            if (navigationVisible && !rail) TheoriaBottomNavigation(
                selectedIndex, bottomBarHeight, bottomBarIconSize, bottomBarWindowInsets, destinations, onDestinationSelected, marked,
            )
        },
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize()) {
            if (navigationVisible && rail) NavigationRail(
                modifier = Modifier.testTag("navigation-rail"),
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
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                content(Modifier.fillMaxSize().padding(padding))
            }
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
    } else if (if (pager.isScrollInProgress) pager.targetPage != target else pager.settledPage != target) {
        // A scroll to another tab passes over this one, where currentPage already names it; one already heading
        // here is left to finish.
        pager.animateScrollToPage(target, animationSpec = tween(TheoriaMotion.SCREEN_MS))
    }
}
