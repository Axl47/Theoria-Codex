package com.theoriacodex.app.recents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.SavedSearchRepository
import com.theoriacodex.data.repository.savedFor
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SavedSearchViewModel(private val repository: SavedSearchRepository) : ViewModel() {
    val savedSearches = repository.observeSavedSearches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val messages = Channel<String>(Channel.BUFFERED)
    val effects = messages.receiveAsFlow()
    private val justSaved = MutableStateFlow<String?>(null)
    private var confirmation: Job? = null

    val justSavedId: StateFlow<String?> = justSaved.asStateFlow()

    fun save(name: String, entry: RecentSearchEntry) = mutate("Search saved.") {
        repository.save(name, entry)
    }

    fun saveInPlace(name: String, entry: RecentSearchEntry) = mutate(successMessage = null) {
        if (repository.observeSavedSearches().first().savedFor(entry) == null) confirm(repository.save(name, entry).id)
    }

    fun rename(id: String, name: String) = mutate("Saved search renamed.") {
        justSaved.compareAndSet(id, null)
        repository.rename(id, name)
    }

    fun remove(id: String) = mutate("Saved search removed.") {
        repository.remove(id)
    }

    private fun confirm(id: String) {
        justSaved.value = id
        confirmation?.cancel()
        confirmation = viewModelScope.launch {
            delay(SAVED_CONFIRM_MS)
            justSaved.compareAndSet(id, null)
        }
    }

    private fun mutate(successMessage: String?, operation: suspend () -> Unit) {
        viewModelScope.launch {
            val outcome = runCatchingPreservingCancellation { operation() }
            outcome.fold(
                onSuccess = { successMessage },
                onFailure = { failure ->
                    (failure as? IllegalArgumentException)?.message
                        ?: "Could not update saved searches. Please try again."
                },
            )?.let { messages.send(it) }
        }
    }

    private companion object {
        const val SAVED_CONFIRM_MS = 5_000L
    }
}
