package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post

internal fun authoritativeDurationProbeRef(post: Post): ImageRef? {
    val full = post.full ?: return null
    return full.takeIf(::isVideoMediaRef)
        ?: full.takeIf { ref ->
            mediaKind(ref) == PostMediaKind.UNKNOWN &&
                ref.url?.substringBefore('?')?.endsWith(".mp4", ignoreCase = true) == true
        }
}
