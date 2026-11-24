package com.theoriacodex.app.ui

import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.theme.TheoriaNightTheme

@Composable
internal fun AppContainerStartupSurface(
    failed: Boolean,
    onRetry: () -> Unit,
) {
    TheoriaNightTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (failed) {
                    Text("Saved data could not be opened safely.")
                    Button(onClick = onRetry) { Text("Retry") }
                } else {
                    CircularProgressIndicator()
                    Text("Preparing your library…")
                }
            }
        }
    }
}

@Composable
internal fun TheoriaBottomNavigation(
    selectedIndex: Int,
    height: Dp,
    iconSize: Dp,
    windowInsets: WindowInsets,
    destinations: List<TopLevelDestination>,
    onDestinationSelected: (TopLevelDestination) -> Unit,
    marked: Set<TopLevelDestination> = emptySet(),
) {
    NavigationBar(
        modifier = Modifier.height(height),
        windowInsets = windowInsets,
    ) {
        destinations.forEachIndexed { index, destination ->
            NavigationBarItem(
                selected = selectedIndex == index,
                onClick = { onDestinationSelected(destination) },
                icon = { destination.TabIcon(destination in marked, iconSize) },
                label = null,
                alwaysShowLabel = false,
            )
        }
    }
}

@Composable
internal fun TopLevelDestination.TabIcon(marked: Boolean, size: Dp) {
    BadgedBox(
        badge = { if (marked) Badge(Modifier.testTag(NEW_TAB_DOT_TAG)) },
        modifier = Modifier.semantics { if (marked) stateDescription = "New" },
    ) { Icon(icon(), label, Modifier.size(size)) }
}

internal const val NEW_TAB_DOT_TAG = "tab-new-dot"

internal fun TopLevelDestination.icon() = when (this) {
    TopLevelDestination.Search -> Icons.Default.Search
    TopLevelDestination.ForYou -> Icons.Default.AutoAwesome
    TopLevelDestination.Library -> Icons.Default.Collections
    TopLevelDestination.Series -> Icons.AutoMirrored.Filled.MenuBook
}
