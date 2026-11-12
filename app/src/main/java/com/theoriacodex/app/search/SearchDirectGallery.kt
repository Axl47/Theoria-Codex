package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey

/**
 * Resolves a typed nhentai gallery ID into the effect Search should publish: the Viewer opened on that
 * gallery (reusing a result already on screen), or a message when the gallery cannot be found.
 */
internal suspend fun SearchCoordinator.directNhentaiGalleryEffect(
    galleryId: String,
    visibleResults: List<Post>,
): SearchEffect {
    val resolved = visibleResults.firstOrNull { post ->
        post.id.source == SourceKey.NHENTAI && post.id.sourcePostId == galleryId
    } ?: resolveNhentaiGalleryById(galleryId)
        ?: return SearchEffect.ShowMessage("Could not open gallery $galleryId")
    val context = ViewerLaunchContext(
        queryHash = "nhentai-search-id:$galleryId",
        startIndex = 0,
        streamSource = ViewerStreamSource.SEARCH,
        scrollOffsetHint = 0,
    )
    setViewerLaunchContext(context)
    return SearchEffect.OpenViewer(
        posts = listOf(resolved),
        context = context,
        liveSearchBinding = false,
        fromTypedId = true,
    )
}
