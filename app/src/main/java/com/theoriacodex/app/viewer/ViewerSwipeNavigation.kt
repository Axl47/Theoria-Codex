package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.app.viewer.state.ViewerResolutionStatus
import com.theoriacodex.domain.model.SourceKey

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

internal fun viewerSwipeDirectionForSetting(
    rawDirection: ViewerHorizontalSwipeDirection,
    currentMediaCount: Int,
    invertMultiImageScrollDirection: Boolean,
): ViewerHorizontalSwipeDirection {
    if (!invertMultiImageScrollDirection || currentMediaCount <= 1) return rawDirection
    return when (rawDirection) {
        ViewerHorizontalSwipeDirection.Previous -> ViewerHorizontalSwipeDirection.Next
        ViewerHorizontalSwipeDirection.Next -> ViewerHorizontalSwipeDirection.Previous
    }
}

internal fun viewerMediaPagerReverseLayout(
    mediaCount: Int,
    invertMultiImageScrollDirection: Boolean,
): Boolean {
    return invertMultiImageScrollDirection && mediaCount > 1
}
