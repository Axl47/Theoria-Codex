package com.theoriacodex.app.recents

import androidx.lifecycle.ViewModelStore
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.InMemorySavedSearchRepository
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.SavedSearchRepository
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repository = InMemorySavedSearchRepository()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `dismissing picker cannot cancel accepted pin job and feedback waits for commit`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gated = object : SavedSearchRepository by repository {
            override suspend fun save(name: String, search: RecentSearchEntry): SavedSearchEntry {
                gate.await()
                return repository.save(name, search)
            }
        }
        val owner = owner(gated)
        val pickerCollector = launch { owner.effects.first() }
        owner.save("Evening", search())
        runCurrent()
        assertTrue(repository.observeSavedSearches().first().isEmpty())
        pickerCollector.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(search(), repository.observeSavedSearches().first().single().search)
        assertEquals("Search pinned.", owner.effects.first())
    }

    @Test
    fun `rename and removal use stable pin identity`() = runTest {
        val saved = repository.save("Original", search())
        val owner = owner()
        owner.rename(saved.id, "Renamed")
        advanceUntilIdle()
        assertEquals(saved.copy(name = "Renamed"), repository.observeSavedSearches().first().single())
        assertEquals("Saved search renamed.", owner.effects.first())

        owner.remove(saved.id)
        advanceUntilIdle()
        assertTrue(repository.observeSavedSearches().first().isEmpty())
        assertEquals("Saved search removed.", owner.effects.first())
    }

    @Test
    fun `storage failure reports actionable feedback without publishing a pin`() = runTest {
        val failing = object : SavedSearchRepository by repository {
            override suspend fun save(name: String, search: RecentSearchEntry): SavedSearchEntry = error("disk unavailable")
        }
        val owner = owner(failing)
        owner.save("Evening", search())
        advanceUntilIdle()

        assertTrue(repository.observeSavedSearches().first().isEmpty())
        assertEquals("Could not update saved searches. Please try again.", owner.effects.first())
    }

    @Test
    fun `pin remains replayable after rolling history prunes it and clear removes all recent activity`() = runTest {
        val recents = InMemoryRecentsRepository(searchLimit = 1)
        val original = search()
        recents.recordSearch(original.query, original.queryHash)
        val pin = repository.save("Keep this", recents.observeSearches().first().single())
        recents.recordSearch(original.query.copy(includeTerms = emptyList()), "later-query")
        assertEquals("later-query", recents.observeSearches().first().single().queryHash)

        RecentsClearWorkflow(recents).clear(
            target = RecentsClearTarget.ALL,
            watchedPosts = emptyList(),
            codexPosts = emptyList(),
            searches = recents.observeSearches().first(),
            fypSearches = emptyList(),
            showActionableFeedback = { _, _ -> false },
        )

        assertTrue(recents.observeSearches().first().isEmpty())
        assertEquals(listOf(pin), repository.observeSavedSearches().first())
        assertEquals(original.query, repository.observeSavedSearches().first().single().search.query)
    }

    private fun owner(source: SavedSearchRepository = repository) = SavedSearchViewModel(source).also {
        store.put("saved-searches", it)
    }

    private fun search() = RecentSearchEntry(
        query = Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = listOf("night"),
            excludeTags = listOf("comic"),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        ),
        queryHash = "exact-search",
        searchedAtEpochMs = 100,
    )
}
