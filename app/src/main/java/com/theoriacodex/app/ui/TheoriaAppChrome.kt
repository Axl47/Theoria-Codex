package com.theoriacodex.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onDestinationSelected: (TopLevelDestination) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.height(height),
        windowInsets = windowInsets,
    ) {
        TopLevelDestination.entries.forEachIndexed { index, destination ->
            NavigationBarItem(
                selected = selectedIndex == index,
                onClick = { onDestinationSelected(destination) },
                icon = {
                    val icon = when (destination) {
                        TopLevelDestination.Search -> Icons.Default.Search
                        TopLevelDestination.Recents -> Icons.Default.History
                        TopLevelDestination.ForYou -> Icons.Default.Favorite
                        TopLevelDestination.Codex -> Icons.Default.Collections
                        TopLevelDestination.Settings -> Icons.Default.Settings
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = destination.label,
                        modifier = Modifier.size(iconSize),
                    )
                },
                label = null,
                alwaysShowLabel = false,
            )
        }
    }
}
