package com.theoriacodex.app.viewer

import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.viewerMediaDeliveryPlan
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post

internal fun viewerPrefetchImageLocation(post: Post, media: ImageRef): String? {
    return viewerMediaDeliveryPlan(post, media).primary?.location
}

/**
 * A gallery page's small thumbnail worth warming before its original, so the page shows something while it
 * loads. Null when the page has none, it is not remote, or it is the page's own image.
 */
internal fun viewerPrefetchThumbnailLocation(media: ImageRef, pageLocation: String): String? =
    media.thumbnailUrl?.takeIf { thumbnail ->
        thumbnail.startsWith("http", ignoreCase = true) && thumbnail != pageLocation
    }

internal fun viewerGifLocations(post: Post, media: ImageRef): List<String> {
    return viewerMediaDeliveryPlan(post, media).candidates
        .filter { candidate -> isGifMediaRef(candidate.ref) }
        .map { candidate -> candidate.location }
}
