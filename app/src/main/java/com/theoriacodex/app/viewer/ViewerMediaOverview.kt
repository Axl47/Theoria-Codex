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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theoriacodex.app.media.isAnimatedImageMediaRef
import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.isPixivUgoiraMedia
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.media.PostMediaKind
import com.theoriacodex.app.media.mediaKind
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey

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
        state = grid, modifier = modifier.background(MaterialTheme.colorScheme.background).padding(horizontal = 10.dp),
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
        item.kind.badgeLabel?.let { label ->
            ViewerOverviewBadge(item.kind, label, Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun ViewerOverviewPoster(source: SourceKey, item: ViewerMediaOverviewItem) {
    val context = LocalContext.current
    val model = remember(context, item.posterLocation, item.kind, source) {
        item.posterLocation?.let { buildViewerImageRequest(context, it, source,
            staticAnimatedWebPFrame = shouldDecodeStaticOverviewFrame(item.kind)) }
    }
    if (model != null) AsyncImage(model, "Media ${item.mediaIndex + 1}: ${item.kind.accessibilityLabel}",
        Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
    else Text("Preview unavailable", color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
}

@Composable
private fun ViewerOverviewBadge(kind: ViewerMediaOverviewKind, label: String, modifier: Modifier) {
    Row(modifier.background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 7.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (kind == ViewerMediaOverviewKind.VIDEO) Icon(Icons.Default.PlayArrow, null, tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

internal fun shouldDecodeStaticOverviewFrame(kind: ViewerMediaOverviewKind): Boolean {
    return kind != ViewerMediaOverviewKind.ANIMATED_IMAGE
}

internal enum class ViewerMediaOverviewKind(
    val badgeLabel: String?,
    val accessibilityLabel: String,
) {
    STILL_IMAGE(badgeLabel = null, accessibilityLabel = "still image"),
    ANIMATED_IMAGE(badgeLabel = "Animated", accessibilityLabel = "animated image"),
    GIF(badgeLabel = "GIF", accessibilityLabel = "GIF"),
    UGOIRA(badgeLabel = "Ugoira", accessibilityLabel = "Ugoira animation"),
    VIDEO(badgeLabel = "Video", accessibilityLabel = "video"),
    UNKNOWN(badgeLabel = "Media", accessibilityLabel = "media"),
}

internal data class ViewerMediaOverviewItem(
    val mediaIndex: Int,
    val media: ImageRef,
    val kind: ViewerMediaOverviewKind,
    val posterLocation: String?,
)

internal fun viewerMediaOverviewItems(post: Post): List<ViewerMediaOverviewItem> {
    return postMediaItems(post).mapIndexed { index, media ->
        val kind = viewerMediaOverviewKind(post, media)
        ViewerMediaOverviewItem(
            mediaIndex = index,
            media = media,
            kind = kind,
            posterLocation = viewerMediaOverviewPosterLocation(post, media, kind),
        )
    }
}

internal fun viewerMediaOverviewAvailable(items: List<ViewerMediaOverviewItem>): Boolean {
    return items.size > 1
}

internal fun viewerMediaOverviewKind(post: Post, media: ImageRef): ViewerMediaOverviewKind {
    if (isPixivUgoiraMedia(post, media)) return ViewerMediaOverviewKind.UGOIRA
    if (isVideoMediaRef(media)) return ViewerMediaOverviewKind.VIDEO
    if (isGifMediaRef(media)) return ViewerMediaOverviewKind.GIF
    if (isAnimatedImageMediaRef(media)) return ViewerMediaOverviewKind.ANIMATED_IMAGE
    return when (mediaKind(media)) {
        PostMediaKind.IMAGE -> ViewerMediaOverviewKind.STILL_IMAGE
        PostMediaKind.VIDEO -> ViewerMediaOverviewKind.VIDEO
        PostMediaKind.UGOIRA -> ViewerMediaOverviewKind.UGOIRA
        PostMediaKind.UNKNOWN -> ViewerMediaOverviewKind.UNKNOWN
    }
}

internal fun viewerMediaOverviewPosterLocation(
    post: Post,
    media: ImageRef,
    kind: ViewerMediaOverviewKind = viewerMediaOverviewKind(post, media),
): String? {
    val mediaPoster = when (kind) {
        ViewerMediaOverviewKind.STILL_IMAGE -> {
            if (post.id.source == SourceKey.HITOMI) {
                overviewWebPImageLocation(media) ?: viewerPrefetchImageLocation(post, media)
            } else {
                viewerPrefetchImageLocation(post, media)
            }
        }
        ViewerMediaOverviewKind.ANIMATED_IMAGE -> staticOverviewImageLocation(media)
        ViewerMediaOverviewKind.GIF,
        ViewerMediaOverviewKind.UGOIRA,
        ViewerMediaOverviewKind.VIDEO,
        ViewerMediaOverviewKind.UNKNOWN,
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
