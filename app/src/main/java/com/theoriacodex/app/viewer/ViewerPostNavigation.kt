package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.app.viewer.state.ViewerRelatedNavigation
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

/** Display order can visit a recommendation branch; backing indices and the original feed stay stable. */
internal data class ViewerPostNavigation(
    val originalPosts: List<Post>,
    val pages: List<ViewerPageState>,
    val sourceIndices: List<Int>,
    val currentIndex: Int,
    val stripAnchorIndex: Int,
    val branchAnchor: PostId?,
)

internal fun viewerPostNavigation(state: ViewerUiState, pendingPostId: PostId? = null): ViewerPostNavigation {
    val indicesById = state.pages.mapIndexed { index, page -> page.post.id to index }.toMap()
    val originals = state.pages.filterNot { it.post.id in state.related.extraPostIds }
    val originalPosts = originals.map { it.post }
    val currentId = state.currentPage?.post?.id
    val branch = state.related.navigation?.takeIf { currentId in it.postIds || pendingPostId in it.postIds }
    val ids = branch?.let { branchDisplayOrder(originalPosts, it, pendingPostId) } ?: originalPosts.map { it.id }
    val indices = ids.mapNotNull(indicesById::get)
    val pages = indices.map(state.pages::get)
    val anchor = if (currentId in state.related.extraPostIds) state.related.navigation?.anchor else currentId
    return ViewerPostNavigation(
        originalPosts = originalPosts, pages = pages, sourceIndices = indices,
        currentIndex = pages.indexOfFirst { it.post.id == currentId }.coerceAtLeast(0),
        stripAnchorIndex = originalPosts.indexOfFirst { it.id == anchor }.coerceAtLeast(0),
        branchAnchor = branch?.anchor,
    )
}

private fun branchDisplayOrder(originals: List<Post>, branch: ViewerRelatedNavigation, pendingPostId: PostId?): List<PostId> {
    val anchorIndex = originals.indexOfFirst { it.id == branch.anchor }
    return buildList {
        add(branch.anchor)
        addAll(branch.postIds)
        originals.getOrNull(anchorIndex + 1)?.let { add(it.id) }
        pendingPostId?.let(::add)
    }.distinct()
}

/** Adding branch media never changes which posts belong to the original bottom strip. */
internal fun ViewerSession.withRelatedPosts(seedId: PostId, incoming: List<Post>): ViewerSession {
    val originals = posts.filterNot { it.id in relatedPostIds }
    val originalIds = originals.mapTo(mutableSetOf(), Post::id)
    val anchor = seedId.takeIf { it in originalIds } ?: relatedNavigation?.anchor ?: return this
    val prefix = relatedNavigation?.postIds.orEmpty().let { ids ->
        if (seedId in originalIds) emptyList() else ids.take(ids.indexOf(seedId) + 1)
    }
    val branchIds = (prefix + incoming.map { it.id }).filterNot { it == anchor }.distinct()
    val extras = incoming.mapTo(mutableSetOf(), Post::id) - originalIds
    return copy(
        posts = mergeViewerPosts(posts, incoming), relatedPostIds = relatedPostIds + extras,
        relatedNavigation = ViewerRelatedNavigation(anchor, branchIds),
    )
}

/** Recommendation branches are transient: cold recovery resumes the original feed at its liked anchor. */
internal fun ViewerUiState.forOriginalNavigation(session: ViewerSession?): ViewerUiState {
    val extras = session?.relatedPostIds.orEmpty()
    if (extras.isEmpty()) return this
    val originalPages = pages.filterNot { it.post.id in extras }
    val selected = currentPage?.post?.id?.takeUnless { it in extras } ?: session?.relatedNavigation?.anchor
    return copy(pages = originalPages, currentPageIndex = originalPages.indexOfFirst { it.post.id == selected }.coerceAtLeast(0))
}
