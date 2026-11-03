package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import com.theoriacodex.app.ui.motion.TheoriaMotion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theoriacodex.domain.model.Post

internal const val VIEWER_PAGINATION_PREFETCH_RATIO = 0.8f

internal val LocalViewerPostStripInset = staticCompositionLocalOf { 0.dp }

/** Small enough that a grown thumbnail never overlaps its neighbours, in the strip or in the related shelf. */
private const val SELECTED_THUMBNAIL_GROWTH = 0.13f
private const val RESTING_THUMBNAIL_ALPHA = 0.72f

/** Seven centered slots share equal gaps between thumbnails and at both viewport edges. */
@Composable
internal fun ViewerPostStrip(
    posts: List<Post>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onLoadMore: () -> Unit = {},
    onInteraction: () -> Unit = {},
) {
    val loadMore by rememberUpdatedState(onLoadMore)
    val interact by rememberUpdatedState(onInteraction)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    LaunchedEffect(selectedIndex) { list.animateScrollToItem(selectedIndex) }
    LaunchedEffect(list, posts.size) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged().collect { lastVisible ->
                if (lastVisible != null && lastVisible >= posts.lastIndex * VIEWER_PAGINATION_PREFETCH_RATIO) loadMore()
            }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect { if (list.isScrollInProgress) interact() }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cell = ((maxWidth - 32.dp) / 7).coerceIn(1.dp, 46.dp)
        val gap = ((maxWidth - cell * 7) / 8).coerceAtLeast(0.dp)
        LazyRow(
            state = list,
            contentPadding = PaddingValues(horizontal = (maxWidth - cell) / 2, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            itemsIndexed(posts, key = { _, post -> "${post.id.source}:${post.id.sourcePostId}" }) { index, post ->
                ViewerPostThumbnail(post, index == selectedIndex, Modifier.size(cell)) { onSelect(index) }
            }
        }
    }
}

@Composable
internal fun ViewerPostThumbnail(post: Post, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val location = post.preview.localPath ?: post.preview.url
    val request = remember(context, location, post.id.source) {
        location?.let { buildViewerImageRequest(context, it, post.id.source, staticAnimatedWebPFrame = true) }
    }
    val shape = RoundedCornerShape(9.dp)
    val accent = LocalViewerAccent.current
    val emphasis by animateFloatAsState(if (selected) 1f else 0f, tween(TheoriaMotion.MEDIUM_MS), label = "Viewer thumbnail emphasis")
    // Emphasis is drawn, not laid out: the strip's slots and its centering stay put as the selection moves.
    Box(modifier.semantics { this.selected = selected }.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val scale = 1f + SELECTED_THUMBNAIL_GROWTH * emphasis
                    scaleX = scale
                    scaleY = scale
                    alpha = RESTING_THUMBNAIL_ALPHA + (1f - RESTING_THUMBNAIL_ALPHA) * emphasis
                }
                .clip(shape)
                .background(Color.White.copy(alpha = 0.12f))
                .then(if (selected) Modifier.border(2.dp, accent, shape).padding(3.dp) else Modifier),
        ) {
            AsyncImage(request, "Open post ${post.id.sourcePostId}", Modifier.fillMaxSize().clip(RoundedCornerShape(if (selected) 6.dp else 9.dp)), contentScale = ContentScale.Crop)
        }
    }
}
