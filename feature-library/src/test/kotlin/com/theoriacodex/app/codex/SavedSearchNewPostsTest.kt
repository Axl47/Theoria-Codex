package com.theoriacodex.app.codex

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedSearchNewPostsTest {
    private val hour = 60 * 60 * 1000L
    private val store = InMemoryUiRestoreRepository()
    private var pages = mapOf<String, List<String>?>()
    private val checked = mutableListOf<String>()
    private val checkedSorts = mutableListOf<SortMode>()
    private var hang = false
    private var gate: CompletableDeferred<Unit>? = null
    private var stored = listOf<SavedSearchEntry>()
    private val checker = SavedSearchNewPostsChecker(store, { stored }) { entry ->
        checked += entry.id
        checkedSorts += entry.search.query.sort
        if (hang) awaitCancellation()
        gate?.await()
        pages[entry.id]?.map { testPost(SourceKey.PIXIV, it) }
    }

    private suspend fun check(entries: List<SavedSearchEntry>, now: Long) {
        stored = entries
        checker.checkIfStale(entries, now)
    }

    private suspend fun counts() = store.observeSavedSearchNewPosts().first().marks.mapValues { it.value.newPostCount }

    @Test fun `first check sets the baseline, later ones count, and opening clears them`() = runTest {
        val searches = listOf(entry("a", 1))
        pages = mapOf("a" to listOf("1"))
        check(searches, 0L)
        assertEquals(mapOf("a" to 0), counts())
        pages = mapOf("a" to listOf("2", "1"))
        check(searches, 3 * hour)
        assertEquals(mapOf("a" to 1), counts())
        store.markSavedSearchesSeen(setOf("a"))
        assertEquals(mapOf("a" to 0), counts())
    }

    @Test fun `a feed clears only the posts it showed`() = runTest {
        val searches = listOf(entry("a", 1))
        pages = mapOf("a" to listOf("1"))
        check(searches, 0L)
        pages = mapOf("a" to listOf("2", "1"))
        check(searches, 3 * hour)
        store.markSavedSearchPostsSeen(setOf("PIXIV:9"))
        assertEquals(mapOf("a" to 1), counts())
        store.markSavedSearchPostsSeen(setOf("PIXIV:2"))
        assertEquals(mapOf("a" to 0), counts())
    }

    @Test fun `checks wait out the interval after a failure and take the eight newest`() = runTest {
        val searches = (1..10).map { entry("s$it", it.toLong()) }
        pages = searches.associate { it.id to null }
        check(searches, 0L)
        check(searches, 2 * hour)
        assertEquals((10 downTo 3).map { "s$it" }.toSet(), checked.toSet())
        assertEquals(8, checked.size)
        assertEquals(emptyMap<String, Int>(), counts())
    }

    @Test fun `searches are checked newest first whatever sort they were saved with`() = runTest {
        pages = mapOf("a" to listOf("1"))
        check(listOf(entry("a", 1, SortMode.RANDOM)), 0L)
        assertEquals(listOf(SortMode.NEWEST), checkedSorts)
    }

    @Test fun `a search cancelled by leaving the app is not sent again within the interval`() = runTest {
        val searches = listOf(entry("a", 1))
        hang = true
        val cancelled = launch { check(searches, 0L) }
        runCurrent()
        cancelled.cancelAndJoin()
        hang = false
        pages = mapOf("a" to listOf("1"))
        check(searches, hour)
        assertEquals(listOf("a"), checked)
        check(searches, 3 * hour)
        assertEquals(listOf("a", "a"), checked)
        assertEquals(mapOf("a" to 0), counts())
    }

    @Test fun `overlapping checks send each search once`() = runTest {
        val searches = listOf(entry("a", 1), entry("b", 2))
        hang = true
        val first = launch { check(searches, 0L) }
        val second = launch { check(searches, 0L) }
        runCurrent()
        assertEquals(setOf("a", "b"), checked.toSet())
        assertEquals(2, checked.size)
        first.cancelAndJoin()
        second.cancelAndJoin()
    }

    @Test fun `editing a saved search starts its new posts over and drops a check of the old query still running (ADR 0218)`() = runTest {
        val saved = savedSearchWithTwoNew()
        val oldQueryAnswer = CompletableDeferred<Unit>()
        gate = oldQueryAnswer
        pages = mapOf("a" to listOf("old2", "old1"))
        val running = launch { check(listOf(saved), 6 * hour) }
        runCurrent()
        store.resetSavedSearchNewPosts(saved.id)
        oldQueryAnswer.complete(Unit)
        running.join()
        assertEquals(emptyMap<String, Int>(), counts())
        gate = null
        pages = mapOf("a" to listOf("9", "3"))
        check(listOf(saved), 6 * hour + 1)
        assertEquals(mapOf("a" to 0), counts())
    }

    @Test fun `a check started before an edit drops what the old query found even when it claims after the edit (ADR 0218)`() = runTest {
        val original = savedSearchWithTwoNew()
        val edited = original.copy(search = original.search.copy(queryHash = "edited"))
        stored = listOf(edited)
        store.resetSavedSearchNewPosts(original.id)
        pages = mapOf("a" to listOf("old2", "old1"))
        checker.checkIfStale(listOf(original), 6 * hour)
        assertEquals(emptyMap<String, Int>(), counts())
        pages = mapOf("a" to listOf("9", "3"))
        check(listOf(edited), 9 * hour)
        assertEquals(mapOf("a" to 0), counts())
    }

    private suspend fun savedSearchWithTwoNew(): SavedSearchEntry {
        val saved = entry("a", 1)
        pages = mapOf("a" to listOf("1"))
        check(listOf(saved), 0L)
        pages = mapOf("a" to listOf("3", "2", "1"))
        check(listOf(saved), 3 * hour)
        assertEquals(mapOf("a" to 2), counts())
        return saved
    }

    @Test fun `opening a saved search keeps its marks until Search shows results for it`() = runTest {
        val saved = savedSearchWithTwoNew()
        val opens = SavedSearchOpenMarks(store)
        opens.opened(saved)
        opens.resultsShown(saved.search.query.copy(minScore = 5), listOf(testPost(SourceKey.PIXIV, "3")))
        assertEquals(mapOf("a" to 2), counts())
    }

    @Test fun `results clear only the posts they showed`() = runTest {
        val saved = savedSearchWithTwoNew()
        val opens = SavedSearchOpenMarks(store)
        opens.opened(saved)
        opens.resultsShown(saved.search.query, listOf(testPost(SourceKey.PIXIV, "3"), testPost(SourceKey.PIXIV, "1")))
        assertEquals(mapOf("a" to 1), counts())
        opens.resultsShown(saved.search.query, listOf(testPost(SourceKey.PIXIV, "2")))
        assertEquals(mapOf("a" to 1), counts())
    }

    private fun entry(id: String, savedAt: Long, sort: SortMode = SortMode.NEWEST) = SavedSearchEntry(id, id, RecentSearchEntry(
        Query(QueryMode.Unified, listOf("tag"), emptyList(), sort, null, null), id, 1,
    ), savedAt)
}
