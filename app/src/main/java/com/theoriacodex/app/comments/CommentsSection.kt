package com.theoriacodex.app.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.SourceKey

private val AVATAR_SIZE = 24.dp
private val STAMP_SIZE = 48.dp
private val REPLY_INDENT = 24.dp

/**
 * A post's comments as items of the info sheet's list: a header with the count, each comment with its
 * replies opened beneath it, and under each list a progress row, a retry, or "More". Adds nothing when the
 * post's state says there is no section ([PostCommentsState.hasSection]).
 */
internal fun LazyListScope.commentsSection(
    post: Post,
    state: PostCommentsState,
    count: CommentCount?,
    actions: PostCommentsActions,
    nowEpochMs: Long,
) {
    if (!state.hasSection) return
    val source = post.id.source
    item(key = "comments-header", contentType = "comments-header") {
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Comments", style = MaterialTheme.typography.titleSmall)
            count?.label?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
    state.top.comments.forEach { comment ->
        val replies = state.replies[comment.id]
        val expanded = comment.id in state.expandedReplies
        item(key = "comment:${comment.id}", contentType = "comment") {
            CommentRow(
                source, comment, nowEpochMs,
                repliesLabel = if (comment.hasReplies) repliesLabel(replies?.total, expanded) else null,
                onToggleReplies = { actions.toggleReplies(post, comment) },
            )
        }
        if (expanded && replies != null) {
            replies.comments.forEach { reply ->
                item(key = "reply:${comment.id}:${reply.id}", contentType = "reply") {
                    CommentRow(source, reply, nowEpochMs, Modifier.padding(start = REPLY_INDENT))
                }
            }
            listFooter("replies-footer:${comment.id}", replies, "More", REPLY_INDENT) { actions.loadMoreReplies(post, comment) }
        }
    }
    listFooter("comments-footer", state.top, "More comments", 0.dp) { actions.loadMore(post) }
}

/** What follows a list: progress while a page loads, a retry after a failed one, or the button for the next page. */
private fun LazyListScope.listFooter(key: String, list: CommentList, moreLabel: String, indent: Dp, onLoadNext: () -> Unit) {
    if (list.status == CommentLoadStatus.Loaded && !list.hasMore) return
    item(key = key, contentType = "comments-footer") {
        Row(Modifier.padding(start = indent), verticalAlignment = Alignment.CenterVertically) {
            when (list.status) {
                CommentLoadStatus.Loading -> CircularProgressIndicator(
                    modifier = Modifier.padding(vertical = 12.dp).size(16.dp).semantics { contentDescription = "Loading comments" },
                    strokeWidth = 2.dp,
                )
                CommentLoadStatus.Failed -> {
                    Text("Comments didn't load", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onLoadNext) { Text("Retry") }
                }
                CommentLoadStatus.Loaded -> TextButton(onClick = onLoadNext) { Text(moreLabel) }
            }
        }
    }
}

/** One comment: avatar beside the author, age and text, then its stamp and the button that opens its replies. */
@Composable
private fun CommentRow(
    source: SourceKey,
    comment: PostComment,
    nowEpochMs: Long,
    modifier: Modifier = Modifier,
    repliesLabel: String? = null,
    onToggleReplies: () -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        comment.authorAvatarUrl?.let { url ->
            CommentImage(source, url, AVATAR_SIZE, CircleShape, Modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = comment.authorName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                commentAgeLabel(nowEpochMs, comment.createdAtEpochMs)?.let { age ->
                    Text(age, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (comment.body.isNotBlank()) Text(comment.body, style = MaterialTheme.typography.bodyMedium)
            comment.stampUrl?.let { url ->
                CommentImage(source, url, STAMP_SIZE, RoundedCornerShape(4.dp), Modifier.padding(top = 4.dp), ContentScale.Fit)
            }
            if (repliesLabel != null) TextButton(onClick = onToggleReplies) { Text(repliesLabel) }
        }
    }
}

/** An avatar or stamp, requested with the source's media headers (Pixiv's image host refuses requests without its Referer). */
@Composable
private fun CommentImage(
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
