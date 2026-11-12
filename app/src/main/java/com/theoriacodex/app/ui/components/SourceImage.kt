package com.theoriacodex.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.domain.model.SourceKey

/**
 * A small decorative image from [source] (avatar, stamp), requested at its drawn size with the source's media
 * headers; Pixiv's image host refuses requests without its Referer.
 */
@Composable
internal fun SourceImage(
    source: SourceKey,
    url: String,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val request = remember(context, source, url, sizePx) {
        MediaRequestFactory.imageRequest(
            context, url, source, crossfade = true, targetWidthPx = sizePx, targetHeightPx = sizePx,
        )
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier.size(size).clip(shape),
        contentScale = contentScale,
    )
}
