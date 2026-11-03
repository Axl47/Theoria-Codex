package com.theoriacodex.app.related

import com.theoriacodex.domain.model.Post

sealed interface RelatedFeedEntry {
    data class PostEntry(
        val post: Post,
        val canonicalIndex: Int,
    ) : RelatedFeedEntry

    data class ShelfEntry(
        val state: RelatedPostsUiState,
        val anchorCanonicalIndex: Int,
    ) : RelatedFeedEntry
}

data class CanonicalFeedPosition(
    val index: Int,
    val offsetPx: Int,
)

data class RelatedFeedProjection(
    val entries: List<RelatedFeedEntry>,
) {
    fun gridIndexForCanonicalIndex(canonicalIndex: Int): Int {
        if (entries.isEmpty()) return 0
        entries.indexOfFirst { entry ->
            entry is RelatedFeedEntry.PostEntry && entry.canonicalIndex == canonicalIndex
        }.takeIf { index -> index >= 0 }?.let { return it }

        val preceding = entries.nearestAtOrBefore(canonicalIndex)
        if (preceding >= 0) return preceding
        return entries.indexOfFirst { entry -> entry is RelatedFeedEntry.PostEntry }
            .takeIf { index -> index >= 0 }
            ?: 0
    }

    /** Only canonical post entries can replace the last persisted scroll position. */
    fun canonicalPositionForGridIndex(gridIndex: Int, offsetPx: Int): CanonicalFeedPosition? {
        if (entries.isEmpty()) return null
        val boundedIndex = gridIndex.coerceIn(0, entries.lastIndex)
        val entry = entries[boundedIndex]
        return (entry as? RelatedFeedEntry.PostEntry)?.let { postEntry ->
            CanonicalFeedPosition(postEntry.canonicalIndex, offsetPx.coerceAtLeast(0))
        }
    }

    fun greatestVisibleCanonicalIndex(visibleGridIndices: Iterable<Int>): Int? {
        return visibleGridIndices.mapNotNull { gridIndex ->
            (entries.getOrNull(gridIndex) as? RelatedFeedEntry.PostEntry)?.canonicalIndex
        }.maxOrNull()
    }
}

/**
 * The position of the post closest to, and not after, a canonical index, or -1. Late duration
 * arrivals sit below posts with higher canonical indices, so position order is not canonical order.
 */
private fun List<RelatedFeedEntry>.nearestAtOrBefore(canonicalIndex: Int): Int {
    return indices
        .filter { index ->
            (this[index] as? RelatedFeedEntry.PostEntry)?.let { it.canonicalIndex <= canonicalIndex } == true
        }
        .maxByOrNull { index -> (this[index] as RelatedFeedEntry.PostEntry).canonicalIndex }
        ?: -1
}

fun buildRelatedFeedProjection(
    canonicalPosts: List<Post>,
    visiblePosts: List<Post>,
    relatedState: RelatedPostsUiState,
): RelatedFeedProjection {
    val canonicalIndexById = canonicalPosts
        .mapIndexed { index, post -> post.id to index }
        .toMap()
    val postEntries = visiblePosts.mapNotNull { post ->
        canonicalIndexById[post.id]?.let { index -> RelatedFeedEntry.PostEntry(post, index) }
    }
    val request = relatedState.requestOrNull ?: return RelatedFeedProjection(postEntries)
    val insertionIndex = postEntries.nearestAtOrBefore(request.anchorCanonicalIndex) + 1
    val entries = postEntries.toMutableList<RelatedFeedEntry>().apply {
        add(
            index = insertionIndex.coerceIn(0, size),
            element = RelatedFeedEntry.ShelfEntry(
                state = relatedState,
                anchorCanonicalIndex = request.anchorCanonicalIndex,
            ),
        )
    }
    return RelatedFeedProjection(entries)
}
