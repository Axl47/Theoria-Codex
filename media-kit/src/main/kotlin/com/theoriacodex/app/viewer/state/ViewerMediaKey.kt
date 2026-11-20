package com.theoriacodex.app.viewer.state

import com.theoriacodex.domain.model.PostId

data class ViewerMediaKey(
    val postId: PostId,
    val mediaIndex: Int,
) {
    init {
        require(mediaIndex >= 0) { "Viewer media index must not be negative" }
    }
}

enum class ViewerPrefetchOutcome {
    WARMED,
    SKIPPED,
    FAILED,
}

/** [decoded]: the image now sits decoded in memory, not only on disk. */
data class ViewerPrefetchResult(
    val outcome: ViewerPrefetchOutcome,
    val bytesCached: Long = 0L,
    val decoded: Boolean = false,
) {
    init {
        require(bytesCached >= 0L) { "Viewer prefetch cached bytes must not be negative" }
        require(outcome == ViewerPrefetchOutcome.WARMED || bytesCached == 0L && !decoded) {
            "Only warmed Viewer media may report cached bytes or a decoded image"
        }
    }
}
