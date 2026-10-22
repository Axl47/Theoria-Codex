package com.theoriacodex.app.related

import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RelatedPostsStateTest {
    @Test
    fun `hide liked retains shelf seeds through loading results empty and failure until dismissal`() {
        val seed = post("seed")
        val nextSeed = post("next")
        val otherLiked = post("other")
        val loading = RelatedPostsUiState.Idle.begin(seed, 0, 1)
        val loaded = loading.complete(loading.request, listOf(nextSeed), setOf(seed.id))
        val nextLoading = loaded.begin(nextSeed, 1, 2, loaded.availablePages)
        val states = listOf(
            loading,
            loaded,
            loading.complete(loading.request, emptyList(), emptySet()),
            loading.fail(loading.request, "offline"),
            nextLoading,
            nextLoading.complete(nextLoading.request, listOf(post("result")), emptySet()),
            nextLoading.complete(nextLoading.request, emptyList(), emptySet()),
            nextLoading.fail(nextLoading.request, "offline"),
        )
        states.forEach { state ->
            val seeds = if (state.requestOrNull?.seed == nextSeed) listOf(seed, nextSeed) else listOf(seed)
            val results = seeds + otherLiked
            val likedIds = results.mapTo(mutableSetOf(), Post::id)
            fun visible(shelf: RelatedPostsUiState, savedIds: Set<PostId> = emptySet()) = filterSearchResults(
                results = results,
                filters = SearchVisibilityFilters(hideLiked = true, hideSaved = true),
                likedPostIds = likedIds,
                savedPostIds = savedIds,
                retainedLikedPostIds = shelf.retainedLikedPostIds,
            )

            assertEquals(seeds, visible(state))
            // Idle also covers likes where recommendations were unsupported or never opened.
            assertEquals(emptyList<Post>(), visible(RelatedPostsUiState.Idle))
            assertEquals(seeds, visible(state, likedIds))
            assertEquals(emptyList<Post>(), visible(RelatedPostsUiState.Idle, likedIds))
        }
    }

    @Test
    fun `latest request rejects stale completion and filters canonical duplicates`() {
        val seedA = post("a")
        val seedB = post("b")
        val first = RelatedPostsUiState.Idle.begin(seedA, anchorCanonicalIndex = 2, generation = 1)
        val second = first.begin(seedB, anchorCanonicalIndex = 4, generation = 2)

        val stale = second.complete(
            request = first.request,
            incoming = listOf(post("old")),
            canonicalPostIds = emptySet(),
        )
        val completed = stale.complete(
            request = second.request,
            incoming = listOf(seedB, post("canonical"), post("new"), post("new")),
            canonicalPostIds = setOf(PostId(SourceKey.PIXIV, "canonical")),
        )

        assertSame(second, stale)
        assertEquals(listOf("new"), completed.loadedPosts.map { post -> post.id.sourcePostId })
    }

    @Test
    fun `empty failed and seed clear transitions retain request identity`() {
        val loading = RelatedPostsUiState.Idle.begin(post("seed"), 0, 1)
        val empty = loading.complete(loading.request, emptyList(), emptySet())
        val failed = loading.fail(loading.request, "  ")

        assertEquals(loading.request, empty.requestOrNull)
        assertEquals("Could not load related posts", (failed as RelatedPostsUiState.Failed).message)
        assertEquals(RelatedPostsUiState.Idle, failed.clearIfSeed(PostId(SourceKey.PIXIV, "seed")))
        assertSame(failed, failed.clearIfSeed(PostId(SourceKey.PIXIV, "other")))
    }

    @Test
    fun `completed pages remain selectable while the latest anchor stays fixed`() {
        val seed = post("seed")
        val firstLoading = RelatedPostsUiState.Idle.begin(seed, 2, 1)
        val first = firstLoading.complete(firstLoading.request, listOf(post("one")), emptySet())
        val secondLoading = first.begin(
            seed = post("one"),
            anchorCanonicalIndex = 3,
            generation = 2,
            retainedPages = first.availablePages,
        )
        val second = secondLoading.complete(secondLoading.request, listOf(post("two")), emptySet())

        assertEquals(1, second.currentPageIndex)
        assertEquals(listOf("two"), second.loadedPosts.map { it.id.sourcePostId })

        val previous = second.selectPage(0)
        assertEquals(listOf("one"), previous.loadedPosts.map { it.id.sourcePostId })
        assertEquals(3, previous.requestOrNull?.anchorCanonicalIndex)
        assertEquals(listOf("two"), previous.selectPage(1).loadedPosts.map { it.id.sourcePostId })
    }

    @Test
    fun `promotion inserts once and removes the promoted card from retained pages`() {
        val seed = post("seed")
        val promoted = post("promoted")
        val sibling = post("sibling")
        val loading = RelatedPostsUiState.Idle.begin(seed, 0, 1)
        val loaded = loading.complete(loading.request, listOf(promoted, sibling), emptySet())

        val withoutPromoted = loaded.withoutPost(promoted.id)
        assertEquals(listOf("sibling"), withoutPromoted.loadedPosts.map { it.id.sourcePostId })
        assertEquals(
            listOf("seed", "promoted", "tail"),
            promoteRelatedPost(listOf(seed, post("tail")), promoted, 1).map { it.id.sourcePostId },
        )
        assertEquals(
            listOf("seed", "promoted"),
            promoteRelatedPost(listOf(seed, promoted), promoted, 1).map { it.id.sourcePostId },
        )
    }

    @Test
    fun `promotion removes retained posts during loading empty and failed pages`() {
        val firstLoading = RelatedPostsUiState.Idle.begin(post("seed"), 0, 1)
        val promoted = post("promoted")
        val sibling = post("sibling")
        val first = firstLoading.complete(firstLoading.request, listOf(promoted, sibling), emptySet())
        val loading = first.begin(post("next"), 1, 2, retainedPages = first.availablePages)
        val empty = loading.complete(loading.request, emptyList(), emptySet())
        val failed = loading.fail(loading.request, "Failed")
        for (state in listOf(loading, empty, failed)) {
            val updated = state.withoutPost(promoted.id)
            assertEquals(state.requestOrNull, updated.requestOrNull)
            assertEquals(listOf(sibling), updated.availablePages.single().posts)
        }
    }

    private fun post(id: String): Post = Post(
        id = PostId(SourceKey.PIXIV, id),
        preview = ImageRef("https://example.com/$id.jpg", null, "image/jpeg"),
        full = null,
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )
}
