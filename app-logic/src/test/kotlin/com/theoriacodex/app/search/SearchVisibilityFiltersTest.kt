package com.theoriacodex.app.search

import com.theoriacodex.data.repository.FavoriteTagEntry
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaFilter
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.app.media.AnimatedDurationRange
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchVisibilityFiltersTest {
    @Test
    fun `mark inputs drop marks whose hide toggle is off so new likes keep the same key (ADR 0084)`() {
        val liked = setOf(PostId(SourceKey.PIXIV, "a"))
        val likedMore = liked + PostId(SourceKey.PIXIV, "b")
        val showAll = SearchVisibilityFilters()
        assertEquals(
            showAll.markInputs(liked, liked, liked, liked),
            showAll.markInputs(likedMore, likedMore, likedMore, likedMore),
        )
        val hideLiked = SearchVisibilityFilters(hideLiked = true)
        assertEquals(PostMarkInputs(likedMore, emptySet(), emptySet(), liked), hideLiked.markInputs(likedMore, likedMore, likedMore, liked))
    }

    @Test
    fun `watched liked seed stays visible until recommendations are dismissed (ADR 0077)`() {
        val seed = samplePost(id = "seed", source = SourceKey.PIXIV)
        val other = samplePost(id = "other", source = SourceKey.PIXIV)
        fun visible(retained: Set<PostId>) = filterSearchResults(
            results = listOf(seed, other),
            filters = SearchVisibilityFilters(hideLiked = true, hideSaved = true, hideWatched = true),
            likedPostIds = setOf(seed.id), savedPostIds = setOf(seed.id),
            watchedPostIds = setOf(seed.id, other.id), retainedLikedPostIds = retained,
        )
        assertEquals(listOf(seed), visible(setOf(seed.id)))
        assertEquals(emptyList<Post>(), visible(emptySet()))
    }

    @Test
    fun `pending like retains its automatic save without retaining unrelated saved posts (ADR 0077)`() {
        val pending = samplePost(id = "pending", source = SourceKey.PIXIV)
        val saved = samplePost(id = "saved", source = SourceKey.PIXIV)
        val filters = SearchVisibilityFilters(hideLiked = true, hideSaved = true)

        fun visible(retainedIds: Set<PostId>) = filterSearchResults(
            results = listOf(pending, saved),
            filters = filters,
            likedPostIds = emptySet(),
            savedPostIds = setOf(pending.id, saved.id),
            retainedLikedPostIds = retainedIds,
        )

        assertEquals(listOf(pending), visible(setOf(pending.id)))
        assertEquals(emptyList<Post>(), visible(emptySet()))
    }

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
    fun `a still post tagged as animated passes the animated filter and an untagged one does not (ADR 0084)`() {
        val rawTagged = samplePost(id = "raw", source = SourceKey.GELBOORU).copy(rawTags = listOf("Animated_GIF"))
        val canonicalTagged = samplePost(id = "canonical", source = SourceKey.DANBOORU).copy(canonicalTags = listOf("video"))
        val untagged = samplePost(id = "untagged", source = SourceKey.GELBOORU).copy(rawTags = listOf("animal"))

        val visible = filterSearchResults(
            results = listOf(rawTagged, canonicalTagged, untagged),
            filters = SearchVisibilityFilters(animatedOnly = true),
            likedPostIds = emptySet(),
            savedPostIds = emptySet(),
        )

        assertEquals(listOf(rawTagged.id, canonicalTagged.id), visible.map { it.id })
    }

    @Test
    fun `animated-only hides stills on the phone in every mode but an nhentai search (ADR 0008, 0080)`() {
        val still = samplePost(id = "still", source = SourceKey.GELBOORU)
        val video = samplePost(id = "video", source = SourceKey.GELBOORU, fullMime = "video/mp4", fullUrl = "https://cdn.test/video.mp4")
        val hidingModes = listOf(QueryMode.Unified) +
            SourceKey.builtIns.filterNot { it == SourceKey.NHENTAI }.map { QueryMode.Source(it) }
        assertEquals(11, hidingModes.size)
        hidingModes.forEach { mode ->
            val query = Query.newest(SourceKey.GELBOORU).copy(mode = mode, media = MediaFilter.ANIMATED)
            assertEquals(mode.toString(), listOf(video.id), listOf(still, video).keptByMedia(query).map { it.id })
        }
        val nhentai = Query.newest(SourceKey.NHENTAI).copy(media = MediaFilter.ANIMATED)
        assertEquals(listOf(still.id, video.id), listOf(still, video).keptByMedia(nhentai).map { it.id })
        val anyMedia = Query.newest(SourceKey.GELBOORU)
        assertEquals(listOf(still.id, video.id), listOf(still, video).keptByMedia(anyMedia).map { it.id })
    }

    @Test
    fun `filterSearchResults excludes static posts from an active duration range (ADR 0084)`() {
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
            knownDurationMsByPostId = mapOf(static.id to 7_000L),
        )

        assertEquals(listOf(shortAnimated.id), visible.map { it.id })
    }

    @Test
    fun `filterSearchResults hides animated posts whose length is unknown (ADR 0084)`() {
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
        )

        assertEquals(listOf(knownAnimated.id), visible.map { it.id })
    }

    @Test
    fun `filterSearchResults uses separate acquired metadata without rewriting posts (ADR 0084)`() {
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
            favoriteTags = listOf(
                FavoriteTagEntry(SourceKey.PIXIV, "sky"),
                FavoriteTagEntry(SourceKey.GELBOORU, "blue_hair"),
                FavoriteTagEntry(SourceKey.GELBOORU, listOf("cat_ears", "fox_ears")),
            ),
            sourceDisplayOrder = listOf(SourceKey.PIXIV, SourceKey.GELBOORU),
        )

        assertEquals(
            listOf(
                FavoriteTagSection(
                    source = SourceKey.GELBOORU,
                    entries = listOf(
                        FavoriteTagEntry(SourceKey.GELBOORU, "blue_hair"),
                        FavoriteTagEntry(SourceKey.GELBOORU, listOf("cat_ears", "fox_ears")),
                    ),
                )
            ),
            sections,
        )
    }

    @Test
    fun `favoriteTagSections groups unified favorites by search source order`() {
        val sections = favoriteTagSections(
            mode = QueryMode.Unified,
            favoriteTags = listOf(
                FavoriteTagEntry(SourceKey.RULE34VIDEO, "dance"),
                FavoriteTagEntry(SourceKey.PIXIV, "sky"),
                FavoriteTagEntry(SourceKey.NHENTAI, "english"),
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
                FavoriteTagSection(SourceKey.PIXIV, listOf(FavoriteTagEntry(SourceKey.PIXIV, "sky"))),
                FavoriteTagSection(SourceKey.NHENTAI, listOf(FavoriteTagEntry(SourceKey.NHENTAI, "english"))),
                FavoriteTagSection(SourceKey.RULE34VIDEO, listOf(FavoriteTagEntry(SourceKey.RULE34VIDEO, "dance"))),
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
