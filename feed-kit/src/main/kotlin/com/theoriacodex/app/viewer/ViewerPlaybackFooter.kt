package com.theoriacodex.app.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.domain.model.SourceKey

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

/**
 * No crossfade: the preview leaves as the image arrives, so fading in would dip through the background.
 * Decodes to hardware bitmaps so a full-size page is not uploaded to the GPU by RenderThread as it settles;
 * callers that read pixels (the artwork accent) opt back into software with `allowHardware(false)`.
 */
fun buildViewerImageRequest(
    context: Context,
    url: String,
    sourceKey: SourceKey,
    staticAnimatedWebPFrame: Boolean = false,
    targetWidthPx: Int? = null,
    targetHeightPx: Int? = null,
): ImageRequest = MediaRequestFactory.imageRequest(
    context = context,
    url = url,
    sourceKey = sourceKey,
    crossfade = false,
    allowHardware = true,
    staticAnimatedWebPFrame = staticAnimatedWebPFrame,
    targetWidthPx = targetWidthPx,
    targetHeightPx = targetHeightPx,
)
