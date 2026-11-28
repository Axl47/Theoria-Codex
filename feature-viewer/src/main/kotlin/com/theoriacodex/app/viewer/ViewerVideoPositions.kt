package com.theoriacodex.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.domain.model.PostId

internal class ViewerVideoPositions(watching: ViewerSeries.Watching? = null) {
    private var retainedPosts = emptySet<PostId>()
    private val positions = mutableStateMapOf<ViewerMediaKey, Long>()
    private val episodes = watching?.startPositionsMs?.mapKeysTo(mutableStateMapOf()) { (postId) -> ViewerMediaKey(postId, mediaIndex = 0) }
    private val frames = LinkedHashMap<ViewerMediaKey, StoredFrame>()
    private val capturing = mutableMapOf<ViewerMediaKey, Any>()
    private var leaving: Pair<ViewerMediaKey, ViewerResumeFrameCapture>? = null

    fun retainAround(posts: List<PostId>, currentIndex: Int) {
        retainedPosts = posts.slice(
            (currentIndex - 5).coerceAtLeast(0)..(currentIndex + 5).coerceAtMost(posts.lastIndex),
        ).toSet()
        positions.keys.retainAll { it.postId in retainedPosts }
        frames.keys.retainAll { it.postId in retainedPosts }
        capturing.keys.retainAll { it.postId in retainedPosts }
    }

    fun position(key: ViewerMediaKey): Long = positions[key] ?: episodes?.get(key) ?: 0L

    fun resumeFrame(key: ViewerMediaKey): ImageBitmap? = frames[key]?.frame?.takeIf { it.positionMs == position(key) }?.image

    fun offerLeavingFrame(key: ViewerMediaKey, capture: ViewerResumeFrameCapture): AutoCloseable {
        val offer = key to capture
        leaving = offer
        return AutoCloseable { if (leaving === offer) leaving = null }
    }

    fun captureLeaving() {
        val (key, capture) = leaving ?: return
        val request = Any()
        capturing[key] = request
        capture { frame -> if (capturing.remove(key, request)) keepFrame(key, frame) }
    }

    private fun keepFrame(key: ViewerMediaKey, frame: ViewerResumeFrame) {
        if (frame.positionMs <= 0L || startsOver(frame.positionMs, frame.durationMs)) return
        frames.remove(key)
        frames[key] = StoredFrame(frame)
        while (frames.size > VIEWER_RESUME_FRAME_LIMIT) frames.remove(frames.keys.first())
    }

    fun save(key: ViewerMediaKey, positionMs: Long): Long {
        capturing.remove(key)
        val stored = frames[key]
        val captured = stored?.takeIf {
            val drift = positionMs - it.frame.positionMs
            drift == 0L || it.snaps && drift in 0..VIEWER_RESUME_FRAME_DRIFT_MS && !startsOver(positionMs, it.frame.durationMs)
        }?.frame?.positionMs
        stored?.snaps = false
        if (captured == null) frames.remove(key)
        val position = (captured ?: positionMs).coerceAtLeast(0L)
        episodes?.set(key, position)
        if (key.postId in retainedPosts) positions[key] = position
        return position
    }

    fun startsOver(positionMs: Long, durationMs: Long): Boolean =
        episodes != null && durationMs > 0L && positionMs >= durationMs - VIEWER_EPISODE_END_MARGIN_MS

    private class StoredFrame(val frame: ViewerResumeFrame) {
        var snaps = true
    }
}

internal typealias ViewerResumeFrameCapture = (deliver: (ViewerResumeFrame) -> Unit) -> Unit

internal class ViewerResumeFrame(val positionMs: Long, val durationMs: Long, val image: ImageBitmap)

internal const val VIEWER_EPISODE_END_MARGIN_MS = 5_000L
private const val VIEWER_RESUME_FRAME_LIMIT = 4
private const val VIEWER_RESUME_FRAME_DRIFT_MS = 1_000L

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
