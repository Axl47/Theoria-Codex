package com.theoriacodex.app.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.comments.CommentCount
import com.theoriacodex.app.comments.commentCount
import com.theoriacodex.app.source.SourceLogo
import com.theoriacodex.app.ui.motion.LikedHeartColor
import com.theoriacodex.app.ui.motion.likePop
import com.theoriacodex.app.ui.motion.rememberLikeFeedback
import com.theoriacodex.app.ui.theme.TextOverMediaShadow
import com.theoriacodex.domain.model.Post

@Composable
fun BoxScope.SearchCardBadges(
    post: Post,
    viewedMediaNumber: Int?,
    watchedDescription: String?,
    liked: Boolean,
    onToggleLike: (() -> Unit)?,
    metadataLabel: String?,
    showSourceBadge: Boolean,
    showPreviewContext: Boolean,
    durationLabel: String?,
    isNew: Boolean = false,
) {
    val mediaCount = postMediaCount(post)
    val comments = commentCount(post.commentHint, state = null)
    if (onToggleLike != null || mediaCount > 1 || comments != null) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(TOP_SCRIM_HEIGHT).background(TopScrim))
    }
    if (durationLabel != null || showPreviewContext) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(BOTTOM_SCRIM_HEIGHT).background(BottomScrim))
    }
    val likeReserve = if (onToggleLike != null) LIKE_HEART_INSET + LIKE_HEART_SIZE + LIKE_MARK_GAP - OVERLAY_MARK_INSET else 0.dp
    SearchCardCountMarks(
        comments, mediaCount, viewedMediaNumber, isNew,
        Modifier.align(Alignment.TopEnd).padding(start = likeReserve).then(OverlayMarkPlacement),
    )
    if (onToggleLike != null) SearchCardLikeButton(liked, onToggleLike, Modifier.align(Alignment.TopStart))
    if (durationLabel != null) {
        OverlayMark(
            text = durationLabel,
            icon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = it) },
            modifier = Modifier.align(Alignment.BottomStart).then(OverlayMarkPlacement),
        )
    }
    if (showPreviewContext) {
        SearchCardPreviewContext(
            metadataLabel, watchedDescription, showSourceBadge, post,
            Modifier.align(Alignment.BottomEnd).then(OverlayMarkPlacement),
        )
    }
    if (mediaCount > 1 && viewedMediaNumber != null) {
        GalleryProgressLine(viewedMediaNumber.coerceIn(1, mediaCount).toFloat() / mediaCount, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SearchCardCountMarks(comments: CommentCount?, mediaCount: Int, viewedMediaNumber: Int?, isNew: Boolean, modifier: Modifier) {
    if (comments == null && mediaCount <= 1 && !isNew) return
    Row(modifier.height(OVERLAY_ROW_HEIGHT), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isNew) NewPostDot()
        if (comments != null) {
            OverlayMark(
                text = comments.label,
                icon = { Icon(Icons.Outlined.ChatBubbleOutline, null, it) },
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics { contentDescription = comments.description(unknown = "Has comments") },
            )
        }
        if (mediaCount > 1) {
            OverlayMark(
                text = imageCountBadgeLabel(mediaCount, viewedMediaNumber),
                icon = { Icon(Icons.Outlined.PhotoLibrary, if (viewedMediaNumber == null) "Image count" else "Watched media progress", it) },
                modifier = Modifier,
            )
        }
    }
}

@Composable
private fun NewPostDot() {
    Box(
        Modifier
            .size(NEW_POST_DOT_SIZE + NEW_POST_RING_WIDTH * 2)
            .background(NewPostRing, CircleShape)
            .padding(NEW_POST_RING_WIDTH)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

internal fun Modifier.newPostMarking(isNew: Boolean, shape: Shape, accent: Color): Modifier =
    if (!isNew) this else border(NEW_POST_OUTLINE_WIDTH, accent, shape).semantics { stateDescription = "New" }

@Composable
private fun SearchCardLikeButton(liked: Boolean, onToggleLike: () -> Unit, modifier: Modifier) {
    val feedback = rememberLikeFeedback()
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(48.dp)
            .likeSemantics(liked)
            .clickable(interactions, indication = null, role = Role.Checkbox) {
                feedback.tapped(liked)
                onToggleLike()
            },
        contentAlignment = Alignment.TopStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = LIKE_HEART_INSET, top = 2.dp)
                .size(LIKE_HEART_SIZE)
                .testTag("Search like visual")
                .indication(interactions, ripple(bounded = false, radius = 18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (liked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                tint = if (liked) LikedHeartColor else Color.White,
                modifier = Modifier.size(20.dp).likePop(feedback),
            )
        }
    }
}

private fun Modifier.likeSemantics(liked: Boolean): Modifier = semantics {
    contentDescription = if (liked) "Unlike post" else "Like post"
    selected = liked
    stateDescription = if (liked) "Liked" else "Not liked"
}

@Composable
private fun SearchCardPreviewContext(
    metadataLabel: String?,
    watchedDescription: String?,
    showSourceBadge: Boolean,
    post: Post,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.height(OVERLAY_ROW_HEIGHT),
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
        if (watchedDescription != null) {
            Icon(Icons.Default.Visibility, watchedDescription, Modifier.size(13.dp), tint = Color.White)
        }
        if (showSourceBadge) SourceLogo(source = post.id.source, size = 13.dp)
    }
}

@Composable
private fun OverlayMark(text: String?, icon: @Composable (Modifier) -> Unit, modifier: Modifier) {
    Row(modifier.height(OVERLAY_ROW_HEIGHT), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        icon(Modifier.size(13.dp))
        if (text != null) Text(text, style = overlayTextStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

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
    shadow = TextOverMediaShadow,
)

private val TOP_SCRIM_HEIGHT = 56.dp
private val BOTTOM_SCRIM_HEIGHT = 72.dp
private val OVERLAY_ROW_HEIGHT = 18.dp
private val LIKE_HEART_INSET = 4.dp
private val LIKE_HEART_SIZE = 30.dp
private val LIKE_MARK_GAP = 4.dp
private val OVERLAY_MARK_INSET = 10.dp
private val NEW_POST_DOT_SIZE = 8.dp
private val NEW_POST_RING_WIDTH = 1.dp
private val NEW_POST_OUTLINE_WIDTH = 2.dp
private val NewPostRing = Color.Black.copy(alpha = 0.55f)

private val OverlayMarkPlacement = Modifier.padding(horizontal = OVERLAY_MARK_INSET, vertical = 8.dp)
private val TopScrim = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))
private val BottomScrim = Brush.verticalGradient(
    0f to Color.Transparent,
    0.5f to Color.Black.copy(alpha = 0.3f),
    1f to Color.Black.copy(alpha = 0.72f),
)
