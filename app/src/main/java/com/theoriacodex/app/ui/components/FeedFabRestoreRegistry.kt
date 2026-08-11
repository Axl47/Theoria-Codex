package com.theoriacodex.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.UiRestoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
internal class FeedFabRestoreRegistry(
    private val repository: UiRestoreRepository,
    private val scope: CoroutineScope,
) {
    var states: Map<String, FeedFabRestoreState>? by mutableStateOf(null)
        private set

    suspend fun load() {
        if (states != null) return
        states = repository.getFeedFabRestoreStates()
    }

    fun state(contextKey: String, default: FeedFabRestoreState = FeedFabRestoreState()): FeedFabRestoreState? {
        val loaded = states ?: return null
        return loaded[contextKey] ?: default
    }

    fun update(contextKey: String, state: FeedFabRestoreState) {
        val loaded = states ?: return
        states = loaded + (contextKey to state)
        scope.launch { repository.setFeedFabRestoreState(contextKey, state) }
    }
}

@Composable
internal fun rememberFeedFabRestoreRegistry(
    repository: UiRestoreRepository,
): FeedFabRestoreRegistry {
    val scope = rememberCoroutineScope()
    return remember(repository) { FeedFabRestoreRegistry(repository, scope) }
}

internal const val SEARCH_FEED_FAB_CONTEXT = "search"
internal const val FOR_YOU_FEED_FAB_CONTEXT = "for-you"

internal fun creatorFeedFabContext(source: String, profileId: String): String =
    "creator:$source:$profileId"

internal fun codexFeedFabContext(codexId: String): String = "codex:$codexId"
