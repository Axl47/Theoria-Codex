package com.theoriacodex.app.viewer

import androidx.compose.animation.core.animate
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The screen's latest navigation facts; rebuilt each composition and read when a gesture needs them. */
internal class ViewerSwipeContext(
    val postPager: PagerState,
    val selection: ViewerHorizontalSwipeTarget,
    val mediaPager: (postIndex: Int) -> PagerState?,
    val postIdAt: (postIndex: Int) -> PostId?,
    val postIndexOf: (PostId) -> Int,
    /** The destination one step from [origin], or null at an end. */
    val target: (origin: ViewerHorizontalSwipeTarget, ViewerHorizontalSwipeDirection) -> ViewerHorizontalSwipeTarget?,
    /** -1 when the post at this index lays its pages out in reverse. */
    val swipeSign: (postIndex: Int) -> Float,
    /** Tells a destination post which media page to show before it slides in. */
    val prepareDestination: (ViewerHorizontalSwipeTarget) -> Unit,
    val commit: (PostId, mediaIndex: Int) -> Unit,
    val onInteraction: () -> Unit,
    val onLimit: () -> Unit,
    val flingVelocity: Float,
)

/**
 * Drives the Viewer's post and media pagers from one finger. Selection commits after the settle,
 * or immediately when a new press catches the settle, so a fast flick continues from wherever the
 * pages are instead of being dropped. All displacement is signed like the pager: negative moves
 * towards Next, whichever way the layout runs.
 */
internal class ViewerSwipeController(
    private val scope: CoroutineScope,
    contextState: State<ViewerSwipeContext>,
) {
    private val context by contextState
    private class Settle(val target: ViewerHorizontalSwipeTarget, val postId: PostId, val commits: Boolean)
    private class Origin(val postId: PostId, val mediaIndex: Int)

    var dragging by mutableStateOf(false)
        private set
    var settling by mutableStateOf(false)
        private set
    /** The page worth preparing because it is about to appear. */
    var loadTarget by mutableStateOf<ViewerHorizontalSwipeTarget?>(null)
        private set
    /** Visual stretch applied past the last page, in pixels. */
    var edgeOffset by mutableFloatStateOf(0f)
        private set

    private var settle: Settle? = null
    private var settleJob: Job? = null
    private var recoilJob: Job? = null
    private var baseline = 0f
    private var limitReported = false
    /** A committed selection that screen state has not delivered yet. */
    private var pendingOrigin: Origin? = null

    private fun origin(): ViewerHorizontalSwipeTarget {
        val current = context
        val pending = pendingOrigin ?: return current.selection
        val index = current.postIndexOf(pending.postId)
        val origin = ViewerHorizontalSwipeTarget(index, pending.mediaIndex)
        if (index < 0 || origin == current.selection) pendingOrigin = null
        return if (index < 0) current.selection else origin
    }

    /** A finger landed: catch a running settle so the gesture continues from the current position. */
    fun press() {
        android.util.Log.d("SwipeRepro", "press settle=${settle != null} dragging=$dragging sel=${context.selection} pending=${pendingOrigin?.postId}")
        val caught = settle
        limitReported = false
        recoilJob?.cancel()
        if (caught == null) {
            pendingOrigin = null
            return
        }
        settleJob?.cancel()
        settle = null
        settling = false
        baseline = displacementFrom(caught.target)
        if (caught.commits) {
            context.commit(caught.postId, caught.target.mediaIndex)
            pendingOrigin = Origin(caught.postId, caught.target.mediaIndex)
        }
        dragging = true
    }

    private fun displacementFrom(target: ViewerHorizontalSwipeTarget): Float {
        val post = context.postPager
        val postDelta = post.currentPage + post.currentPageOffsetFraction - target.postIndex
        if (abs(postDelta) > 0.0001f) return -postDelta * post.viewerPageExtent()
        val media = context.mediaPager(target.postIndex) ?: return 0f
        return -(media.currentPage + media.currentPageOffsetFraction - target.mediaIndex) * media.viewerPageExtent()
    }

    fun drag(rawDisplacement: Float) {
        val current = context
        val origin = origin()
        val sign = current.swipeSign(origin.postIndex)
        val displacement = baseline + rawDisplacement * sign
        dragging = true
        val direction = if (displacement < 0f) ViewerHorizontalSwipeDirection.Next else ViewerHorizontalSwipeDirection.Previous
        val target = current.target(origin, direction)
        loadTarget = target
        current.mediaPager(origin.postIndex)
            ?.dragViewerPage(origin.mediaIndex, target?.takeIf { it.postIndex == origin.postIndex }?.mediaIndex, displacement)
        current.postPager.dragViewerPage(origin.postIndex, target?.takeIf { it.postIndex != origin.postIndex }?.postIndex, displacement)
        edgeOffset = if (target == null) viewerEdgeStretch(displacement * sign, current.postPager.viewerPageExtent()) else 0f
        if (target == null && !limitReported) {
            limitReported = true
            current.onLimit()
        }
    }

    fun release(rawDisplacement: Float, rawVelocity: Float) {
        val current = context
        val origin = origin()
        val sign = current.swipeSign(origin.postIndex)
        val displacement = baseline + rawDisplacement * sign
        val velocity = rawVelocity * sign
        val pager = current.postPager
        val direction = viewerSwipeDecision(
            displacement, rawDisplacement * sign, velocity, pager.viewerPageExtent(), current.flingVelocity, pager.viewerCommitFraction(),
        )
        val target = direction?.let { current.target(origin, it) }
        android.util.Log.d("SwipeRepro", "release raw=$rawDisplacement disp=$displacement vel=$velocity extent=${pager.viewerPageExtent()} frac=${pager.viewerCommitFraction()} fling=${current.flingVelocity} origin=$origin dir=$direction target=$target baseline=$baseline")
        settleTo(target ?: origin, -velocity, commits = target != null)
    }

    /** The gesture ended without a swipe decision, so a caught or dragged page returns to rest. */
    fun cancel() {
        android.util.Log.d("SwipeRepro", "cancel dragging=$dragging")
        if (dragging) settleTo(origin(), 0f, commits = false)
    }

    /** Slides to a post chosen elsewhere, such as a thumbnail. */
    fun navigateTo(target: ViewerHorizontalSwipeTarget) = settleTo(target, 0f, commits = true)

    /** Forgets everything when the Viewer session is replaced. */
    fun reset() {
        settleJob?.cancel()
        recoilJob?.cancel()
        settle = null
        pendingOrigin = null
        settling = false
        dragging = false
        loadTarget = null
        edgeOffset = 0f
        baseline = 0f
    }

    private fun settleTo(target: ViewerHorizontalSwipeTarget, pagerVelocity: Float, commits: Boolean) {
        val current = context
        val postId = current.postIdAt(target.postIndex) ?: return reset()
        val origin = origin()
        val crossesPosts = target.postIndex != origin.postIndex
        settleJob?.cancel()
        dragging = false
        settling = true
        baseline = 0f
        settle = Settle(target, postId, commits)
        loadTarget = target.takeIf { commits }
        if (commits) current.onInteraction()
        if (crossesPosts) current.prepareDestination(target)
        recoilEdge()
        settleJob = scope.launch {
            val postVelocity = if (crossesPosts) pagerVelocity else 0f
            val mediaVelocity = if (crossesPosts) 0f else pagerVelocity
            val mediaPage = if (crossesPosts) origin.mediaIndex else target.mediaIndex
            coroutineScope {
                launch { current.postPager.settleViewerPage(target.postIndex, postVelocity) }
                launch { current.mediaPager(origin.postIndex)?.settleViewerPage(mediaPage, mediaVelocity) }
            }
            finishSettle()
        }
    }

    private fun finishSettle() {
        val finished = settle ?: return
        settle = null
        if (finished.commits) {
            context.commit(finished.postId, finished.target.mediaIndex)
            pendingOrigin = Origin(finished.postId, finished.target.mediaIndex)
        }
        settling = false
        loadTarget = null
    }

    private fun recoilEdge() {
        val from = edgeOffset
        if (from == 0f) return
        recoilJob = scope.launch {
            animate(from, 0f, animationSpec = TheoriaMotion.recoil) { value, _ -> edgeOffset = value }
        }
    }
}
