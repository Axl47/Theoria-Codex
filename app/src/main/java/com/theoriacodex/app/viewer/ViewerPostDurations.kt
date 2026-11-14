package com.theoriacodex.app.viewer

import com.theoriacodex.app.media.isAuthoritativeDurationMedia
import com.theoriacodex.app.media.isPixivUgoiraMedia
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The route's post durations as the Viewer uses them: [observe] gives a post's known length so a timeline can
 * show it before its player loads, and [onPlayerDuration] reports the length a player measured.
 */
internal class ViewerPostDurations(
    val observe: (Post) -> Flow<Long?> = { flowOf(null) },
    val onPlayerDuration: (Post, Long) -> Unit = { _, _ -> },
) {
    companion object {
        val None = ViewerPostDurations()
    }
}

/** Whether [media] is the one a post's duration describes, so a player's length is the post's and vice versa. */
internal fun carriesPostDuration(post: Post, media: ImageRef): Boolean =
    isPixivUgoiraMedia(post, media) || isAuthoritativeDurationMedia(post, media)
