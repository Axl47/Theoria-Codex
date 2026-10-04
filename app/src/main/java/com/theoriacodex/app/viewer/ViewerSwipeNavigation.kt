package com.theoriacodex.app.viewer



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
): ViewerHorizontalSwipeTarget? {
    if (currentPostIndex !in 0 until postCount) return null
    if (currentMediaCount <= 0) return null

    val step = if (direction == ViewerHorizontalSwipeDirection.Next) 1 else -1
    val mediaIndex = currentMediaIndex.coerceIn(0, currentMediaCount - 1) + step
    if (mediaIndex in 0 until currentMediaCount) return ViewerHorizontalSwipeTarget(currentPostIndex, mediaIndex)
    val postIndex = currentPostIndex + step
    if (postIndex !in 0 until postCount || targetPostMediaCount <= 0) return null
    return ViewerHorizontalSwipeTarget(postIndex, 0)
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
