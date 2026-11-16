package com.theoriacodex.app.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.theoriacodex.app.ui.components.LocalFeedComputationContext
import com.theoriacodex.app.ui.components.rememberRetainedDurationProjection
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.withContext

/** One key preparation per result snapshot; no fingerprint work on duration publication. */
internal class DurationFilterProjection(private val keyFactory: (Post) -> MediaDurationKey = ::mediaDurationKey) {
    private var previousPosts: List<Post>? = null
    private var keys = emptyMap<PostId, MediaDurationKey>()
    private var reveal = DurationReveal()

    /** The last frame this projection answered. A feed returning from the Viewer starts from it instead of nothing. */
    @Volatile var lastFrame: DurationFilterFrame? = null
        private set

    /** [project] as a frame. The posts, states and switch of [lastFrame] answer with [lastFrame] itself. */
    @Synchronized fun frame(posts: List<Post>, states: Map<MediaDurationKey, MediaDurationState>, active: Boolean): DurationFilterFrame =
        lastFrame?.takeIf { it.posts === posts && it.active == active && it.states == states }
            ?: DurationFilterFrame(posts, states, active, project(posts, states, active)).also { lastFrame = it }

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

internal data class DurationFilterFrame(
    val posts: List<Post>,
    val states: Map<MediaDurationKey, MediaDurationState>,
    val active: Boolean,
    val metadata: DurationFilterMetadata,
)

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
 * Coming back from the Viewer, the first frame shows the projection's last frame while it still
 * covers [posts], and the background answer replaces it as usual.
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
    val background = LocalFeedComputationContext.current
    val frame by produceState(projection.lastFrame, posts, states, active) {
        value = withContext(background) { projection.frame(posts, states, active) }
    }
    if (!active) return DurationFilterMetadata.EMPTY
    val current = frame?.takeIf { it.active && it.covers(posts) } ?: return DurationFilterMetadata.RESOLVING
    return if (current.posts === posts) current.metadata else current.metadata.stillPreparing()
}
