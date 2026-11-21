package com.theoriacodex.app.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The tallest timeline footer (portrait's two lines): gestures on an animated page leave this much to scrubbing, plus the strip below it. */
val VIEWER_TIMELINE_HEIGHT = 72.dp

@Composable
fun ViewerPlaybackFooter(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            modifier = modifier.fillMaxWidth().padding(bottom = LocalViewerPostStripInset.current).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}
