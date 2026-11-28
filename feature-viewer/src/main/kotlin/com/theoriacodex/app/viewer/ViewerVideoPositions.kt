package com.theoriacodex.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.domain.model.PostId

internal class ViewerVideoPositions(watching: ViewerSeries.Watching? = null) {
    private var retainedPosts = emptySet<PostId>()
    private val positions = mutableStateMapOf<ViewerMediaKey, Long>()
    private val episodes = watching?.startPositionsMs?.mapKeysTo(mutableStateMapOf()) { (postId) -> ViewerMediaKey(postId, mediaIndex = 0) }

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

    fun startsOver(positionMs: Long, durationMs: Long): Boolean =
        episodes != null && positionMs >= durationMs - VIEWER_EPISODE_END_MARGIN_MS
}

internal const val VIEWER_EPISODE_END_MARGIN_MS = 5_000L

internal class ViewerVideoPositionStore : ViewModel() {
    private var session: ViewerSessionIdentity? = null
    private var positions = ViewerVideoPositions()

    fun forSession(identity: ViewerSessionIdentity?, watching: ViewerSeries.Watching? = null): ViewerVideoPositions {
        if (identity != session) {
            session = identity
            positions = ViewerVideoPositions(watching)
        }
        return positions
    }
}

@Composable
internal fun rememberViewerVideoPositions(session: ViewerSessionIdentity?, series: ViewerSeries? = null): ViewerVideoPositions {
    val store = viewModel<ViewerVideoPositionStore>()
    return remember(store, session) { store.forSession(session, series as? ViewerSeries.Watching) }
}
