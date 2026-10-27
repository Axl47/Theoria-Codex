package com.theoriacodex.app.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.availablePages
import com.theoriacodex.app.related.requestOrNull
import com.theoriacodex.app.related.retainedLikedPostIds
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

/** Collapsing is local; only explicit Dismiss clears the source shelf and its retained liked seeds. */
@Composable
internal fun ViewerRelatedStrip(
    state: RelatedPostsUiState,
    posts: List<Post>,
    currentPostId: PostId,
    onSelect: (Post) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onExpandedChange: (Boolean) -> Unit = {},
    onInteraction: () -> Unit = {},
) {
    val latestExpandedChange by rememberUpdatedState(onExpandedChange)
    DisposableEffect(Unit) { onDispose { latestExpandedChange(false) } }
    val request = state.requestOrNull ?: return
    var expanded by remember(request.seed.id, request.generation) { mutableStateOf(false) }
    var hidden by remember(request.seed.id, request.generation) { mutableStateOf(false) }
    val scopeIds = remember(state) {
        state.retainedLikedPostIds + state.availablePages.flatMap { page -> page.posts.map { it.id } }
    }
    LaunchedEffect(currentPostId, scopeIds) {
        if (currentPostId !in scopeIds) hidden = true
    }
    LaunchedEffect(expanded, hidden) { latestExpandedChange(expanded && !hidden) }
    if (hidden) return
    Box(modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = expanded,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.padding(end = 36.dp),
        ) {
            ViewerRelatedStripContent(state, posts, currentPostId, enabled, onSelect, onDismiss, onRetry)
        }

        Surface(
            onClick = { expanded = !expanded; onInteraction() },
            modifier = Modifier.align(Alignment.TopEnd).size(width = 32.dp, height = 48.dp),
            shape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(if (expanded) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    if (expanded) "Collapse related posts" else "Show related posts")
            }
        }
    }
}

@Composable
private fun ViewerRelatedStripContent(
    state: RelatedPostsUiState, posts: List<Post>, currentPostId: PostId, enabled: Boolean,
    onSelect: (Post) -> Unit, onDismiss: () -> Unit, onRetry: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("More like this", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss related posts") }
            }
            ViewerRelatedStatus(state, posts.isEmpty(), onRetry)
            ViewerRelatedThumbnails(posts, currentPostId, enabled, onSelect)
        }
    }
}

@Composable
private fun ViewerRelatedStatus(state: RelatedPostsUiState, empty: Boolean, onRetry: () -> Unit) {
    when (state) {
        is RelatedPostsUiState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        is RelatedPostsUiState.Failed -> TextButton(onClick = onRetry) { Text("Could not load related posts. Retry") }
        is RelatedPostsUiState.Empty -> Text("No new related posts", Modifier.padding(horizontal = 12.dp))
        else -> if (empty) Text("No matching related posts", Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
private fun ViewerRelatedThumbnails(posts: List<Post>, currentPostId: PostId, enabled: Boolean, onSelect: (Post) -> Unit) {
    if (posts.isEmpty()) return
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(posts, key = { "${it.id.source}:${it.id.sourcePostId}" }) { post ->
            ViewerPostThumbnail(post, post.id == currentPostId, Modifier.size(52.dp)) { if (enabled) onSelect(post) }
        }
    }
}
