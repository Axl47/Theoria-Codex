package com.theoriacodex.app.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One key preparation per result snapshot; no fingerprint work on duration publication. */
internal class DurationFilterProjection(private val keyFactory: (Post) -> MediaDurationKey = ::mediaDurationKey) {
    private var previousPosts: List<Post>? = null
    private var keys = emptyMap<PostId, MediaDurationKey>()

    @Synchronized fun project(posts: List<Post>, states: Map<MediaDurationKey, MediaDurationState>, active: Boolean): DurationFilterMetadata {
        if (!active) return DurationFilterMetadata.EMPTY
        if (previousPosts !== posts) {
            keys = posts.filter(::isAnimatedPost).associate { it.id to keyFactory(it) }
            previousPosts = posts
        }
        return durationFilterMetadata(posts, states, active, keys)
    }
}

private data class DurationFilterFrame(val posts: List<Post>, val active: Boolean, val metadata: DurationFilterMetadata)

/** Only current media snapshots may be presented; a replacement waits with resolving feedback. */
@Composable
internal fun rememberDurationFilterMetadata(posts: List<Post>, states: Map<MediaDurationKey, MediaDurationState>,
    active: Boolean): DurationFilterMetadata {
    val projection = remember { DurationFilterProjection() }
    val frame by produceState<DurationFilterFrame?>(null, posts, states, active) {
        value = withContext(Dispatchers.Default) { DurationFilterFrame(posts, active, projection.project(posts, states, active)) }
    }
    return frame?.takeIf { it.posts === posts && it.active == active }?.metadata
        ?: if (active) DurationFilterMetadata.RESOLVING else DurationFilterMetadata.EMPTY
}
