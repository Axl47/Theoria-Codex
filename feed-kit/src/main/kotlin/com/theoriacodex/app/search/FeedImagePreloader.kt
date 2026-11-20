package com.theoriacodex.app.search

import android.content.Context
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import com.theoriacodex.app.media.isMediaNetworkMetered
import com.theoriacodex.app.media.previewMediaDeliveryPlan
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Loads the card images just past the end of the visible feed, so a card scrolling in usually finds its image ready.
 * [entries] holds one post per grid item, null for items that are not posts. A preload is the same request the card
 * makes, so the card finds it in the memory cache. Unmetered networks only, and never for cards already passed.
 */
@Composable
internal fun FeedImagePreloadEffect(entries: List<Post?>, gridState: LazyStaggeredGridState) {
    val context = LocalContext.current
    val screenWidthPx = LocalWindowInfo.current.containerSize.width
    val currentEntries by rememberUpdatedState(entries)
    LaunchedEffect(gridState, screenWidthPx) {
        val considered = HashSet<PostId>()
        val inFlight = ArrayList<Disposable>()
        try {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .distinctUntilChanged()
                .collect { lastVisible ->
                    // Checked per batch: the network can become metered while the grid stays open.
                    if (lastVisible == null || context.isMediaNetworkMetered()) return@collect
                    inFlight.removeAll(Disposable::isDisposed)
                    currentEntries.subList(
                        (lastVisible + 1).coerceAtMost(currentEntries.size),
                        (lastVisible + 1 + PRELOAD_CARDS_AHEAD).coerceAtMost(currentEntries.size),
                    ).filterNotNull().filter { post -> considered.add(post.id) }.forEach { post ->
                        preloadRequest(context, post, screenWidthPx)?.let { inFlight += context.imageLoader.enqueue(it) }
                    }
                }
        } finally {
            inFlight.forEach(Disposable::dispose)
        }
    }
}

private fun preloadRequest(context: Context, post: Post, screenWidthPx: Int): ImageRequest? {
    val location = previewMediaDeliveryPlan(post).primary?.location ?: return null
    val decodeSize = feedPreviewDecodeSize(screenWidthPx, previewAspectRatio(post))
    return buildFeedImageRequest(context, location, post.id.source, decodeSize)
        .newBuilder()
        // Null keeps this out of the memory cache key, so the card's own request still matches.
        .setParameter(FEED_PRELOAD_PARAMETER, true, memoryCacheKey = null)
        .build()
}

private const val FEED_PRELOAD_PARAMETER = "theoria.feedPreload"
private const val PRELOAD_CARDS_AHEAD = 12
