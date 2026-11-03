package com.theoriacodex.app.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.theoriacodex.app.ui.components.rememberRetainedDurationProjection
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One key preparation per result snapshot; no fingerprint work on duration publication. */
internal class DurationFilterProjection(private val keyFactory: (Post) -> MediaDurationKey = ::mediaDurationKey) {
    private var previousPosts: List<Post>? = null
    private var keys = emptyMap<PostId, MediaDurationKey>()
    private var reveal = DurationReveal()

    @Synchronized fun project(posts: List<Post>, states: Map<MediaDurationKey, MediaDurationState>, active: Boolean): DurationFilterMetadata {
        if (!active) {
            reveal = DurationReveal()
            return DurationFilterMetadata.EMPTY
        }
        if (previousPosts !== posts) {
            keys = posts.filter(::isAnimatedPost).associate { it.id to keyFactory(it) }
            previousPosts = posts
        }
        val metadata = durationFilterMetadata(posts, states, active, keys)
        reveal = advanceDurationReveal(reveal, posts, metadata.stateByPostId, metadata.knownDurationMsByPostId)
        return metadata.copy(presentedPosts = reveal.present(posts), revealFrontier = reveal.frontier)
    }
}

private data class DurationFilterFrame(val posts: List<Post>, val active: Boolean, val metadata: DurationFilterMetadata)

/** The last frame stays valid while [posts] only appended to it; a replacement waits with resolving feedback. */
private fun DurationFilterFrame.covers(posts: List<Post>): Boolean {
    if (posts === this.posts) return true
    val shown = this.posts
    return shown.size <= posts.size && shown.isNotEmpty() &&
        shown.first().id == posts.first().id && shown.last().id == posts[shown.lastIndex].id
}

/**
 * Presents duration metadata for [posts]. Pass [retainKey] for a feed's main list, so its reveal
 * order survives the Viewer; the projection then owns which posts may appear, and in what order.
 */
@Composable
internal fun rememberDurationFilterMetadata(
    posts: List<Post>,
    states: Map<MediaDurationKey, MediaDurationState>,
    active: Boolean,
    retainKey: String? = null,
): DurationFilterMetadata {
    val projection = if (retainKey != null) {
        rememberRetainedDurationProjection(retainKey)
    } else {
        remember { DurationFilterProjection() }
    }
    val frame by produceState<DurationFilterFrame?>(null, posts, states, active) {
        value = withContext(Dispatchers.Default) { DurationFilterFrame(posts, active, projection.project(posts, states, active)) }
    }
    if (!active) return DurationFilterMetadata.EMPTY
    val current = frame?.takeIf { it.active && it.covers(posts) } ?: return DurationFilterMetadata.RESOLVING
    return if (current.posts === posts) current.metadata else current.metadata.stillPreparing()
}
