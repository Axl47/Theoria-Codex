package com.theoriacodex.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.noMediaDurationStateForPost
import com.theoriacodex.app.media.observedMediaDurationMs
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.requestOrNull
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.Flow

@Composable
fun RelatedPostsShelf(
    state: RelatedPostsUiState,
    posts: List<Post>,
    likedPostIds: Set<PostId>,
    pixivUgoiraClient: PixivUgoiraClient?,
    acquiredDurations: Map<PostId, Long> = emptyMap(),
    durationStateForPost: (Post) -> Flow<MediaDurationState?> = noMediaDurationStateForPost,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)? = null,
    onToggleLike: (Post) -> Unit,
    onOpenPost: (posts: List<Post>, index: Int) -> Unit,
    onLongPress: (Post) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onViewportChanged: (Post, Boolean) -> Unit,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit,
) {
    val request = state.requestOrNull ?: return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RelatedShelfHeader(state, request.seed, onDismiss, onRetry)
            RelatedShelfBody(
                state = state,
                posts = posts,
                likedPostIds = likedPostIds,
                pixivUgoiraClient = pixivUgoiraClient,
                acquiredDurations = acquiredDurations,
                durationStateForPost = durationStateForPost,
                recoverPostMedia = recoverPostMedia,
                onToggleLike = onToggleLike,
                onOpenPost = onOpenPost,
                onLongPress = onLongPress,
                onViewportChanged = onViewportChanged,
                onAuthoritativeDurationKnown = onAuthoritativeDurationKnown,
            )
        }
    }
}

@Composable
private fun RelatedShelfHeader(
    state: RelatedPostsUiState,
    seed: Post,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("More like this", style = MaterialTheme.typography.titleSmall)
            Text(
                text = seed.id.source.displayName(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state is RelatedPostsUiState.Failed) {
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun RelatedShelfBody(
    state: RelatedPostsUiState,
    posts: List<Post>,
    likedPostIds: Set<PostId>,
    pixivUgoiraClient: PixivUgoiraClient?,
    acquiredDurations: Map<PostId, Long>,
    durationStateForPost: (Post) -> Flow<MediaDurationState?>,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)?,
    onToggleLike: (Post) -> Unit,
    onOpenPost: (posts: List<Post>, index: Int) -> Unit,
    onLongPress: (Post) -> Unit,
    onViewportChanged: (Post, Boolean) -> Unit,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit,
) {
    when (state) {
        RelatedPostsUiState.Idle -> Unit
        is RelatedPostsUiState.Loading -> LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        )
        is RelatedPostsUiState.Empty -> ShelfMessage("No new related posts")
        is RelatedPostsUiState.Failed -> ShelfMessage(state.message)
        is RelatedPostsUiState.Loaded -> RelatedPostsRow(
            posts = posts,
            likedPostIds = likedPostIds,
            pixivUgoiraClient = pixivUgoiraClient,
            acquiredDurations = acquiredDurations,
            durationStateForPost = durationStateForPost,
            recoverPostMedia = recoverPostMedia,
            onToggleLike = onToggleLike,
            onOpenPost = onOpenPost,
            onLongPress = onLongPress,
            onViewportChanged = onViewportChanged,
            onAuthoritativeDurationKnown = onAuthoritativeDurationKnown,
        )
    }
}

@Composable
private fun RelatedPostsRow(
    posts: List<Post>,
    likedPostIds: Set<PostId>,
    pixivUgoiraClient: PixivUgoiraClient?,
    acquiredDurations: Map<PostId, Long>,
    durationStateForPost: (Post) -> Flow<MediaDurationState?>,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)?,
    onToggleLike: (Post) -> Unit,
    onOpenPost: (posts: List<Post>, index: Int) -> Unit,
    onLongPress: (Post) -> Unit,
    onViewportChanged: (Post, Boolean) -> Unit,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit,
) {
    if (posts.isEmpty()) {
        ShelfMessage("No related posts match the current filters")
        return
    }
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = posts,
            key = { _, post -> "related-card:${post.id.source.name}:${post.id.sourcePostId}" },
        ) { index, post ->
            Box(modifier = Modifier.width(180.dp)) {
                val observedDuration = observedMediaDurationMs(post, durationStateForPost)
                SearchResultCard(
                    post = post,
                    pixivUgoiraClient = pixivUgoiraClient,
                    acquiredDurationMs = observedDuration ?: acquiredDurations[post.id],
                    showSourceBadge = true,
                    liked = post.id in likedPostIds,
                    onToggleLike = { onToggleLike(post) },
                    recoverPostMedia = recoverPostMedia,
                    onClick = { onOpenPost(posts, index) },
                    onLongPress = { onLongPress(post) },
                    onViewportChanged = { visible -> onViewportChanged(post, visible) },
                    onAuthoritativeDurationKnown = { durationMs ->
                        onAuthoritativeDurationKnown(post, durationMs)
                    },
                )
            }
        }
    }
}

@Composable
private fun ShelfMessage(message: String) {
    Text(
        text = message,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
