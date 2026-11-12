package com.theoriacodex.app.comments

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.FoldHeader
import com.theoriacodex.app.ui.components.SourceImage
import com.theoriacodex.domain.model.CommentOrder
import com.theoriacodex.domain.model.CommentTarget
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.SourceKey

private val AVATAR_SIZE = 24.dp
private val STAMP_SIZE = 48.dp
private val REPLY_INDENT = 24.dp
private val HIGHLIGHT_OUTSET = 4.dp
private const val HIGHLIGHT_FADE_MS = 600
private const val HIGHLIGHT_ALPHA = 0.18f
private const val HIGHLIGHT_SHRINK_MS = 250
private const val HOLD_FILL_DELAY_MS = 80L

/**
 * How the comments section is shown: folded or not, its sort order, which the header's button cycles, and
 * what a comment's actions lead to. [highlightKey] names the item ([commentItemKey], [replyItemKey]) tinted
 * to show where a link led; clearing it fades the tint out.
 */
internal class CommentsView(
    val collapsed: Boolean,
    val onToggleCollapsed: () -> Unit,
    val order: CommentOrder,
    val onOrderSelected: (CommentOrder) -> Unit,
    val highlightKey: String? = null,
    private val creatorBrowsingSources: Set<SourceKey> = emptySet(),
    val onOpenAuthor: (AuthorDestination) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
) {
    fun authorDestination(author: CreatorProfile?) = commentAuthorDestination(author, creatorBrowsingSources)
}

/**
 * A post's comments as items of the info sheet's list: a header with the count that folds the rest away,
 * each comment in the chosen order, with its replies opened beneath it, and under each list a progress row, a
 * retry, or "More". Adds nothing when the post's state says there is no section ([PostCommentsState.hasSection]).
 */
internal fun LazyListScope.commentsSection(
    post: Post,
    state: PostCommentsState,
    count: CommentCount?,
    actions: PostCommentsActions,
    nowEpochMs: Long,
    view: CommentsView,
) {
    if (!state.hasSection) return
    item(key = "comments-header", contentType = "comments-header") { CommentsHeader(count, view) }
    if (view.collapsed) return
    state.top.comments.inOrder(view.order).forEach { comment -> commentWithReplies(post, state, comment, actions, nowEpochMs, view) }
    listFooter("comments-footer", state.top, "More comments", 0.dp) { actions.loadMore(post) }
}

@Composable
private fun CommentsHeader(count: CommentCount?, view: CommentsView) {
    FoldHeader("Comments", "comments", count?.label, view.collapsed, view.onToggleCollapsed, Modifier.padding(top = 8.dp)) {
        if (!view.collapsed) {
            TextButton(onClick = { view.onOrderSelected(view.order.toggled()) }) {
                Text(if (view.order == CommentOrder.OLDEST) "Oldest" else "Newest")
            }
        }
    }
}

/** One top-level comment, then its replies and their footer while they are opened. */
private fun LazyListScope.commentWithReplies(
    post: Post,
    state: PostCommentsState,
    comment: PostComment,
    actions: PostCommentsActions,
    nowEpochMs: Long,
    view: CommentsView,
) {
    val replies = state.replies[comment.id]
    val expanded = comment.id in state.expandedReplies
    item(key = commentItemKey(comment.id), contentType = "comment") {
        CommentRow(
            post, comment, parentId = null, nowEpochMs, actions, view,
            repliesLabel = if (comment.hasReplies) repliesLabel(replies?.total, expanded) else null,
            onToggleReplies = { actions.toggleReplies(post, comment) },
        )
    }
    if (!expanded || replies == null) return
    replies.comments.forEach { reply ->
        item(key = replyItemKey(comment.id, reply.id), contentType = "reply") {
            CommentRow(post, reply, parentId = comment.id, nowEpochMs, actions, view, Modifier.padding(start = REPLY_INDENT))
        }
    }
    listFooter("replies-footer:${comment.id}", replies, "More", REPLY_INDENT) { actions.loadMoreReplies(post, comment) }
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

/**
 * One comment: avatar beside the author, age and text, then its stamp and the button that opens its replies.
 * Holding it opens its actions; tapping the avatar or name opens the author's profile. [parentId] is set for a reply.
 */
@Composable
private fun CommentRow(
    post: Post,
    comment: PostComment,
    parentId: String?,
    nowEpochMs: Long,
    actions: PostCommentsActions,
    view: CommentsView,
    modifier: Modifier = Modifier,
    repliesLabel: String? = null,
    onToggleReplies: () -> Unit = {},
) {
    val source = post.id.source
    var showingActions by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val showActions = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        showingActions = true
    }
    val author = view.authorDestination(comment.author)
    // Holding the avatar or name opens the actions too, since a tap there already belongs to the profile.
    val authorTap = author?.let {
        Modifier.combinedClickable(
            role = Role.Button,
            onClickLabel = "Open ${comment.authorName}'s profile",
            onClick = { view.onOpenAuthor(it) },
            onLongClick = showActions,
        )
    } ?: Modifier
    val highlighted = view.highlightKey == if (parentId == null) commentItemKey(comment.id) else replyItemKey(parentId, comment.id)
    Row(
        modifier.fillMaxWidth().padding(top = 12.dp)
            .commentRowHighlight(highlighted, showingActions, "Actions for comment by ${comment.authorName}", showActions),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        comment.authorAvatarUrl?.let { url ->
            SourceImage(source, url, AVATAR_SIZE, CircleShape, Modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).then(authorTap))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = comment.authorName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).then(authorTap),
                )
                commentAgeLabel(nowEpochMs, comment.createdAtEpochMs)?.let { age ->
                    Text(age, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (comment.body.isNotBlank()) Text(comment.body, style = MaterialTheme.typography.bodyMedium)
            comment.stampUrl?.let { url ->
                SourceImage(source, url, STAMP_SIZE, RoundedCornerShape(4.dp), Modifier.padding(top = 4.dp), ContentScale.Fit)
            }
            if (repliesLabel != null) TextButton(onClick = onToggleReplies) { Text(repliesLabel) }
        }
    }
    if (showingActions) {
        CommentActionSheet(
            source, comment, actions.linkTo(post, CommentTarget(comment.id, parentId)), author, view,
            onDismiss = { showingActions = false },
        )
    }
}

/**
 * The row's tint and hold gesture. [linked] tints the row and fades out when it ends, to show where a link led.
 * Holding fills the row from the finger, covering it as the hold completes and runs [onLongPress]; the fill stays
 * while [selected] (its actions are open) and shrinks back on an early release or when they close.
 * Screen readers get the hold as a long-click named [label]. Taps are left alone.
 */
@Composable
private fun Modifier.commentRowHighlight(linked: Boolean, selected: Boolean, label: String, onLongPress: () -> Unit): Modifier {
    val tint = MaterialTheme.colorScheme.primary
    val latest by rememberUpdatedState(onLongPress)
    val link by animateFloatAsState(if (linked) 1f else 0f, tween(HIGHLIGHT_FADE_MS), label = "Comment link highlight")
    val fill = remember { Animatable(0f) }
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    val scope = rememberCoroutineScope()
    val holdMs = LocalViewConfiguration.current.longPressTimeoutMillis.toInt()
    LaunchedEffect(selected) {
        if (selected) fill.snapTo(1f) else if (fill.value > 0f) fill.animateTo(0f, tween(HIGHLIGHT_SHRINK_MS))
    }
    return semantics { onLongClick(label) { latest(); true } }
        .pointerInput(Unit) {
            var held = false
            detectTapGestures(
                onPress = { at ->
                    origin = at
                    held = false
                    // A short delay keeps a scroll's touch-down from flashing the fill.
                    val grow = scope.launch {
                        delay(HOLD_FILL_DELAY_MS)
                        fill.animateTo(1f, tween((holdMs - HOLD_FILL_DELAY_MS.toInt()).coerceAtLeast(1), easing = LinearEasing))
                    }
                    tryAwaitRelease()
                    grow.cancel()
                    if (!held) scope.launch { fill.animateTo(0f, tween(HIGHLIGHT_SHRINK_MS)) }
                },
                onLongPress = {
                    held = true
                    scope.launch { fill.snapTo(1f) }
                    latest()
                },
            )
        }
        .drawBehind { drawRowHighlight(tint, link, fill.value, origin) }
}

/** Draws the link tint at [link] strength and the hold fill as a circle from [origin] grown to [fill] of the row. */
private fun DrawScope.drawRowHighlight(tint: Color, link: Float, fill: Float, origin: Offset) {
    val outset = HIGHLIGHT_OUTSET.toPx()
    val bounds = RoundRect(-outset, -outset, size.width + outset, size.height + outset, CornerRadius(2 * outset))
    val shape = Path().apply { addRoundRect(bounds) }
    if (link > 0f) drawPath(shape, tint.copy(alpha = HIGHLIGHT_ALPHA * link))
    if (fill <= 0f) return
    val from = if (origin.isSpecified) origin else center
    val reach = Offset(maxOf(from.x - bounds.left, bounds.right - from.x), maxOf(from.y - bounds.top, bounds.bottom - from.y)).getDistance()
    clipPath(shape) { drawCircle(tint.copy(alpha = HIGHLIGHT_ALPHA), radius = reach * fill, center = from) }
}

