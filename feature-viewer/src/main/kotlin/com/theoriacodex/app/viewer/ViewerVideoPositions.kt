package com.theoriacodex.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.domain.model.PostId

/** Session-local timestamps for the current post and five neighboring posts in either direction. */
internal class ViewerVideoPositions {
    private var retainedPosts = emptySet<PostId>()
    private val positions = mutableMapOf<ViewerMediaKey, Long>()

    fun retainAround(posts: List<PostId>, currentIndex: Int) {
        retainedPosts = posts.slice(
            (currentIndex - 5).coerceAtLeast(0)..(currentIndex + 5).coerceAtMost(posts.lastIndex),
        ).toSet()
        positions.keys.retainAll { it.postId in retainedPosts }
    }

    fun position(key: ViewerMediaKey): Long = positions[key] ?: 0L

    fun save(key: ViewerMediaKey, positionMs: Long) {
        if (key.postId in retainedPosts) positions[key] = positionMs.coerceAtLeast(0L)
    }
}

/**
 * Holds the current session's [ViewerVideoPositions] in the screen's ViewModel store (the Viewer's navigation entry), so
 * a Viewer level covered by a trail fork resumes its videos where they were when it returns.
 */
internal class ViewerVideoPositionStore : ViewModel() {
    private var session: ViewerSessionIdentity? = null
    private var positions = ViewerVideoPositions()

    fun forSession(identity: ViewerSessionIdentity?): ViewerVideoPositions {
        if (identity != session) {
            session = identity
            positions = ViewerVideoPositions()
        }
        return positions
    }
}

/** The positions for [session], kept across the screen leaving composition while its entry stays on the back stack. */
@Composable
internal fun rememberViewerVideoPositions(session: ViewerSessionIdentity?): ViewerVideoPositions {
    val store = viewModel<ViewerVideoPositionStore>()
    return remember(store, session) { store.forSession(session) }
}
