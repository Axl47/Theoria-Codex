package com.theoriacodex.app.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.theoriacodex.app.ui.components.LocalFeedComputationContext
import com.theoriacodex.app.ui.components.appendedAfterRemovals
import com.theoriacodex.app.ui.components.rememberRetainedDurationProjection
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.withContext

/** Turns a route's duration decisions into the filter's reveal order and presented list. */
class DurationFilterProjection {
    private var reveal = DurationReveal()
    private var presented: List<Post>? = null
    private var presentedDurations = emptyMap<PostId, Long>()
    private var presentedDurationsKey = Any()

    /** The last frame this projection answered. A feed returning from the Viewer starts from it instead of nothing. */
    @Volatile var lastFrame: DurationFilterFrame? = null
        private set

    /** [project] as a frame. The posts, states and switch of [lastFrame] answer with [lastFrame] itself. */
    @Synchronized fun frame(posts: List<Post>, states: MediaDurationStates, active: Boolean): DurationFilterFrame =
        lastFrame?.takeIf { it.posts === posts && it.active == active && it.states == states }
            ?: DurationFilterFrame(posts, states, active, project(posts, states, active)).also { lastFrame = it }

    @Synchronized fun project(posts: List<Post>, states: MediaDurationStates, active: Boolean): DurationFilterMetadata {
        if (!active) {
            reveal = DurationReveal()
            presented = null
            return DurationFilterMetadata.EMPTY
        }
        val metadata = durationFilterMetadata(posts, states, active)
        reveal = advanceDurationReveal(reveal, posts, metadata.stateByPostId, metadata.knownDurationMsByPostId)
        val presentedPosts = stablePresented(reveal.present(posts), metadata.knownDurationMsByPostId)
        return metadata.copy(
            presentedPosts = presentedPosts, revealFrontier = reveal.frontier, presentedDurationsKey = presentedDurationsKey,
        )
    }

    /**
     * Keeps the previous presented list when nothing in it changed, so a length that changes nothing shown is no new
     * list downstream, and starts a new [DurationFilterMetadata.presentedDurationsKey] when a post that stayed in place
     * changed length. An arrival that moves a post to the end counts as removed and appended.
     */
    private fun stablePresented(next: List<Post>, known: Map<PostId, Long>): List<Post> {
        val previous = presented
        val keptLengths = previous != null &&
            next.appendedAfterRemovals(previous) { post, kept -> !kept || known[post.id] == presentedDurations[post.id] } != null
        if (!keptLengths) presentedDurationsKey = Any()
        val unchanged = previous?.takeIf { it.size == next.size && it.indices.all { index -> it[index] === next[index] } }
        return (unchanged ?: next).also {
            presented = it
            presentedDurations = known
        }
    }
}

data class DurationFilterFrame(
    val posts: List<Post>,
    val states: MediaDurationStates,
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
 * Presents duration metadata for [posts]. Pass [retainKey] so the projection survives the Viewer: a
 * feed's main list keeps its reveal order (the projection then owns which posts may appear, and in
 * what order), and a related shelf keeps the lengths it resolved. Coming back from the Viewer, the first frame shows the projection's last frame while it still
 * covers [posts], and the background answer replaces it as usual.
 */
@Composable
fun rememberDurationFilterMetadata(
    posts: List<Post>,
    states: MediaDurationStates,
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
