package com.theoriacodex.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.domain.model.PostId

/**
 * Session-local timestamps for the current post and five neighboring posts in either direction. A
 * [ViewerSeries.Watching] session seeds each episode's start with [startPositionsMs]; an episode's seed follows what is
 * saved for it, so it never falls back to the start it opened at once the episode leaves the retained window.
 */
internal class ViewerVideoPositions(startPositionsMs: Map<PostId, Long> = emptyMap()) {
    private var retainedPosts = emptySet<PostId>()
    private val positions = mutableMapOf<ViewerMediaKey, Long>()
    private val starts = startPositionsMs.mapKeysTo(mutableMapOf()) { (postId) -> ViewerMediaKey(postId, mediaIndex = 0) }

    fun retainAround(posts: List<PostId>, currentIndex: Int) {
        retainedPosts = posts.slice(
            (currentIndex - 5).coerceAtLeast(0)..(currentIndex + 5).coerceAtMost(posts.lastIndex),
        ).toSet()
        positions.keys.retainAll { it.postId in retainedPosts }
    }

    fun position(key: ViewerMediaKey): Long = positions[key] ?: starts[key] ?: 0L

    fun save(key: ViewerMediaKey, positionMs: Long) {
        val position = positionMs.coerceAtLeast(0L)
        if (key in starts) starts[key] = position
        if (key.postId in retainedPosts) positions[key] = position
    }
}

/**
 * Holds the current session's [ViewerVideoPositions] in the screen's ViewModel store (the Viewer's navigation entry), so
 * a Viewer level covered by a trail fork resumes its videos where they were when it returns.
 */
internal class ViewerVideoPositionStore : ViewModel() {
    private var session: ViewerSessionIdentity? = null
    private var positions = ViewerVideoPositions()

    /** [startPositionsMs] seeds the positions only when [identity] starts a new session. */
    fun forSession(identity: ViewerSessionIdentity?, startPositionsMs: Map<PostId, Long> = emptyMap()): ViewerVideoPositions {
        if (identity != session) {
            session = identity
            positions = ViewerVideoPositions(startPositionsMs)
        }
        return positions
    }
}

/**
 * The positions for [session], kept across the screen leaving composition while its entry stays on the back stack. A
 * [ViewerSeries.Watching] [series] seeds its episodes' starts.
 */
@Composable
internal fun rememberViewerVideoPositions(session: ViewerSessionIdentity?, series: ViewerSeries? = null): ViewerVideoPositions {
    val store = viewModel<ViewerVideoPositionStore>()
    val starts = (series as? ViewerSeries.Watching)?.startPositionsMs.orEmpty()
    return remember(store, session) { store.forSession(session, starts) }
}
