package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post

/**
 * The video whose file the duration probe may read for [post]'s length, or null. Never a series episode's streams: its
 * player publishes the length, and a probe would be a second request against an expiring link, with the source's
 * headers rather than the stream's own.
 */
internal fun authoritativeDurationProbeRef(post: Post): ImageRef? {
    val full = post.full?.takeIf { it.streams.isEmpty() } ?: return null
    return full.takeIf(::isVideoMediaRef)
        ?: full.takeIf { ref ->
            mediaKind(ref) == PostMediaKind.UNKNOWN &&
                ref.url?.substringBefore('?')?.endsWith(".mp4", ignoreCase = true) == true
        }
}
