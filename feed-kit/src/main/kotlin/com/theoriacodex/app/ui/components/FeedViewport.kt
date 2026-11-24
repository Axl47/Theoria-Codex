package com.theoriacodex.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutBoundsHolder
import androidx.compose.ui.layout.layoutBounds
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

@Stable
internal class FeedViewport(val bounds: LayoutBoundsHolder, val lifecycleStarted: State<Boolean>)

internal val LocalFeedViewport = staticCompositionLocalOf<FeedViewport?> { null }

@Composable
internal fun rememberFeedViewport(): FeedViewport {
    val started = rememberLifecycleStarted()
    return remember(started) { FeedViewport(LayoutBoundsHolder(), started) }
}

@Composable
internal fun rememberLifecycleStarted(): State<Boolean> {
    val lifecycleState = LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return remember(lifecycleState) { derivedStateOf { lifecycleState.value.isAtLeast(Lifecycle.State.STARTED) } }
}

@Composable
fun ProvideFeedViewport(content: @Composable () -> Unit) {
    val viewport = rememberFeedViewport()
    Box(Modifier.layoutBounds(viewport.bounds), propagateMinConstraints = true) {
        CompositionLocalProvider(LocalFeedViewport provides viewport, content = content)
    }
}
