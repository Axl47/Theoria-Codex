package com.theoriacodex.app.codex

import com.theoriacodex.app.statistics.statisticsPostKey
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CodexPostStatistics
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.CodexStatistics
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.LifetimeStatistics
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexPostSortingTest {
    // Newest saved first, as the store returns them.
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

    @Test fun `live collections refine their post-date order the same way`() {
        val live = listOf(
            testPost(sourcePostId = "old", createdAtEpochMs = 1L, mediaCount = 2),
            testPost(sourcePostId = "new", createdAtEpochMs = 9L, mediaCount = 2),
            testPost(sourcePostId = "long", createdAtEpochMs = 5L, mediaCount = 6),
        )
        fun liveIds(sort: CodexSortMode) = sortLiveCodexPosts(live, sort).map(Post::id).map { it.sourcePostId }
        assertEquals(listOf("new", "long", "old"), liveIds(CodexSortMode.NEWEST_SAVED))
        assertEquals(listOf("long", "new", "old"), liveIds(CodexSortMode.MOST_PAGES))
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
