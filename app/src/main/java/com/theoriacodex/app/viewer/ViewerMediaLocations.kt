package com.theoriacodex.app.viewer

import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.viewerMediaDeliveryPlan
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post

internal fun viewerPrefetchImageLocation(post: Post, media: ImageRef): String? {
    return viewerMediaDeliveryPlan(post, media).primary?.location
}

internal fun viewerGifLocations(post: Post, media: ImageRef): List<String> {
    return viewerMediaDeliveryPlan(post, media).candidates
        .filter { candidate -> isGifMediaRef(candidate.ref) }
        .map { candidate -> candidate.location }
}
