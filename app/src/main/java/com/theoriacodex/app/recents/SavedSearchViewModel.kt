package com.theoriacodex.app.recents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.SavedSearchRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Owns durable pin changes outside the lifetime of the picker or name dialog. */
internal class SavedSearchViewModel(private val repository: SavedSearchRepository) : ViewModel() {
    val savedSearches = repository.observeSavedSearches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val messages = Channel<String>(Channel.BUFFERED)
    val effects = messages.receiveAsFlow()

    fun save(name: String, entry: RecentSearchEntry) = mutate("Search pinned.") {
        repository.save(name, entry)
    }

    fun rename(id: String, name: String) = mutate("Saved search renamed.") {
        repository.rename(id, name)
    }

    fun remove(id: String) = mutate("Saved search removed.") {
        repository.remove(id)
    }

    private fun mutate(successMessage: String, operation: suspend () -> Unit) {
        viewModelScope.launch {
            val outcome = runCatchingPreservingCancellation { operation() }
            messages.send(outcome.fold(
                onSuccess = { successMessage },
                onFailure = { failure ->
                    (failure as? IllegalArgumentException)?.message
                        ?: "Could not update saved searches. Please try again."
                },
            ))
        }
    }
}
