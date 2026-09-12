package com.theoriacodex.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onDestinationSelected: (TopLevelDestination) -> Unit,
    windowWidthDp: Float = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density,
    content: @Composable (Modifier) -> Unit,
) {
    val rail = useNavigationRail(windowWidthDp)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (navigationVisible && !rail) TheoriaBottomNavigation(
                selectedIndex, bottomBarHeight, bottomBarIconSize, bottomBarWindowInsets, onDestinationSelected,
            )
        },
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize()) {
            if (navigationVisible && rail) NavigationRail(
                modifier = Modifier.testTag("navigation-rail").verticalScroll(rememberScrollState()),
            ) {
                TopLevelDestination.entries.forEachIndexed { index, destination ->
                    NavigationRailItem(
                        selected = selectedIndex == index,
                        onClick = { onDestinationSelected(destination) },
                        icon = { Icon(destination.icon(), destination.label, Modifier.size(24.dp)) },
                        label = { Text(destination.label) },
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                content(Modifier.fillMaxSize().padding(padding))
            }
        }
    }
}
