package com.theoriacodex.app.recents

import androidx.lifecycle.ViewModelStore
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.InMemorySavedSearchRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.repository.NewPostMarks
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.SavedSearchNewPosts
import com.theoriacodex.data.repository.SavedSearchRepository
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
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
    private val newPosts = InMemoryUiRestoreRepository()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `dismissing picker cannot cancel accepted save job and feedback waits for commit`() = runTest {
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
        assertEquals("Search saved.", owner.effects.first())
    }

    @Test
    fun `rename and removal use stable saved search identity`() = runTest {
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
    fun `updating a saved search's query starts its new posts over, and an unchanged update keeps them (ADR 0218)`() = runTest {
        val edited = repository.save("Edited", search())
        val other = repository.save("Other", search().copy(queryHash = "other", query = search().query.copy(sort = SortMode.POPULAR)))
        val marks = NewPostMarks(seenPostIds = listOf("PIXIV:1"), latestPostIds = listOf("PIXIV:2", "PIXIV:1"), checkedAtEpochMs = 5)
        val state = SavedSearchNewPosts(attempts = mapOf(edited.id to 5, other.id to 5), marks = mapOf(edited.id to marks, other.id to marks))
        newPosts.updateSavedSearchNewPosts { state }
        val owner = owner()

        owner.update(edited.id, search().copy(searchedAtEpochMs = 900))
        advanceUntilIdle()
        assertEquals(state, newPosts.observeSavedSearchNewPosts().first())

        val night = search().copy(queryHash = "day", query = search().query.withIncludeTermsAsRequired(listOf(SearchTerm("day"))))
        owner.update(edited.id, night)
        advanceUntilIdle()

        assertEquals(edited.copy(search = night), repository.observeSavedSearches().first().first { it.id == edited.id })
        assertEquals(SavedSearchNewPosts(mapOf(other.id to 5L), mapOf(other.id to marks)), newPosts.observeSavedSearchNewPosts().first())
        assertEquals("Saved search updated.", owner.effects.first())
    }

    @Test
    fun `an update matching another saved search is refused and changes nothing (ADR 0218)`() = runTest {
        val edited = repository.save("Edited", search())
        val other = repository.save("Other", search().copy(queryHash = "other", query = search().query.copy(sort = SortMode.POPULAR)))
        val owner = owner()

        owner.update(edited.id, other.search)
        advanceUntilIdle()

        assertEquals("“Other” already saves this search.", owner.effects.first())
        assertEquals(search(), repository.observeSavedSearches().first().first { it.id == edited.id }.search)
    }

    @Test
    fun `storage failure reports actionable feedback without publishing a saved search`() = runTest {
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
    fun `saving in place confirms only after the save commits, then the confirmation lapses`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gated = object : SavedSearchRepository by repository {
            override suspend fun save(name: String, search: RecentSearchEntry): SavedSearchEntry {
                gate.await()
                return repository.save(name, search)
            }
        }
        val owner = owner(gated)
        owner.saveInPlace("Evening", search())
        runCurrent()
        assertEquals(null, owner.justSavedId.value)

        gate.complete(Unit)
        runCurrent()
        assertEquals(repository.observeSavedSearches().first().single().id, owner.justSavedId.value)
        advanceUntilIdle()
        assertEquals(null, owner.justSavedId.value)
    }

    @Test
    fun `a failed save in place reports the failure and never confirms`() = runTest {
        val full = object : SavedSearchRepository by repository {
            override suspend fun save(name: String, search: RecentSearchEntry): SavedSearchEntry =
                throw IllegalArgumentException("You can pin up to 200 searches. Remove one before adding another.")
        }
        val owner = owner(full)
        owner.saveInPlace("Evening", search())
        runCurrent()

        assertEquals(null, owner.justSavedId.value)
        assertEquals("You can pin up to 200 searches. Remove one before adding another.", owner.effects.first())
    }

    @Test
    fun `saving in place leaves an already saved search and its name alone`() = runTest {
        val pin = repository.save("Chosen name", search())
        val owner = owner()
        owner.saveInPlace("night -comic", search().copy(searchedAtEpochMs = 200))
        advanceUntilIdle()

        assertEquals(listOf(pin), repository.observeSavedSearches().first())
        assertEquals(null, owner.justSavedId.value)
    }

    @Test
    fun `saved search remains replayable after rolling history prunes it and clear removes all recent activity`() = runTest {
        val recents = InMemoryRecentsRepository(searchLimit = 1)
        val original = search()
        recents.recordSearch(original.query, original.queryHash)
        val pin = repository.save("Keep this", recents.observeSearches().first().single())
        recents.recordSearch(original.query.withIncludeTermsAsRequired(emptyList()), "later-query")
        assertEquals("later-query", recents.observeSearches().first().single().queryHash)

        RecentsClearWorkflow(recents).clear(
            target = RecentsClearTarget.ALL,
            watchedPosts = emptyList(),
            codexPosts = emptyList(),
            searches = recents.observeSearches().first(),
            showActionableFeedback = { _, _ -> false },
        )

        assertTrue(recents.observeSearches().first().isEmpty())
        assertEquals(listOf(pin), repository.observeSavedSearches().first())
        assertEquals(original.query, repository.observeSavedSearches().first().single().search.query)
    }

    private fun owner(source: SavedSearchRepository = repository) = SavedSearchViewModel(source, newPosts).also {
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
