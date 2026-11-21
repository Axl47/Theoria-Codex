package com.theoriacodex.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.theoriacodex.app.media.PostMediaKind
import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.mediaKind
import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.delay

@Composable
internal fun ViewerMediaOverviewGrid(
    post: Post,
    items: List<ViewerMediaOverviewItem>,
    selectedMediaIndex: Int,
    onMediaSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = remember(items, selectedMediaIndex) { items.indexOfFirst { it.mediaIndex == selectedMediaIndex }.coerceAtLeast(0) }
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = selected)
    com.theoriacodex.app.ui.components.AdaptiveMediaGrid(
        state = grid, modifier = modifier.background(Color.Black).padding(horizontal = 10.dp),
        contentPadding = PaddingValues(top = 64.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.mediaIndex }) { item ->
            ViewerOverviewCell(post.id.source, item, item.mediaIndex == selectedMediaIndex) { onMediaSelected(item.mediaIndex) }
        }
    }
}

@Composable
private fun ViewerOverviewCell(source: SourceKey, item: ViewerMediaOverviewItem, selected: Boolean, onSelect: () -> Unit) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    Box(Modifier.fillMaxWidth().aspectRatio(0.72f)
        .border(if (selected) 2.dp else 1.dp, border).background(Color.Black).clickable(onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        ViewerOverviewPoster(source, item)
        Text("${item.mediaIndex + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 6.dp, vertical = 3.dp))
        item.kind.overviewBadgeLabel?.let { label ->
            ViewerOverviewBadge(item.kind, label, Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** The thumbnail at once, then the original over it, since the thumbnail is soft at this tile size. */
@Composable
private fun ViewerOverviewPoster(source: SourceKey, item: ViewerMediaOverviewItem) {
    val thumbnail = rememberViewerThumbnailRequest(source, item)
    if (thumbnail == null) {
        Text("Preview unavailable", color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        return
    }
    val imageModifier = Modifier.fillMaxSize().padding(4.dp)
    AsyncImage(thumbnail, "Media ${item.mediaIndex + 1}: ${item.kind.overviewAccessibilityLabel}", imageModifier, contentScale = ContentScale.Fit)
    if (item.posterLocation != item.thumbnailLocation) ViewerOverviewOriginal(source, item, imageModifier)
}

/** Waits for the tile to stay on screen, so a fast scroll starts no heavy downloads, then fades in over the thumbnail. */
@Composable
private fun ViewerOverviewOriginal(source: SourceKey, item: ViewerMediaOverviewItem, modifier: Modifier) {
    var rested by remember(item.posterLocation) { mutableStateOf(false) }
    LaunchedEffect(item.posterLocation) {
        delay(VIEWER_SHARP_SETTLE_MS)
        rested = true
    }
    val original = rememberViewerPosterRequest(source, item)
    val fadingIn = remember(original) { original?.newBuilder()?.crossfade(true)?.build() }
    if (rested && fadingIn != null) AsyncImage(fadingIn, null, modifier, contentScale = ContentScale.Fit)
}

/** How long a page must stay put before its heavy original replaces its thumbnail. */
internal const val VIEWER_SHARP_SETTLE_MS = 250L

/**
 * Once the Viewer rests on a multi-page post, warms all its page thumbnails into the disk cache, nearest pages
 * first, so the page grid, page strip, seek preview and loading pages paint from disk instead of the network.
 * Leaving the post cancels the warm; turning pages within it does not restart it.
 */
@Composable
internal fun WarmViewerPageThumbnails(postId: PostId, items: List<ViewerMediaOverviewItem>, mediaIndex: Int) {
    val context = LocalContext.current
    val currentPage by rememberUpdatedState(mediaIndex)
    LaunchedEffect(postId, items) {
        if (!viewerMediaOverviewAvailable(items)) return@LaunchedEffect
        delay(VIEWER_SHARP_SETTLE_MS)
        warmViewerPageThumbnails(context, postId.source, items, currentPage)
    }
}

/** The still that stands for one page wherever the Viewer lists a post's pages; null when the page has none. */
@Composable
internal fun rememberViewerPosterRequest(source: SourceKey, item: ViewerMediaOverviewItem): ImageRequest? =
    rememberViewerOverviewRequest(source, item, item.posterLocation)

/** The small stand-in for page pickers (strip, seek preview, gallery grid); falls back to the poster when the provider has none. */
@Composable
internal fun rememberViewerThumbnailRequest(source: SourceKey, item: ViewerMediaOverviewItem, decodeEdgePx: Int? = null): ImageRequest? =
    rememberViewerOverviewRequest(source, item, item.thumbnailLocation, decodeEdgePx)

@Composable
private fun rememberViewerOverviewRequest(
    source: SourceKey,
    item: ViewerMediaOverviewItem,
    location: String?,
    decodeEdgePx: Int? = null,
): ImageRequest? {
    val context = LocalContext.current
    return remember(context, location, item.kind, source, decodeEdgePx) {
        location?.let {
            buildViewerImageRequest(
                context, it, source,
                staticAnimatedWebPFrame = shouldDecodeStaticOverviewFrame(item.kind),
                targetWidthPx = decodeEdgePx,
                targetHeightPx = decodeEdgePx,
            )
        }
    }
}

@Composable
private fun ViewerOverviewBadge(kind: ViewerMediaKind, label: String, modifier: Modifier) {
    Row(modifier.background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 7.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (kind == ViewerMediaKind.VIDEO) Icon(Icons.Default.PlayArrow, null, tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

internal fun shouldDecodeStaticOverviewFrame(kind: ViewerMediaKind): Boolean = !kind.isAnimatedImage

private val ViewerMediaKind.isAnimatedImage
    get() = this == ViewerMediaKind.ANIMATED_IMAGE || this == ViewerMediaKind.ANIMATED_WEBP

/** The overview tile's corner badge; a still image has none. */
internal val ViewerMediaKind.overviewBadgeLabel: String?
    get() = when (this) {
        ViewerMediaKind.IMAGE -> null
        ViewerMediaKind.ANIMATED_IMAGE, ViewerMediaKind.ANIMATED_WEBP -> "Animated"
        ViewerMediaKind.GIF -> "GIF"
        ViewerMediaKind.UGOIRA -> "Ugoira"
        ViewerMediaKind.VIDEO -> "Video"
        ViewerMediaKind.UNKNOWN -> "Media"
    }

private val ViewerMediaKind.overviewAccessibilityLabel: String
    get() = when (this) {
        ViewerMediaKind.IMAGE -> "still image"
        ViewerMediaKind.ANIMATED_IMAGE, ViewerMediaKind.ANIMATED_WEBP -> "animated image"
        ViewerMediaKind.GIF -> "GIF"
        ViewerMediaKind.UGOIRA -> "Ugoira animation"
        ViewerMediaKind.VIDEO -> "video"
        ViewerMediaKind.UNKNOWN -> "media"
    }

data class ViewerMediaOverviewItem(
    val mediaIndex: Int,
    val media: ImageRef,
    val kind: ViewerMediaKind,
    val posterLocation: String?,
    // A smaller stand-in for pickers; equals the poster when the provider has none.
    val thumbnailLocation: String? = posterLocation,
)

/** The overview's tiles for [page], from the media its state already lists and classifies. */
fun viewerMediaOverviewItems(page: ViewerPageState): List<ViewerMediaOverviewItem> {
    val post = page.post
    return page.media.mapIndexed { index, state ->
        val media = state.ref
        val kind = state.kind
        val posterLocation = viewerMediaOverviewPosterLocation(post, media, kind)
        // A locally saved page keeps using its local file.
        val thumbnail = media.thumbnailUrl?.takeIf {
            kind == ViewerMediaKind.IMAGE && media.localPath.isNullOrBlank() && it.isNotBlank()
        }
        ViewerMediaOverviewItem(
            mediaIndex = index,
            media = media,
            kind = kind,
            posterLocation = posterLocation,
            thumbnailLocation = thumbnail ?: posterLocation,
        )
    }
}

internal fun viewerMediaOverviewAvailable(items: List<ViewerMediaOverviewItem>): Boolean {
    return items.size > 1
}

private fun viewerMediaOverviewPosterLocation(post: Post, media: ImageRef, kind: ViewerMediaKind): String? {
    val mediaPoster = when (kind) {
        ViewerMediaKind.IMAGE -> {
            if (post.id.source == SourceKey.HITOMI) {
                overviewWebPImageLocation(media) ?: viewerPrefetchImageLocation(post, media)
            } else {
                viewerPrefetchImageLocation(post, media)
            }
        }
        ViewerMediaKind.ANIMATED_IMAGE, ViewerMediaKind.ANIMATED_WEBP -> staticOverviewImageLocation(media)
        ViewerMediaKind.GIF,
        ViewerMediaKind.UGOIRA,
        ViewerMediaKind.VIDEO,
        ViewerMediaKind.UNKNOWN,
        -> null
    }
    if (mediaPoster != null) return mediaPoster
    if (media == post.preview) return null
    return staticOverviewImageLocation(post.preview)
}

private fun staticOverviewImageLocation(ref: ImageRef): String? {
    if (mediaKind(ref) != PostMediaKind.IMAGE || isGifMediaRef(ref)) return null
    val locations = buildList {
        ref.localPath?.takeIf(String::isNotBlank)?.let(::add)
        addAll(ref.progressiveUrls.filter(String::isNotBlank))
        ref.url?.takeIf(String::isNotBlank)?.let(::add)
        addAll(ref.fallbackUrls.filter(String::isNotBlank))
    }.distinct()
    if (!ref.isAnimated) return locations.firstOrNull()
    return locations.firstOrNull { location -> isOverviewWebPImageLocation(ref, location) }
        ?: locations.firstOrNull(::isStaticOverviewImageLocation)
}

private fun overviewWebPImageLocation(ref: ImageRef): String? {
    val locations = buildList {
        ref.localPath?.takeIf(String::isNotBlank)?.let(::add)
        addAll(ref.progressiveUrls.filter(String::isNotBlank))
        ref.url?.takeIf(String::isNotBlank)?.let(::add)
        addAll(ref.fallbackUrls.filter(String::isNotBlank))
    }.distinct()
    return locations.firstOrNull { location -> isOverviewWebPImageLocation(ref, location) }
}

private fun isOverviewWebPImageLocation(ref: ImageRef, location: String): Boolean {
    val extension = location
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
    if (extension == "webp") return true
    val normalizedMime = ref.mime?.substringBefore(';')?.trim()?.lowercase()
    return normalizedMime == "image/webp" && (location == ref.localPath || location == ref.url)
}

private fun isStaticOverviewImageLocation(location: String): Boolean {
    val extension = location
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
    return extension in STATIC_OVERVIEW_IMAGE_EXTENSIONS
}

private val STATIC_OVERVIEW_IMAGE_EXTENSIONS = setOf(
    "avif",
    "bmp",
    "heic",
    "heif",
    "jpeg",
    "jpg",
    "png",
)
