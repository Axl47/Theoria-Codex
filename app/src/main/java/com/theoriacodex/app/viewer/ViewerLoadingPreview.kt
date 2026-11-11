package com.theoriacodex.app.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.CachePolicy
import com.theoriacodex.app.media.isAnimatedImageMediaRef
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post

/** Post dimensions describe its first page; other gallery pages may have any shape. */
internal fun viewerMediaAspectRatio(post: Post, media: ImageRef): Float? {
    if (post.media.indexOf(media) > 0) return null
    val width = post.width?.takeIf { it > 0 } ?: return null
    val height = post.height?.takeIf { it > 0 } ?: return null
    return width.toFloat() / height.toFloat()
}

/** Occupies exactly where fitted media will appear, or the whole parent when its shape is unknown. */
internal fun Modifier.viewerFittedMedia(aspectRatio: Float?): Modifier =
    if (aspectRatio == null) fillMaxSize() else aspectRatio(aspectRatio)

/**
 * A cached page-matching preview can paint immediately without competing for network bandwidth.
 * Thumbnails can differ from their media, so it fills the media's own bounds rather than the page's.
 */
@Composable
internal fun ViewerLoadingPreview(post: Post, media: ImageRef, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(post.id, post.preview, media) {
        viewerLoadingPreviewLocation(post, media)?.let { location ->
            MediaRequestFactory.imageRequest(context, location, post.id.source, crossfade = false)
                .newBuilder().networkCachePolicy(CachePolicy.DISABLED).build()
        }
    }
    val aspectRatio = viewerMediaAspectRatio(post, media)
    if (request != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request, contentDescription = null,
            modifier = Modifier.viewerFittedMedia(aspectRatio).then(modifier),
            contentScale = if (aspectRatio == null) ContentScale.Fit else ContentScale.Crop,
        )
    }
}

internal fun viewerLoadingPreviewLocation(post: Post, media: ImageRef): String? {
    // A gallery's cover must never be presented as a different media page; its own small thumbnail may.
    if (post.media.indexOf(media) > 0) return media.thumbnailUrl ?: media.progressiveUrls.firstOrNull()
    if (isAnimatedImageMediaRef(post.preview) || isVideoMediaRef(post.preview)) return null
    return post.preview.localPath ?: post.preview.url ?: post.preview.progressiveUrls.firstOrNull()
}
