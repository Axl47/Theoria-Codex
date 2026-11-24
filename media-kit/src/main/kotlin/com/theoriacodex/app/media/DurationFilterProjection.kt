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

class DurationFilterProjection {
    private var reveal = DurationReveal()
    private var presented: List<Post>? = null
    private var presentedDurations = emptyMap<PostId, Long>()
    private var presentedDurationsKey = Any()

    @Volatile var lastFrame: DurationFilterFrame? = null
        private set

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

private fun DurationFilterFrame.covers(posts: List<Post>): Boolean {
    if (posts === this.posts) return true
    val shown = this.posts
    return shown.size <= posts.size && shown.isNotEmpty() &&
        shown.first().id == posts.first().id && shown.last().id == posts[shown.lastIndex].id
}

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
