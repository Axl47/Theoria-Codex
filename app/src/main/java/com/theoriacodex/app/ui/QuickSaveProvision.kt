package com.theoriacodex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.codex.CodexCollectionSource
import com.theoriacodex.app.codex.CodexSaveRelevanceLoader
import com.theoriacodex.app.codex.CodexSaver
import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.app.codex.QuickSaveRecord
import com.theoriacodex.app.codex.QuickSaveViewModel
import com.theoriacodex.app.codex.SaveSuggestionSource
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * The app's [QuickSave]: an Activity-owned save and undo owner, plus suggestions ranked from the shared
 * relevance [loader] and this session's search saves (the same memory the picker ranks by). A Codex waiting to be
 * deleted is never suggested.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun rememberQuickSave(shell: TheoriaShell, loader: CodexSaveRelevanceLoader): QuickSave {
    val data = shell.data
    val owner = viewModel(key = "quick-save-owner") {
        QuickSaveViewModel(
            CodexSaver(shell.workflows.codexTransfer, data.codexRepository, data.statisticsRepository),
            data.codexRepository,
            cacheFullImage = { data.settingsRepository.observeSettings().first().cache.cacheFullImageOnSave },
        )
    }
    return remember(owner, shell, loader) {
        val searchSaves = shell.pendingSave.searchSaves
        val deleting = shell.owners.codexCommands.pendingDeletes.map { it.keys }.distinctUntilChanged()
        val suggestions = SaveSuggestionSource(
            data.codexRepository, data.settingsRepository,
            CodexCollectionSource(data.codexRepository, data.storageDirectory, data.cacheRepository),
            loader, searchSaves, shell.scope,
        )
        object : QuickSave {
            override val lastQuickSave: StateFlow<QuickSaveRecord?> = owner.lastQuickSave
            override suspend fun quickSave(post: Post, codexId: String, query: Query?, fromForYou: Boolean) =
                owner.quickSave(post, codexId, fromForYou)?.also { searchSaves.record(query, codexId) }
            override suspend fun saveToNew(post: Post, name: String, profileId: String, query: Query?, fromForYou: Boolean) =
                owner.saveToNew(post, name, profileId, fromForYou)?.also { searchSaves.record(query, it.codexId) }
            override suspend fun undoQuickSave(record: QuickSaveRecord) = owner.undoQuickSave(record)
            override fun suggestions(post: Post, query: Query?, limit: Int): Flow<SaveSuggestions> =
                deleting.flatMapLatest { hidden ->
                    suggestions.observe(post, query, limit + hidden.size).map { found ->
                        found.copy(targets = found.targets.filterNot { it.codexId in hidden }.take(limit))
                    }
                }
        }
    }
}
