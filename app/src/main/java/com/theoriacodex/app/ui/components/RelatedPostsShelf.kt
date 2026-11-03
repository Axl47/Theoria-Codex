package com.theoriacodex.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.rememberLazyListState
import com.theoriacodex.app.viewer.ViewerReturnShelfId
import com.theoriacodex.app.viewer.ViewerReturnShelf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.availablePages
import com.theoriacodex.app.related.currentPageIndex
import com.theoriacodex.app.related.requestOrNull
import com.theoriacodex.app.source.displayName
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

@Composable
internal fun RelatedPostsShelf(
    state: RelatedPostsUiState,
    posts: List<Post>,
    environment: FeedCardEnvironment,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)? = null,
    onOpenPost: (posts: List<Post>, index: Int) -> Unit,
    onLongPress: (Post) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
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
            RelatedShelfHeader(
                state = state,
                seed = request.seed,
                onDismiss = onDismiss,
                onRetry = onRetry,
                onPreviousPage = onPreviousPage,
                onNextPage = onNextPage,
            )
            RelatedShelfBody(state, posts, environment, recoverPostMedia, onOpenPost, onLongPress)
        }
    }
}

@Composable
private fun RelatedShelfHeader(
    state: RelatedPostsUiState,
    seed: Post,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    val showPageNavigation = state.currentPageIndex > 0 || state.availablePages.size > 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showPageNavigation) {
                IconButton(
                    onClick = onPreviousPage,
                    enabled = state.currentPageIndex > 0,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous related page")
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("More like this", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = seed.id.source.displayName(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showPageNavigation) {
                IconButton(
                    onClick = onNextPage,
                    enabled = state.currentPageIndex in 0 until state.availablePages.lastIndex,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next related page")
                }
            }
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
    environment: FeedCardEnvironment,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)?,
    onOpenPost: (posts: List<Post>, index: Int) -> Unit,
    onLongPress: (Post) -> Unit,
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
            shelfId = state.request.seed.id,
            posts = posts,
            sizingPosts = (state.pages.flatMap { page -> page.posts } + posts).distinctBy(Post::id),
            environment = environment,
            recoverPostMedia = recoverPostMedia,
            onOpenPost = onOpenPost,
            onLongPress = onLongPress,
        )
    }
}

@Composable
private fun RelatedPostsRow(
    shelfId: PostId,
    posts: List<Post>,
    sizingPosts: List<Post>,
    environment: FeedCardEnvironment,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)?,
    onOpenPost: (posts: List<Post>, index: Int) -> Unit,
    onLongPress: (Post) -> Unit,
) {
    if (posts.isEmpty()) {
        ShelfMessage("No related posts match the current filters")
        return
    }
    val cardWidth = 180.dp
    val rowHeight = rememberPostShelfHeight(sizingPosts, cardWidth)
    val rowState = rememberLazyListState()
    val postIds = remember(posts) { posts.map { it.id } }
    ViewerReturnShelf(ViewerReturnShelfId.Related(shelfId), postIds, rowState) {
        LazyRow(
            state = rowState,
            modifier = Modifier.height(rowHeight),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                items = posts,
                key = { _, post -> "related-card:${post.id.source.name}:${post.id.sourcePostId}" },
            ) { index, post ->
                Box(modifier = Modifier.width(cardWidth)) {
                    FeedPostCard(
                        post = post,
                        environment = environment,
                        mediaAspectRatio = POST_SHELF_MEDIA_ASPECT_RATIO,
                        showSourceBadge = true,
                        recoverPostMedia = recoverPostMedia,
                        onClick = { onOpenPost(posts, index) },
                        onLongPress = { onLongPress(post) },
                    )
                }
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
