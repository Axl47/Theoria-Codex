package com.theoriacodex.app.search

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.app.media.AnimatedDurationRange
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
    fun `filterSearchResults hides only posts in watched recents`() {
        val keep = samplePost(id = "keep", source = SourceKey.PIXIV)
        val watched = samplePost(id = "watched", source = SourceKey.GELBOORU)

        val visible = filterSearchResults(
            results = listOf(keep, watched),
            filters = SearchVisibilityFilters(hideWatched = true),
            likedPostIds = emptySet(),
            savedPostIds = emptySet(),
            watchedPostIds = setOf(watched.id),
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

    @Test
    fun `filterSearchResults excludes static posts from an active duration range`() {
        val static = samplePost(id = "static", source = SourceKey.GELBOORU)
        val shortAnimated = samplePost(
            id = "short",
            source = SourceKey.PIXIV,
            fullMime = "video/mp4",
            fullUrl = "https://cdn.test/short.mp4",
            durationMs = 7_000L,
        )
        val longAnimated = samplePost(
            id = "long",
            source = SourceKey.PIXIV,
            fullMime = "video/mp4",
            fullUrl = "https://cdn.test/long.mp4",
            durationMs = 90_000L,
        )

        val visible = filterSearchResults(
            results = listOf(static, shortAnimated, longAnimated),
            filters = SearchVisibilityFilters(
                animatedDurationRange = AnimatedDurationRange(minBucket = 1, maxBucket = 2),
            ),
            likedPostIds = emptySet(),
            savedPostIds = emptySet(),
        )

        assertEquals(listOf(shortAnimated.id), visible.map { it.id })
    }

    @Test
    fun `filterSearchResults hides unknown animated durations while background resolution is enabled`() {
        val unknownAnimated = samplePost(
            id = "unknown",
            source = SourceKey.RULE34VIDEO,
            fullMime = "video/mp4",
            fullUrl = "https://cdn.test/unknown.mp4",
        )
        val knownAnimated = samplePost(
            id = "known",
            source = SourceKey.RULE34VIDEO,
            fullMime = "video/mp4",
            fullUrl = "https://cdn.test/known.mp4",
            durationMs = 15_000L,
        )

        val visible = filterSearchResults(
            results = listOf(unknownAnimated, knownAnimated),
            filters = SearchVisibilityFilters(
                animatedDurationRange = AnimatedDurationRange(minBucket = 2, maxBucket = 4),
            ),
            likedPostIds = emptySet(),
            savedPostIds = emptySet(),
            unknownAnimatedDurationPolicy = UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND,
        )

        assertEquals(listOf(knownAnimated.id), visible.map { it.id })
    }

    @Test
    fun `filterSearchResults hides unknown animated durations in strict mode`() {
        val unknownAnimated = samplePost(
            id = "unknown",
            source = SourceKey.RULE34VIDEO,
            fullMime = "video/mp4",
            fullUrl = "https://cdn.test/unknown.mp4",
        )
        val knownAnimated = samplePost(
            id = "known",
            source = SourceKey.RULE34VIDEO,
            fullMime = "video/mp4",
            fullUrl = "https://cdn.test/known.mp4",
            durationMs = 15_000L,
        )

        val visible = filterSearchResults(
            results = listOf(unknownAnimated, knownAnimated),
            filters = SearchVisibilityFilters(
                animatedDurationRange = AnimatedDurationRange(minBucket = 2, maxBucket = 4),
            ),
            likedPostIds = emptySet(),
            savedPostIds = emptySet(),
            unknownAnimatedDurationPolicy = UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS,
        )

        assertEquals(listOf(knownAnimated.id), visible.map { it.id })
    }

    @Test
    fun `filterSearchResults uses separate acquired metadata without rewriting posts`() {
        val acquiredMatch = samplePost(
            id = "acquired",
            source = SourceKey.HITOMI,
            fullMime = "video/mp4",
            fullUrl = "https://cdn.test/acquired.mp4",
        )

        val visible = filterSearchResults(
            results = listOf(acquiredMatch),
            filters = SearchVisibilityFilters(
                animatedDurationRange = AnimatedDurationRange(minBucket = 2, maxBucket = 4),
            ),
            likedPostIds = emptySet(),
            savedPostIds = emptySet(),
            knownDurationMsByPostId = mapOf(acquiredMatch.id to 15_000L),
        )

        assertEquals(listOf(acquiredMatch.id), visible.map { it.id })
        assertEquals(null, acquiredMatch.durationMs)
    }

    @Test
    fun `filterSearchResults keeps unknown animated durations when range is full`() {
        val unknownAnimated = samplePost(
            id = "unknown",
            source = SourceKey.RULE34VIDEO,
            fullMime = "video/mp4",
            fullUrl = "https://cdn.test/unknown.mp4",
        )

        val visible = filterSearchResults(
            results = listOf(unknownAnimated),
            filters = SearchVisibilityFilters(animatedDurationRange = AnimatedDurationRange.Full),
            likedPostIds = emptySet(),
            savedPostIds = emptySet(),
        )

        assertEquals(listOf(unknownAnimated.id), visible.map { it.id })
    }

    @Test
    fun `favoriteTagSections limits source mode to the active source`() {
        val sections = favoriteTagSections(
            mode = QueryMode.Source(SourceKey.GELBOORU),
            favoriteTags = mapOf(
                SourceKey.PIXIV to listOf("sky"),
                SourceKey.GELBOORU to listOf("blue_hair", "cat_ears"),
            ),
            sourceDisplayOrder = listOf(SourceKey.PIXIV, SourceKey.GELBOORU),
        )

        assertEquals(
            listOf(
                FavoriteTagSection(
                    source = SourceKey.GELBOORU,
                    tags = listOf("blue_hair", "cat_ears"),
                )
            ),
            sections,
        )
    }

    @Test
    fun `favoriteTagSections groups unified favorites by search source order`() {
        val sections = favoriteTagSections(
            mode = QueryMode.Unified,
            favoriteTags = mapOf(
                SourceKey.RULE34VIDEO to listOf("dance"),
                SourceKey.PIXIV to listOf("sky"),
                SourceKey.GELBOORU to emptyList(),
                SourceKey.NHENTAI to listOf("english"),
            ),
            sourceDisplayOrder = listOf(
                SourceKey.GELBOORU,
                SourceKey.PIXIV,
                SourceKey.NHENTAI,
                SourceKey.RULE34VIDEO,
            ),
        )

        assertEquals(
            listOf(
                FavoriteTagSection(source = SourceKey.PIXIV, tags = listOf("sky")),
                FavoriteTagSection(source = SourceKey.NHENTAI, tags = listOf("english")),
                FavoriteTagSection(source = SourceKey.RULE34VIDEO, tags = listOf("dance")),
            ),
            sections,
        )
    }

    private fun samplePost(
        id: String,
        source: SourceKey,
        fullMime: String = "image/jpeg",
        fullUrl: String = "https://cdn.test/$id.jpg",
        durationMs: Long? = null,
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
            durationMs = durationMs,
        )
    }
}
