package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.app.viewer.state.ViewerRelatedNavigation
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

data class ViewerPostNavigation(
    val originalPosts: List<Post>,
    val pages: List<ViewerPageState>,
    val sourceIndices: List<Int>,
    val currentIndex: Int,
    val stripAnchorIndex: Int,
    val branchAnchor: PostId?,
) {
    val neighbours: List<ViewerPageState>
        get() = listOfNotNull(pages.getOrNull(currentIndex + 1), pages.getOrNull(currentIndex - 1))
}

fun viewerPostNavigation(state: ViewerUiState, pendingPostId: PostId? = null): ViewerPostNavigation {
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

internal fun ViewerUiState.forOriginalNavigation(): ViewerUiState {
    val extras = related.extraPostIds
    if (extras.isEmpty()) return this
    val originalPages = pages.filterNot { it.post.id in extras }
    val selected = currentPage?.post?.id?.takeUnless { it in extras } ?: related.navigation?.anchor
    return copy(pages = originalPages, currentPageIndex = originalPages.indexOfFirst { it.post.id == selected }.coerceAtLeast(0))
}
