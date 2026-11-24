package com.theoriacodex.app.viewer

import com.theoriacodex.app.media.isAuthoritativeDurationMedia
import com.theoriacodex.app.media.isPixivUgoiraMedia
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class ViewerPostDurations(
    val observe: (Post) -> Flow<Long?> = { flowOf(null) },
    val onPlayerDuration: (Post, Long) -> Unit = { _, _ -> },
) {
    companion object {
        val None = ViewerPostDurations()
    }
}

internal fun carriesPostDuration(post: Post, media: ImageRef): Boolean =
    isPixivUgoiraMedia(post, media) || isAuthoritativeDurationMedia(post, media)
