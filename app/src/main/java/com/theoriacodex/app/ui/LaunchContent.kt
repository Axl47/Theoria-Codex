package com.theoriacodex.app.ui

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

@Stable
internal class LaunchContent {
    var drawn by mutableStateOf(emptySet<TopLevelDestination>())
        private set

    fun markDrawn(destination: TopLevelDestination) {
        if (destination !in drawn) drawn += destination
    }
}

private val LocalLaunchContent = staticCompositionLocalOf<LaunchContent?> { null }

@Composable
internal fun ReportsLaunchContent(launchTab: () -> TopLevelDestination?, content: @Composable () -> Unit) {
    val launch = remember { LaunchContent() }
    ReportDrawnWhen { launchTab()?.let { it in launch.drawn } == true }
    CompositionLocalProvider(LocalLaunchContent provides launch, content = content)
}

@Composable
internal fun ReportLaunchContent(destination: TopLevelDestination, drawn: Boolean = true) {
    val launch = LocalLaunchContent.current ?: return
    if (drawn) SideEffect { launch.markDrawn(destination) }
}
