package com.theoriacodex.app.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/** A cached page-matching preview can paint immediately without competing for network bandwidth. */
@Composable
internal fun ViewerLoadingPreview(post: Post, media: ImageRef, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(post.id, post.preview, media) {
        viewerLoadingPreviewLocation(post, media)?.let { location ->
            MediaRequestFactory.imageRequest(context, location, post.id.source, crossfade = false)
                .newBuilder().networkCachePolicy(CachePolicy.DISABLED).build()
        }
    }
    if (request != null) AsyncImage(
        model = request, contentDescription = null,
        modifier = modifier.fillMaxSize(), contentScale = ContentScale.Fit,
    )
}

internal fun viewerLoadingPreviewLocation(post: Post, media: ImageRef): String? {
    // A gallery's cover must never be presented as a different media page.
    if (post.media.indexOf(media) > 0) return media.progressiveUrls.firstOrNull()
    if (isAnimatedImageMediaRef(post.preview) || isVideoMediaRef(post.preview)) return null
    return post.preview.localPath ?: post.preview.url ?: post.preview.progressiveUrls.firstOrNull()
}
