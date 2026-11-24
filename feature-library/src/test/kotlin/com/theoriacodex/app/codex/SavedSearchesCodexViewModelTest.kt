package com.theoriacodex.app.codex

import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.search.TestAdapter
import com.theoriacodex.app.search.TestRegistry
import com.theoriacodex.app.search.testSearchCoordinator
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.InMemoryCacheRepository
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.NewPostMarks
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.MediaFilter
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
    @Test fun `saved searches on different sources run newest first and merge by canonical identity without Search writes`() = runTest {
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
            val pixiv = entry("pixiv", "native a")
            val aibooru = SavedSearchEntry("aibooru", "aibooru",
                RecentSearchEntry(query("native_b", QueryMode.Source(SourceKey.AIBOORU)), "aibooru", 1), 1)
            owner.synchronize(listOf(ordinary, pixiv, aibooru), AppSettings(), adapters.map { it.sourceKey }.toSet())
            advanceUntilIdle()
            assertEquals(setOf(SourceKey.PIXIV, SourceKey.AIBOORU), owner.state.value.posts.map { it.id.source }.toSet())
            assertEquals(2, owner.state.value.posts.size)
            assertTrue(calls.any { it.mode == QueryMode.Source(SourceKey.PIXIV) && it.includeTags == listOf("native a") })
            assertTrue(calls.any { it.mode == QueryMode.Source(SourceKey.AIBOORU) && it.includeTags == listOf("native_b") })
            assertNull(queries.observeAppliedQuery("source:PIXIV").first())
            assertTrue(recents.observeSearches().first().isEmpty())
            assertEquals(beforeStats, statistics.observeStatistics().first())
            assertTrue(calls.filter { it.includeTags.any { tag -> tag == "red" || tag == "blue" } }
                .all { it.excludeTags == listOf("rain") && it.sort == SortMode.NEWEST })
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

    @Test fun `an Animated only saved search hides stills a source could not leave out, on every page`() = runTest {
        val source = adapter(SourceKey.PIXIV) { _, token ->
            val page = token ?: "first"
            Page(
                listOf(
                    testPost(SourceKey.PIXIV, "$page-moving").copy(canonicalTags = listOf("animated")),
                    testPost(SourceKey.PIXIV, "$page-still"),
                ),
                "second".takeIf { token == null },
            )
        }
        withOwner(listOf(source)) { owner ->
            val animated = entry("moving", "cat").let { it.copy(search = it.search.copy(query = it.search.query.copy(media = MediaFilter.ANIMATED))) }
            owner.synchronize(listOf(animated), AppSettings(), setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            owner.loadMore()
            advanceUntilIdle()
            assertEquals(listOf("first-moving", "second-moving"), owner.state.value.posts.map { it.id.sourcePostId })
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

    @Test fun `reopening a feed loaded before a check found new posts reloads the newest first page, keeping old pages and showing the error if that fails (ADR 0152)`() = runTest {
        var uploads = listOf("old1", "old2", "old3", "old4")
        var offline = false
        val source = adapter(SourceKey.PIXIV) { query, token ->
            if (query.includeTags.single() == "dogs") return@adapter Page(listOf(testPost(SourceKey.PIXIV, "dog")), null)
            check(!offline) { "offline" }
            val page = token?.toInt() ?: 0
            Page(uploads.drop(page * 2).take(2).map { testPost(SourceKey.PIXIV, it) }, "${page + 1}".takeIf { (page + 1) * 2 < uploads.size })
        }
        withOwner(listOf(source)) { owner ->
            val saved = listOf(entry("cats", "cats"), entry("dogs", "dogs"))
            owner.synchronize(saved, AppSettings(), setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            val loadedAt = checkNotNull(owner.state.value.loadedAtEpochMs)
            val baseline = NewPostMarks().checked(listOf("old1", "old2"), loadedAt - 1)
            uploads = listOf("new1", "new2", "new3") + uploads
            assertFalse(owner.state.value.predates(listOf(baseline.checked(listOf("new1", "new2"), loadedAt - 1))))
            assertFalse(owner.state.value.predates(listOf(baseline.checked(listOf("old1", "old2"), loadedAt + 1))))
            val found = listOf(baseline.checked(listOf("new1", "new2"), loadedAt + 1))

            owner.synchronize(saved, AppSettings(), setOf(SourceKey.PIXIV))
            assertTrue(owner.state.value.predates(found))
            offline = true
            owner.refresh()
            owner.refresh()
            assertEquals(listOf("old1", "old2", "dog"), owner.state.value.posts.map { it.id.sourcePostId })
            advanceUntilIdle()
            assertEquals(listOf("old1", "old2", "dog"), owner.state.value.posts.map { it.id.sourcePostId })
            assertEquals(setOf("cats"), owner.state.value.errors.keys)
            assertTrue(owner.state.value.predates(found))

            offline = false
            owner.retry()
            advanceUntilIdle()
            assertEquals(listOf("new1", "new2", "dog"), owner.state.value.posts.map { it.id.sourcePostId })
            assertTrue(owner.state.value.errors.isEmpty())

            owner.loadMore()
            advanceUntilIdle()
            assertEquals(listOf("new1", "new2", "new3", "old1", "dog"), owner.state.value.posts.map { it.id.sourcePostId })
        }
    }

    @Test fun `load more on one newest-first saved search appends below the posts already shown (ADR 0152)`() = runTest {
        val edited = mapOf("p1" to 50L, "p2" to 90L, "p3" to 40L, "p4" to 95L, "p5" to 30L, "p6" to 99L)
        val pages = listOf(listOf("p1", "p2", "p3"), listOf("p4", "p5", "p6"))
        val source = adapter(SourceKey.GELBOORU) { _, token ->
            val page = token?.toInt() ?: 0
            Page(pages[page].map { testPost(SourceKey.GELBOORU, it, createdAtEpochMs = edited.getValue(it)) }, "1".takeIf { page == 0 })
        }
        withOwner(listOf(source)) { owner ->
            val saved = SavedSearchEntry("gel", "gel",
                RecentSearchEntry(query("tag", QueryMode.Source(SourceKey.GELBOORU)), "gel", 1), 1)
            owner.synchronize(listOf(saved), AppSettings(), setOf(SourceKey.GELBOORU))
            advanceUntilIdle()
            val shown = { owner.state.value.run { sortLiveCodexPosts(posts, CodexSortMode.NEWEST_SAVED, batches = batches) }.map { it.id.sourcePostId } }
            val firstScreen = shown()

            owner.loadMore()
            advanceUntilIdle()

            assertEquals(firstScreen, shown().take(firstScreen.size))
        }
    }

    @Test fun `a Top saved search's collection refreshes to the newest uploads, not its top-scored page (ADR 0152)`() = runTest {
        var uploads = listOf("old1" to 20L, "old2" to 10L)
        val topScored = listOf("best1" to 2L, "best2" to 1L)
        val source = adapter(SourceKey.GELBOORU) { query, _ ->
            val ids = if (query.sort == SortMode.NEWEST) uploads else topScored
            Page(ids.map { (id, uploaded) -> testPost(SourceKey.GELBOORU, id, createdAtEpochMs = uploaded) }, null)
        }
        withOwner(listOf(source)) { owner ->
            val top = query("tag", QueryMode.Source(SourceKey.GELBOORU)).copy(sort = SortMode.TOP)
            val saved = SavedSearchEntry("gel", "gel", RecentSearchEntry(top, "gel", 1), 1)
            owner.synchronize(listOf(saved), AppSettings(), setOf(SourceKey.GELBOORU))
            advanceUntilIdle()
            uploads = listOf("new1" to 30L) + uploads

            owner.refresh()
            advanceUntilIdle()

            val shown = owner.state.value.run { sortLiveCodexPosts(posts, CodexSortMode.NEWEST_SAVED, batches = batches) }
            assertEquals(listOf("new1", "old1", "old2"), shown.map { it.id.sourcePostId })
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
            val owner = SavedSearchesCodexViewModel(SavedSearchFeedLoader(coordinator, dispatcher), InMemoryCacheRepository())
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
