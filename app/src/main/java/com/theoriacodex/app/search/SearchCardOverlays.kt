package com.theoriacodex.app.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.SourceLogo
import com.theoriacodex.domain.model.Post

/**
 * Everything drawn over a card's media. Soft gradients along the top and bottom edges carry plain
 * white marks, so no overlay needs its own pill: like and media count on top; duration, then
 * time, watched mark and source along the bottom; and gallery progress as a line on the bottom edge.
 */
@Composable
internal fun BoxScope.SearchCardBadges(
    post: Post,
    viewedMediaNumber: Int?,
    watchedDescription: String?,
    liked: Boolean,
    onToggleLike: (() -> Unit)?,
    metadataLabel: String?,
    showSourceBadge: Boolean,
    showPreviewContext: Boolean,
    durationLabel: String?,
) {
    val mediaCount = postMediaCount(post)
    if (onToggleLike != null || mediaCount > 1) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(TOP_SCRIM_HEIGHT).background(TopScrim))
    }
    if (durationLabel != null || showPreviewContext) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(BOTTOM_SCRIM_HEIGHT).background(BottomScrim))
    }
    if (mediaCount > 1) {
        OverlayMark(
            text = imageCountBadgeLabel(mediaCount, viewedMediaNumber),
            icon = { Icon(Icons.Outlined.PhotoLibrary, if (viewedMediaNumber == null) "Image count" else "Watched media progress", it) },
            modifier = Modifier.align(Alignment.TopEnd).padding(OVERLAY_EDGE_PADDING),
        )
    }
    if (onToggleLike != null) SearchCardLikeButton(liked, onToggleLike, Modifier.align(Alignment.TopStart))
    if (durationLabel != null) {
        OverlayMark(
            text = durationLabel,
            icon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = it) },
            modifier = Modifier.align(Alignment.BottomStart).padding(OVERLAY_EDGE_PADDING),
        )
    }
    if (showPreviewContext) {
        SearchCardPreviewContext(
            metadataLabel, watchedDescription, showSourceBadge, post,
            Modifier.align(Alignment.BottomEnd).padding(OVERLAY_EDGE_PADDING),
        )
    }
    if (mediaCount > 1 && viewedMediaNumber != null) {
        GalleryProgressLine(viewedMediaNumber.coerceIn(1, mediaCount).toFloat() / mediaCount, Modifier.align(Alignment.BottomCenter))
    }
}

/** A 48dp checkbox target whose visible heart sits in the card's corner. */
@Composable
private fun SearchCardLikeButton(liked: Boolean, onToggleLike: () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .size(48.dp)
            .likeSemantics(liked)
            .clickable(role = Role.Checkbox, onClick = onToggleLike),
        contentAlignment = Alignment.TopStart,
    ) {
        Box(
            modifier = Modifier.padding(2.dp).size(30.dp).testTag("Search like visual"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (liked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                tint = if (liked) MaterialTheme.colorScheme.error else Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun Modifier.likeSemantics(liked: Boolean): Modifier = semantics {
    contentDescription = if (liked) "Unlike post" else "Like post"
    selected = liked
    stateDescription = if (liked) "Liked" else "Not liked"
}

/** When it was seen or watched, then where it came from. */
@Composable
private fun SearchCardPreviewContext(
    metadataLabel: String?,
    watchedDescription: String?,
    showSourceBadge: Boolean,
    post: Post,
    modifier: Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (metadataLabel != null) {
            Text(
                metadataLabel,
                style = overlayTextStyle().copy(color = Color.White.copy(alpha = 0.78f), fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Marks a previously watched post; when it was watched is left to screen readers and Recents.
        if (watchedDescription != null) {
            Icon(Icons.Default.Visibility, watchedDescription, Modifier.size(13.dp), tint = Color.White)
        }
        if (showSourceBadge) SourceLogo(source = post.id.source, size = 13.dp)
    }
}

/** A white icon and label that rely on the edge scrim, not a container, for contrast. */
@Composable
private fun OverlayMark(text: String, icon: @Composable (Modifier) -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        icon(Modifier.size(13.dp))
        Text(text, style = overlayTextStyle(), maxLines = 1)
    }
}

/** How far through a gallery the viewer got, along the media's bottom edge. */
@Composable
private fun GalleryProgressLine(fraction: Float, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
@ReadOnlyComposable
private fun overlayTextStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(
    color = Color.White,
    fontWeight = FontWeight.SemiBold,
    shadow = OverlayTextShadow,
)

private val TOP_SCRIM_HEIGHT = 56.dp
private val BOTTOM_SCRIM_HEIGHT = 72.dp
private val OVERLAY_EDGE_PADDING = 10.dp
private val OverlayTextShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 3f)
private val TopScrim = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))
private val BottomScrim = Brush.verticalGradient(
    0f to Color.Transparent,
    0.5f to Color.Black.copy(alpha = 0.3f),
    1f to Color.Black.copy(alpha = 0.72f),
)
