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

        val preceding = entries.indexOfLast { entry ->
            entry is RelatedFeedEntry.PostEntry && entry.canonicalIndex <= canonicalIndex
        }
        if (preceding >= 0) return preceding
        return entries.indexOfFirst { entry -> entry is RelatedFeedEntry.PostEntry }
            .takeIf { index -> index >= 0 }
            ?: 0
    }

    fun canonicalPositionForGridIndex(gridIndex: Int, offsetPx: Int): CanonicalFeedPosition {
        if (entries.isEmpty()) return CanonicalFeedPosition(0, 0)
        val boundedIndex = gridIndex.coerceIn(0, entries.lastIndex)
        val entry = entries[boundedIndex]
        if (entry is RelatedFeedEntry.PostEntry) {
            return CanonicalFeedPosition(entry.canonicalIndex, offsetPx.coerceAtLeast(0))
        }
        val preceding = entries.subList(0, boundedIndex)
            .filterIsInstance<RelatedFeedEntry.PostEntry>()
            .lastOrNull()
        val following = entries.drop(boundedIndex + 1)
            .filterIsInstance<RelatedFeedEntry.PostEntry>()
            .firstOrNull()
        return CanonicalFeedPosition((preceding ?: following)?.canonicalIndex ?: 0, 0)
    }

    fun greatestVisibleCanonicalIndex(visibleGridIndices: Iterable<Int>): Int? {
        return visibleGridIndices.mapNotNull { gridIndex ->
            (entries.getOrNull(gridIndex) as? RelatedFeedEntry.PostEntry)?.canonicalIndex
        }.maxOrNull()
    }
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
    val insertionIndex = postEntries.indexOfLast { entry ->
        entry.canonicalIndex <= request.anchorCanonicalIndex
    }.let { preceding -> if (preceding < 0) 0 else preceding + 1 }
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
