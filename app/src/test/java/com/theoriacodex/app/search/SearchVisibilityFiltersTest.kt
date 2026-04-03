package com.theoriacodex.app.search

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchVisibilityFiltersTest {
    @Test
    fun `filterSearchResults keeps all results when filters are disabled`() {
        val animated = samplePost(id = "animated", source = SourceKey.PIXIV, fullMime = "video/mp4", fullUrl = "https://cdn.test/animated.mp4")
        val liked = samplePost(id = "liked", source = SourceKey.GELBOORU)
        val saved = samplePost(id = "saved", source = SourceKey.AIBOORU)

        val visible = filterSearchResults(
            results = listOf(animated, liked, saved),
            filters = SearchVisibilityFilters(),
            likedPostIds = setOf(liked.id),
            savedPostIds = setOf(saved.id),
        )

        assertEquals(listOf(animated.id, liked.id, saved.id), visible.map { it.id })
    }

    @Test
    fun `filterSearchResults hides liked and saved posts independently`() {
        val keep = samplePost(id = "keep", source = SourceKey.PIXIV)
        val liked = samplePost(id = "liked", source = SourceKey.GELBOORU)
        val saved = samplePost(id = "saved", source = SourceKey.AIBOORU)

        val visible = filterSearchResults(
            results = listOf(keep, liked, saved),
            filters = SearchVisibilityFilters(hideLiked = true, hideSaved = true),
            likedPostIds = setOf(liked.id),
            savedPostIds = setOf(saved.id),
        )

        assertEquals(listOf(keep.id), visible.map { it.id })
    }

    @Test
    fun `filterSearchResults combines animated liked and saved visibility rules`() {
        val animatedSaved = samplePost(
            id = "animated-saved",
            source = SourceKey.PIXIV,
            fullMime = "video/mp4",
            fullUrl = "https://cdn.test/animated-saved.mp4",
        )
        val animatedVisible = samplePost(
            id = "animated-visible",
            source = SourceKey.RULE34VIDEO,
            fullMime = "video/mp4",
            fullUrl = "https://cdn.test/animated-visible.mp4",
        )
        val staticVisible = samplePost(id = "static-visible", source = SourceKey.NHENTAI)
        val likedStatic = samplePost(id = "liked-static", source = SourceKey.GELBOORU)

        val visible = filterSearchResults(
            results = listOf(animatedSaved, animatedVisible, staticVisible, likedStatic),
            filters = SearchVisibilityFilters(animatedOnly = true, hideLiked = true, hideSaved = true),
            likedPostIds = setOf(likedStatic.id),
            savedPostIds = setOf(animatedSaved.id),
        )

        assertEquals(listOf(animatedVisible.id), visible.map { it.id })
    }

    private fun samplePost(
        id: String,
        source: SourceKey,
        fullMime: String = "image/jpeg",
        fullUrl: String = "https://cdn.test/$id.jpg",
    ): Post {
        val preview = ImageRef(
            url = "https://cdn.test/$id-preview.jpg",
            localPath = null,
            mime = "image/jpeg",
        )
        val full = ImageRef(
            url = fullUrl,
            localPath = null,
            mime = fullMime,
        )
        return Post(
            id = PostId(source = source, sourcePostId = id),
            preview = preview,
            full = full,
            media = listOf(full),
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            title = null,
        )
    }
}
