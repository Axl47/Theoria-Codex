package com.theoriacodex.app.codex

import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.app.statistics.statisticsPostKey
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CodexPostStatistics
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.CodexStatistics
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.LifetimeStatistics
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexPostSortingTest {
    private val stored = listOf(
        testPost(sourcePostId = "a", authorName = "zed", mediaCount = 3),
        testPost(sourcePostId = "b", authorName = null, mediaCount = 1),
        testPost(sourcePostId = "c", authorName = "Amy", mediaCount = 3),
        testPost(sourcePostId = "d", authorName = "amy", mediaCount = 8,
            creatorProfile = CreatorProfile(SourceKey.PIXIV, displayName = "Bea")),
    )
    private val usage = mapOf(
        key("a") to CodexUsage(watchingMs = 100L, opens = 1L),
        key("c") to CodexUsage(watchingMs = 100L, opens = 5L),
        key("d") to CodexUsage(watchingMs = 10L, opens = 9L),
    )

    private fun key(id: String) = statisticsPostKey(PostId(SourceKey.PIXIV, id))

    private fun ids(sort: CodexSortMode) = sortCodexPosts(stored, sort, usage).map { it.id.sourcePostId }

    @Test fun `usage sorts break ties on the other figure`() {
        assertEquals(listOf("c", "a", "d", "b"), ids(CodexSortMode.MOST_WATCHED))
        assertEquals(listOf("d", "c", "a", "b"), ids(CodexSortMode.MOST_OPENED))
    }

    @Test fun `page sorts keep the stored order for equal counts`() {
        assertEquals(listOf("d", "a", "c", "b"), ids(CodexSortMode.MOST_PAGES))
        assertEquals(listOf("b", "a", "c", "d"), ids(CodexSortMode.FEWEST_PAGES))
    }

    @Test fun `creator sort prefers the profile name, ignores case and puts unknown creators last`() {
        assertEquals(listOf("c", "d", "a", "b"), ids(CodexSortMode.CREATOR_ASCENDING))
    }

    @Test fun `store orders pass through unchanged`() {
        assertEquals(listOf("a", "b", "c", "d"), ids(CodexSortMode.NEWEST_SAVED))
        assertEquals(listOf("a", "b", "c", "d"), ids(CodexSortMode.OLDEST_SAVED))
    }

    @Test fun `length sorts put known lengths first, then unresolved animations, then stills`() {
        val still = testPost(sourcePostId = "still")
        val unresolved = testPost(source = SourceKey.RULE34VIDEO, sourcePostId = "unresolved")
        val short = testPost(source = SourceKey.RULE34VIDEO, sourcePostId = "short")
        val long = testPost(source = SourceKey.RULE34VIDEO, sourcePostId = "long")
        val durations = mapOf(short.id to 5_000L, long.id to 90_000L)
        fun lengthIds(sort: CodexSortMode) =
            sortCodexPosts(listOf(still, unresolved, short, long), sort, emptyMap(), durations).map { it.id.sourcePostId }
        assertEquals(listOf("long", "short", "unresolved", "still"), lengthIds(CodexSortMode.LONGEST))
        assertEquals(listOf("short", "long", "unresolved", "still"), lengthIds(CodexSortMode.SHORTEST))
    }

    @Test fun `length sorts keep the order of the comparator that classified posts on every comparison`() {
        val random = Random(7)
        val posts = List(240) { index ->
            val video = ImageRef("https://example.com/$index.mp4", null, "video/mp4")
            when (index % 4) {
                0 -> testPost(source = SourceKey.RULE34VIDEO, sourcePostId = "s$index")
                1 -> testPost(sourcePostId = "m$index").copy(full = video, media = listOf(video))
                else -> testPost(sourcePostId = "p$index")
            }.copy(createdAtEpochMs = random.nextLong(40))
        }.shuffled(random)
        val durations = posts.filter { isAnimatedPost(it) && random.nextInt(3) > 0 }
            .associate { it.id to 1_000L * random.nextInt(1, 20) }
        fun reference(sort: CodexSortMode): Comparator<Post> {
            val groups = compareBy<Post> { post ->
                when {
                    post.id in durations -> 0
                    isAnimatedPost(post) -> 1
                    else -> 2
                }
            }
            return if (sort == CodexSortMode.LONGEST) {
                groups.thenByDescending { durations[it.id] }
            } else {
                groups.thenBy { durations[it.id] }
            }
        }
        for (sort in listOf(CodexSortMode.LONGEST, CodexSortMode.SHORTEST)) {
            assertEquals(posts.sortedWith(reference(sort)), sortCodexPosts(posts, sort, emptyMap(), durations))
        }
    }

    @Test fun `post usage reads one Codex's watch time and opens`() = runTest {
        val statistics = InMemoryStatisticsRepository(LifetimeStatistics(codexStatistics = mapOf(
            "mine" to CodexStatistics(posts = mapOf(
                key("a") to CodexPostStatistics(opens = 2L, videoMs = 30L, imageMs = 12L),
            )),
            "other" to CodexStatistics(posts = mapOf(key("b") to CodexPostStatistics(opens = 7L))),
        )))
        assertEquals(mapOf(key("a") to CodexUsage(watchingMs = 42L, opens = 2L)), statistics.codexPostUsage("mine"))
    }
}
