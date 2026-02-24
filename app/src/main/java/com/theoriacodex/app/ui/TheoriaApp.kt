package com.theoriacodex.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.theoriacodex.app.explore.ExploreScreen
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.app.search.SearchScreen

enum class TopLevelDestination(val route: String, val label: String) {
    Search("search", "Search"),
    Explore("explore", "Explore"),
    Codex("codex", "Codex"),
    Settings("settings", "Settings")
}

@Composable
fun TheoriaApp() {
    val navController = rememberNavController()
    val searchCoordinator = remember { SearchCoordinator() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    MaterialTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                val icon = when (destination) {
                                    TopLevelDestination.Search -> Icons.Default.Search
                                    TopLevelDestination.Explore -> Icons.Default.Explore
                                    TopLevelDestination.Codex -> Icons.Default.Collections
                                    TopLevelDestination.Settings -> Icons.Default.Settings
                                }
                                Icon(imageVector = icon, contentDescription = destination.label)
                            },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = TopLevelDestination.Search.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(TopLevelDestination.Search.route) {
                    SearchScreen(coordinator = searchCoordinator)
                }
                composable(TopLevelDestination.Explore.route) {
                    ExploreScreen(
                        coordinator = searchCoordinator,
                        onNavigateToSearch = {
                            navController.navigate(TopLevelDestination.Search.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(TopLevelDestination.Codex.route) { PlaceholderScreen("Codex") }
                composable(TopLevelDestination.Settings.route) { PlaceholderScreen("Settings") }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "$title screen scaffold", style = MaterialTheme.typography.titleLarge)
    }
}
