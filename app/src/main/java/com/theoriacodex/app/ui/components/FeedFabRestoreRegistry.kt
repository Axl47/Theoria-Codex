package com.theoriacodex.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.ui.state.MutationFeedback
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Stable
internal class FeedFabRestoreRegistry(
    private val repository: UiRestoreRepository,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val writes = Mutex()
    private var committed = emptyMap<String, FeedFabRestoreState>()
    private val revisions = mutableMapOf<String, Long>()
    val feedback = MutationFeedback()
    var states: Map<String, FeedFabRestoreState>? by mutableStateOf(null)
        private set

    suspend fun load() {
        if (states != null) return
        val result = runCatchingPreservingCancellation { repository.getFeedFabRestoreStates() }
        committed = result.getOrDefault(emptyMap())
        states = committed
        if (result.isFailure) feedback.add("Filter settings could not be restored. Default filters are active.")
    }

    fun state(contextKey: String, default: FeedFabRestoreState = FeedFabRestoreState()): FeedFabRestoreState? {
        val loaded = states ?: return null
        return loaded[contextKey] ?: default
    }

    fun update(contextKey: String, state: FeedFabRestoreState) {
        val loaded = states ?: return
        val revision = (revisions[contextKey] ?: 0L) + 1L
        revisions[contextKey] = revision
        states = loaded + (contextKey to state)
        scope.launch {
            writes.withLock {
                val result = runCatchingPreservingCancellation { repository.setFeedFabRestoreState(contextKey, state) }
                if (result.isSuccess) committed = committed + (contextKey to state)
                else {
                    if (revisions[contextKey] == revision) {
                        states = states.orEmpty() + (contextKey to (committed[contextKey] ?: FeedFabRestoreState()))
                    }
                    feedback.add("Filter settings could not be saved. Please try again.")
                }
            }
        }
    }
}

@Composable
internal fun rememberFeedFabRestoreRegistry(repository: UiRestoreRepository): FeedFabRestoreRegistry = viewModel(
    key = "feed-fab-restore",
    factory = remember(repository) { viewModelFactory { initializer { FeedFabRestoreRegistry(repository) } } },
)

internal const val SEARCH_FEED_FAB_CONTEXT = "search"
internal const val FOR_YOU_FEED_FAB_CONTEXT = "for-you"

internal fun creatorFeedFabContext(source: String, profileId: String): String = "creator:$source:$profileId"
internal fun codexFeedFabContext(codexId: String): String = "codex:$codexId"
