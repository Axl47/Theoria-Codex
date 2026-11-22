package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.codex.CodexCollectionPresentation
import com.theoriacodex.app.codex.CodexCollectionSource
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.domain.model.Codex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Every Codex's post count and cover candidates, for all profiles. Activity-owned, so a save costs one summary query
 * and one thumbnail listing however many screens (Library, Save to Codex, Search) show covers; each filters by profile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CodexCollectionViewModel(codices: CodexRepository, source: CodexCollectionSource) : ViewModel() {
    private val thumbnailGeneration = MutableStateFlow(0)

    val presentation: StateFlow<CodexCollectionPresentation?> = combine(
        codices.observeCodices().map { rows -> rows.mapTo(linkedSetOf(), Codex::codexId) }.distinctUntilChanged(),
        thumbnailGeneration,
    ) { ids, _ -> ids }
        .flatMapLatest(source::observe)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /** Resolves covers again after clearing the thumbnail cache deleted files they pointed at. */
    fun thumbnailCacheCleared() = thumbnailGeneration.update { it + 1 }
}

@Composable
internal fun codexCollectionOwner(data: DataDependencies): CodexCollectionViewModel =
    viewModel(viewModelStoreOwner = activityViewModelStoreOwner(), key = "codex-collection") {
        CodexCollectionViewModel(
            data.codexRepository,
            CodexCollectionSource(data.codexRepository, data.storageDirectory, data.cacheRepository),
        )
    }

/** The shared [CodexCollectionViewModel.presentation], empty until its first emission. */
@Composable
internal fun rememberCodexCollection(data: DataDependencies): CodexCollectionPresentation =
    codexCollectionOwner(data).presentation.collectAsStateWithLifecycle().value ?: CodexCollectionPresentation()
