package com.theoriacodex.app.codex

import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.search.TestAdapter
import com.theoriacodex.app.search.TestRegistry
import com.theoriacodex.app.search.testSearchCoordinator
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.InMemoryCacheRepository
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SavedSearchesCodexViewModelTest {
    @Test fun `saved FYP keeps native per-source tags and union preserves canonical identities without Search writes`() = runTest {
        val calls = mutableListOf<Query>()
        val adapters = listOf(SourceKey.PIXIV, SourceKey.AIBOORU).map { source -> adapter(source) { query, _ ->
            calls += query
            Page(listOf(testPost(source, "shared").copy(canonicalTags = listOf("red", "blue"))), null)
        } }
        val queries = InMemoryQueryRepository()
        val recents = InMemoryRecentsRepository()
        val statistics = InMemoryStatisticsRepository()
        withOwner(adapters, queries, recents, statistics) { owner ->
            val beforeStats = statistics.observeStatistics().first()
            val grouped = query("ordinary").copy(
                includeTermGroups = listOf(SearchTermGroup(listOf(SearchTerm("red"), SearchTerm("blue")))),
                excludeTerms = listOf(SearchTerm("rain")), sort = SortMode.POPULAR,
            )
            val ordinary = entry("normal", "ordinary").let { it.copy(search = it.search.copy(query = grouped)) }
            val fyp = entry("fyp", "unused").copy(search = RecentSearchEntry(
                query("unused", QueryMode.Unified), "fyp-seed", 1, RecentSearchKind.FYP,
                listOf(SourceKey.AIBOORU, SourceKey.PIXIV),
                linkedMapOf(SourceKey.AIBOORU to listOf("native_b"), SourceKey.PIXIV to listOf("native a")),
            ))
            owner.synchronize(listOf(ordinary, fyp), AppSettings(), adapters.map { it.sourceKey }.toSet())
            advanceUntilIdle()
            assertEquals(setOf(SourceKey.PIXIV, SourceKey.AIBOORU), owner.state.value.posts.map { it.id.source }.toSet())
            assertEquals(2, owner.state.value.posts.size)
            assertTrue(calls.any { it.mode == QueryMode.Source(SourceKey.PIXIV) && it.includeTags == listOf("native a") })
            assertTrue(calls.any { it.mode == QueryMode.Source(SourceKey.AIBOORU) && it.includeTags == listOf("native_b") })
            assertNull(queries.observeAppliedQuery("source:PIXIV").first())
            assertTrue(recents.observeSearches().first().isEmpty())
            assertEquals(beforeStats, statistics.observeStatistics().first())
            assertTrue(calls.filter { it.includeTags.any { tag -> tag == "red" || tag == "blue" } }
                .all { it.excludeTags == listOf("rain") && it.sort == SortMode.POPULAR })
        }
    }

    @Test fun `empty duplicate pages retain continuation while Retry only reloads the failed saved query`() = runTest {
        var fail = true
        val calls = mutableListOf<Pair<String, String?>>()
        val source = adapter(SourceKey.PIXIV) { query, token ->
            val tag = query.includeTags.single()
            calls += tag to token
            if (tag == "bad" && fail) error("unavailable")
            when (token) {
                null -> Page(listOf(testPost(SourceKey.PIXIV, "shared")), "next")
                "next" -> Page(emptyList(), "last")
                else -> Page(listOf(testPost(SourceKey.PIXIV, "last")), null)
            }
        }
        withOwner(listOf(source)) { owner ->
            owner.synchronize(listOf(entry("good", "good"), entry("bad", "bad")), AppSettings(), setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            assertEquals(setOf("bad"), owner.state.value.errors.keys)
            fail = false
            owner.retry()
            advanceUntilIdle()
            assertEquals(1, calls.count { it == ("good" to null) })
            assertTrue(owner.state.value.errors.isEmpty())
            owner.loadMore()
            advanceUntilIdle()
            assertTrue(owner.state.value.canLoadMore)
            assertEquals(1, owner.state.value.posts.size)
            owner.loadMore()
            advanceUntilIdle()
            assertFalse(owner.state.value.canLoadMore)
            assertEquals(listOf("shared", "last"), owner.state.value.posts.map { it.id.sourcePostId })
        }
    }

    @Test fun `Multi-Search partial failure keeps successful provider paging and retries only the failed provider`() = runTest {
        var fail = true
        val calls = mutableListOf<SourceKey>()
        val sources = listOf(SourceKey.PIXIV, SourceKey.AIBOORU).map { source -> adapter(source) { _, token ->
            calls += source
            if (source == SourceKey.AIBOORU && fail) error("unavailable")
            Page(listOf(testPost(source, token ?: "root")), "next".takeIf { token == null })
        } }
        val multi = entry("multi", "tag").let { it.copy(search = it.search.copy(
            query = query("tag", QueryMode.Unified), kind = RecentSearchKind.MULTI_SEARCH,
            sources = listOf(SourceKey.AIBOORU, SourceKey.PIXIV),
        )) }
        withOwner(sources) { owner ->
            owner.synchronize(listOf(multi), AppSettings(), sources.map { it.sourceKey }.toSet())
            advanceUntilIdle()
            assertEquals(1, owner.state.value.errors.size)
            assertTrue(owner.state.value.canLoadMore)
            owner.retry()
            advanceUntilIdle()
            assertTrue("A still-failing provider must not stop the successful provider", owner.state.value.canLoadMore)
            owner.loadMore()
            advanceUntilIdle()
            assertEquals(listOf("root", "next"), owner.state.value.posts.map { it.id.sourcePostId })
            val successes = calls.count { it == SourceKey.PIXIV }
            fail = false
            owner.retry()
            advanceUntilIdle()
            assertEquals(successes, calls.count { it == SourceKey.PIXIV })
            assertTrue(owner.state.value.errors.isEmpty())
            assertEquals(3, owner.state.value.posts.size)
        }
    }

    @Test fun `selection replacement rejects late results and renaming or selecting none does not create requests`() = runTest {
        var calls = 0
        val source = adapter(SourceKey.PIXIV) { query, _ ->
            calls++
            val tag = query.includeTags.single()
            withContext(NonCancellable) { delay(if (tag == "old") 1000 else 1) }
            Page(listOf(testPost(SourceKey.PIXIV, tag)), null)
        }
        withOwner(listOf(source)) { owner ->
            owner.synchronize(listOf(entry("old", "old")), AppSettings(), setOf(SourceKey.PIXIV))
            runCurrent()
            val current = entry("new", "new")
            owner.synchronize(listOf(current), AppSettings(), setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            assertEquals(listOf("new"), owner.state.value.posts.map { it.id.sourcePostId })
            owner.synchronize(listOf(current.copy(name = "Renamed")), AppSettings(), setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            assertEquals(2, calls)
            owner.synchronize(emptyList(), AppSettings(), setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            assertFalse(owner.state.value.loading)
            assertTrue(owner.state.value.posts.isEmpty())
            assertEquals(2, calls)
        }
    }

    @Test fun `saved search deadlines isolate failures and at most two saved searches overlap`() = runTest {
        var active = 0
        var peak = 0
        val source = adapter(SourceKey.PIXIV) { query, _ ->
            active++
            peak = maxOf(peak, active)
            try {
                val tag = query.includeTags.single()
                delay(if (tag == "slow") 30_000 else 100)
                Page(listOf(testPost(SourceKey.PIXIV, tag)), null)
            } finally { active-- }
        }
        withOwner(listOf(source)) { owner ->
            owner.synchronize(listOf("slow", "a", "b", "c").map { entry(it, it) }, AppSettings(), setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            assertEquals(2, peak)
            assertEquals(3, owner.state.value.posts.size)
            assertEquals(setOf("slow"), owner.state.value.errors.keys)
            assertFalse(owner.state.value.loading)
        }
    }

    private suspend fun TestScope.withOwner(
        adapters: List<SourceAdapter>,
        queries: InMemoryQueryRepository = InMemoryQueryRepository(),
        recents: InMemoryRecentsRepository = InMemoryRecentsRepository(),
        statistics: InMemoryStatisticsRepository = InMemoryStatisticsRepository(),
        body: suspend TestScope.(SavedSearchesCodexViewModel) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val coordinator = testSearchCoordinator(TestRegistry(adapters), queryRepository = queries,
                recentsRepository = recents, statisticsRepository = statistics, computationDispatcher = dispatcher)
            val owner = SavedSearchesCodexViewModel(coordinator, SavedSearchFeedLoader(coordinator, dispatcher), InMemoryCacheRepository())
            store.put("owner", owner)
            body(owner)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    private fun adapter(source: SourceKey, fetch: suspend (Query, String?) -> Page<Post>): SourceAdapter =
        object : SourceAdapter by TestAdapter(source) {
            override suspend fun search(query: Query, pageToken: String?) = fetch(query, pageToken)
        }

    private fun entry(id: String, tag: String) = SavedSearchEntry(id, id,
        RecentSearchEntry(query(tag), id, 1), 1)
    private fun query(tag: String, mode: QueryMode = QueryMode.Source(SourceKey.PIXIV)) =
        Query(mode, listOf(tag), emptyList(), SortMode.NEWEST, null, null)
}
