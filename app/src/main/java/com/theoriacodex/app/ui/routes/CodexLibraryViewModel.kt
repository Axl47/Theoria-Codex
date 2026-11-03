package com.theoriacodex.app.ui.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.codex.CodexCollectionPresentation
import com.theoriacodex.app.codex.CodexCollectionSource
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.domain.model.Codex
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The Library's read model, owned by its navigation entry. The entry outlives the Library's
 * composition while a Codex is open, so returning paints the last known tiles immediately.
 * Null means nothing has loaded yet: only a cold start waits, keeping a placeholder grid from
 * clamping the restored scroll position.
 */
internal class CodexLibraryViewModel(private val data: DataDependencies) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000L)
    val source = CodexCollectionSource(data.codexRepository, data.storageDirectory, data.cacheRepository)
    val settings: StateFlow<AppSettings?> = data.settingsRepository.observeSettings().stateIn(viewModelScope, started, null)
    val codices: StateFlow<List<Codex>?> = data.codexRepository.observeCodices().stateIn(viewModelScope, started, null)
    val savedSearches: StateFlow<List<SavedSearchEntry>?> =
        data.savedSearches.observeSavedSearches().stateIn(viewModelScope, started, null)

    private var collectionKey: Pair<Set<String>, Int>? = null
    private var collectionState: StateFlow<CodexCollectionPresentation>? = null

    /** Counts and covers for these Codices; asking for the same ones again keeps their last values. */
    fun collection(ids: Set<String>, generation: Int): StateFlow<CodexCollectionPresentation> {
        val key = ids to generation
        collectionState?.takeIf { collectionKey == key }?.let { return it }
        return source.observe(ids).stateIn(viewModelScope, started, CodexCollectionPresentation()).also {
            collectionKey = key
            collectionState = it
        }
    }
}
