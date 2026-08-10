package com.theoriacodex.app.media

import coil.network.HttpException
import com.theoriacodex.domain.adapter.MediaRecoverySourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post

internal fun isHttpNotFound(error: Throwable): Boolean {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
        if (current is HttpException && current.response.code == 404) return true
        current = current.cause
    }
    return false
}

internal suspend fun recoverRemoteMedia(
    registry: SourceAdapterRegistry,
    post: Post,
    failedMedia: ImageRef,
): Post? {
    val adapter = registry.adapterFor(post.id.source) ?: return null
    return if (adapter is MediaRecoverySourceAdapter) {
        adapter.recoverPostMedia(post, failedMedia)
    } else {
        adapter.resolvePost(post.id)
    }
}
