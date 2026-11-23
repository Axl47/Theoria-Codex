package com.theoriacodex.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.domain.model.PostId

/**
 * Session-local timestamps for the current post and five neighboring posts in either direction. A [watching] session
 * keeps every episode's position however far the user goes, seeded with [ViewerSeries.Watching.startPositionsMs], so an
 * episode outside the retained window resumes where it was left rather than at the start it opened at.
 */
internal class ViewerVideoPositions(watching: ViewerSeries.Watching? = null) {
    private var retainedPosts = emptySet<PostId>()
    private val positions = mutableMapOf<ViewerMediaKey, Long>()
    private val episodes = watching?.startPositionsMs?.mapKeysTo(mutableMapOf()) { (postId) -> ViewerMediaKey(postId, mediaIndex = 0) }

    fun retainAround(posts: List<PostId>, currentIndex: Int) {
        retainedPosts = posts.slice(
            (currentIndex - 5).coerceAtLeast(0)..(currentIndex + 5).coerceAtMost(posts.lastIndex),
        ).toSet()
        positions.keys.retainAll { it.postId in retainedPosts }
    }

    fun position(key: ViewerMediaKey): Long = positions[key] ?: episodes?.get(key) ?: 0L

    fun save(key: ViewerMediaKey, positionMs: Long) {
        val position = positionMs.coerceAtLeast(0L)
        episodes?.set(key, position)
        if (key.postId in retainedPosts) positions[key] = position
    }

    /**
     * Whether a start at [positionMs] of a video [durationMs] long begins again instead: in a watched session, within
     * [VIEWER_EPISODE_END_MARGIN_MS] of the end, the episode was watched to its end. Any other video resumes anywhere.
     */
    fun startsOver(positionMs: Long, durationMs: Long): Boolean =
        episodes != null && positionMs >= durationMs - VIEWER_EPISODE_END_MARGIN_MS
}

/** How close to an episode's end a saved position counts as watched to the end. */
internal const val VIEWER_EPISODE_END_MARGIN_MS = 5_000L

/**
 * Holds the current session's [ViewerVideoPositions] in the screen's ViewModel store (the Viewer's navigation entry), so
 * a Viewer level covered by a trail fork resumes its videos where they were when it returns.
 */
internal class ViewerVideoPositionStore : ViewModel() {
    private var session: ViewerSessionIdentity? = null
    private var positions = ViewerVideoPositions()

    /** [watching] applies only when [identity] starts a new session. */
    fun forSession(identity: ViewerSessionIdentity?, watching: ViewerSeries.Watching? = null): ViewerVideoPositions {
        if (identity != session) {
            session = identity
            positions = ViewerVideoPositions(watching)
        }
        return positions
    }
}

/**
 * The positions for [session], kept across the screen leaving composition while its entry stays on the back stack. A
 * [ViewerSeries.Watching] [series] keeps its episodes' positions; see [ViewerVideoPositions].
 */
@Composable
internal fun rememberViewerVideoPositions(session: ViewerSessionIdentity?, series: ViewerSeries? = null): ViewerVideoPositions {
    val store = viewModel<ViewerVideoPositionStore>()
    return remember(store, session) { store.forSession(session, series as? ViewerSeries.Watching) }
}
