package com.theoriacodex.app.viewer

import androidx.compose.animation.core.animate
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.app.viewer.state.ViewerResolutionStatus
import com.theoriacodex.domain.model.SourceKey
import kotlin.math.abs

/** A sparse cover is not the gallery's last page, even when its page count is still unknown. */
internal fun ViewerPageState.hasPendingGalleryMedia(): Boolean {
    if (resolution.status == ViewerResolutionStatus.RESOLVED ||
        resolution.status == ViewerResolutionStatus.NOT_REQUIRED
    ) return false
    return (post.mediaCount ?: 0) > media.size ||
        ((post.id.source == SourceKey.NHENTAI || post.id.source == SourceKey.HITOMI) &&
            post.media.isEmpty() && post.full == null)
}

internal fun ViewerPageState.swipeMediaCount(): Int {
    return if (hasPendingGalleryMedia()) maxOf(media.size, post.mediaCount ?: 2) else media.size
}

internal data class ViewerHorizontalSwipeTarget(
    val postIndex: Int,
    val mediaIndex: Int,
)

internal enum class ViewerHorizontalSwipeDirection {
    Previous,
    Next,
}

internal fun viewerHorizontalSwipeTarget(
    currentPostIndex: Int,
    currentMediaIndex: Int,
    currentMediaCount: Int,
    postCount: Int,
    targetPostMediaCount: Int,
    direction: ViewerHorizontalSwipeDirection,
    galleryMediaPending: Boolean = false,
    targetMediaIndex: Int = 0,
): ViewerHorizontalSwipeTarget? {
    if (currentPostIndex !in 0 until postCount) return null
    if (currentMediaCount <= 0) return null

    val step = if (direction == ViewerHorizontalSwipeDirection.Next) 1 else -1
    val mediaIndex = currentMediaIndex.coerceIn(0, currentMediaCount - 1) + step
    if (mediaIndex in 0 until currentMediaCount) return ViewerHorizontalSwipeTarget(currentPostIndex, mediaIndex)
    if (direction == ViewerHorizontalSwipeDirection.Next && galleryMediaPending) return null
    val postIndex = currentPostIndex + step
    if (postIndex !in 0 until postCount || targetPostMediaCount <= 0) return null
    return ViewerHorizontalSwipeTarget(postIndex, targetMediaIndex.coerceAtLeast(0))
}

internal fun viewerMediaPagerReverseLayout(
    mediaCount: Int,
    invertMultiImageScrollDirection: Boolean,
): Boolean {
    return invertMultiImageScrollDirection && mediaCount > 1
}

internal fun PagerState.viewerPageExtent(): Float = (layoutInfo.pageSize + layoutInfo.pageSpacing).toFloat()

/** A release this fast counts as a fling whatever distance it covered. */
internal val VIEWER_FLING_VELOCITY = 400.dp

private const val SWIPE_COMMIT_FRACTION = 0.18f
private const val EDGE_STRETCH_FRACTION = 0.18f

/**
 * Decides a released swipe. A fling goes where it is moving, and one moving back against the drag
 * cancels it; a slow release commits only past [SWIPE_COMMIT_FRACTION] of a page.
 */
internal fun viewerSwipeDecision(
    displacement: Float,
    velocity: Float,
    pageExtent: Float,
    flingVelocity: Float,
): ViewerHorizontalSwipeDirection? {
    val deciding = when {
        abs(velocity) >= flingVelocity -> if (displacement * velocity < 0f) return null else velocity
        abs(displacement) >= pageExtent * SWIPE_COMMIT_FRACTION -> displacement
        else -> return null
    }
    return if (deciding < 0f) ViewerHorizontalSwipeDirection.Next else ViewerHorizontalSwipeDirection.Previous
}

/** Diminishing resistance: the page follows the finger less the further it is pulled past the end. */
internal fun viewerEdgeStretch(displacement: Float, pageExtent: Float): Float {
    val limit = pageExtent * EDGE_STRETCH_FRACTION
    if (limit <= 0f) return 0f
    return displacement * limit / (limit + abs(displacement))
}

/** Lands on [page] from the finger's velocity; distant jumps use the pager's own long-distance scroll. */
internal suspend fun PagerState.settleViewerPage(page: Int, velocity: Float) {
    val extent = viewerPageExtent()
    if (extent <= 0f || abs(page - currentPage) > 1) {
        animateScrollToPage(page, animationSpec = TheoriaMotion.settle)
        return
    }
    val distance = (page - currentPage - currentPageOffsetFraction) * extent
    scroll {
        var previous = 0f
        animate(0f, distance, initialVelocity = velocity, animationSpec = TheoriaMotion.settle) { value, _ ->
            scrollBy(value - previous)
            previous = value
        }
    }
    scrollToPage(page)
}

/** Raw deltas expose finger progress without committing the selected post before settling. */
internal fun PagerState.dragViewerPage(origin: Int, target: Int?, displacement: Float) {
    val width = viewerPageExtent()
    if (width <= 0f) return
    val desired = if (target == null) 0f else {
        val distance = -displacement / width
        if (target > origin) distance.coerceIn(0f, 0.95f) else distance.coerceIn(-0.95f, 0f)
    }
    val current = currentPage - origin + currentPageOffsetFraction
    dispatchRawDelta((desired - current) * width)
}
