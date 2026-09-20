package com.theoriacodex.app.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.OfflineMediaSnapshot
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Codex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class OfflineStorageOwner(
    val id: String,
    val name: String,
    val availablePosts: Int,
    val job: OfflineMediaJob? = null,
    val preparing: Boolean = false,
)

internal sealed interface OfflineRemovalTarget {
    data class Owner(val id: String, val name: String) : OfflineRemovalTarget
    data object All : OfflineRemovalTarget
}

internal data class OfflineStorageUiState(
    val isOpen: Boolean = false,
    val offline: OfflineMediaSnapshot = OfflineMediaSnapshot(),
    val owners: List<OfflineStorageOwner> = emptyList(),
    val disposable: DisposableMediaCacheSnapshot? = null,
    val refreshing: Boolean = false,
    val working: Boolean = false,
    val pendingRemoval: OfflineRemovalTarget? = null,
    val message: String? = null,
    val preparingOwners: Set<String> = emptySet(),
) {
    val controlsEnabled: Boolean get() = !working && !refreshing
}

/** Activity-owned presentation and collection preparation; the coordinator owns acquisition and bytes. */
internal class OfflineStorageViewModel(
    private val codices: CodexRepository,
    private val offline: OfflineMediaCoordinator,
    private val readDisposableUsage: suspend () -> DisposableMediaCacheSnapshot,
    private val clearDisposable: suspend () -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val controls = MutableStateFlow(OfflineStorageUiState())
    private val preparationLock = Any()
    private val preparations = mutableMapOf<String, Job>()
    private val names = codices.observeCodices().flowOn(dispatcher).catch {
        controls.update { it.copy(message = "Collection names could not be loaded.") }
        emit(emptyList())
    }
    val state = combine(controls, offline.store.snapshot, offline.jobs, names) { ui, snapshot, jobs, collections ->
        ui.copy(offline = snapshot, owners = offlineStorageOwners(snapshot, jobs, collections, ui.preparingOwners))
    }.stateIn(scope, SharingStarted.Eagerly, OfflineStorageUiState())

    fun open() {
        controls.update { it.copy(isOpen = true, message = null) }
        if (!controls.value.controlsEnabled) return
        controls.update { it.copy(refreshing = true) }
        scope.launch {
            try {
                val usage = runCatchingPreservingCancellation {
                    withContext(dispatcher) { offline.store.refresh(); readDisposableUsage() }
                }
                controls.update { it.copy(
                    disposable = usage.getOrNull(),
                    message = if (usage.isFailure) "Storage usage could not be refreshed. Try again." else it.message,
                ) }
            } finally {
                controls.update { it.copy(refreshing = false) }
            }
        }
    }

    fun dismiss() { controls.update { it.copy(isOpen = false, pendingRemoval = null) } }

    fun makeAvailableOffline(codexId: String) {
        val owner = "codex:$codexId"
        controls.update { it.copy(isOpen = true) }
        if (controls.value.working || owner in controls.value.preparingOwners || offline.jobs.value[owner]?.isRunning == true) return
        controls.update { it.copy(preparingOwners = it.preparingOwners + owner, message = null) }
        val preparation = scope.launch(start = CoroutineStart.LAZY) {
            val result = runCatchingPreservingCancellation {
                withContext(dispatcher) { codices.observeCodexPosts(codexId, CodexSortMode.NEWEST_SAVED).first() }
            }
            val posts = result.getOrNull()
            when {
                posts == null -> controls.update { it.copy(message = "The collection could not be loaded. Try again.") }
                posts.isEmpty() -> controls.update { it.copy(message = "This collection has no posts to save offline.") }
                else -> offline.makeAvailableOffline(owner, posts)
            }
        }
        synchronized(preparationLock) { preparations[owner] = preparation }
        preparation.invokeOnCompletion {
            synchronized(preparationLock) { preparations.remove(owner) }
            controls.update { it.copy(preparingOwners = it.preparingOwners - owner) }
        }
        preparation.start()
    }

    fun retry(owner: String) {
        if (!controls.value.controlsEnabled) return
        controls.update { it.copy(message = null) }
        offline.retry(owner)
    }

    fun cancel(owner: String) {
        synchronized(preparationLock) { preparations[owner] }?.cancel()
        offline.cancel(owner)
        controls.update { it.copy(message = "Offline download cancelled. Completed copies remain available.") }
    }

    fun requestRemove(owner: String) {
        val row = state.value.owners.firstOrNull { it.id == owner } ?: return
        controls.update { it.copy(pendingRemoval = OfflineRemovalTarget.Owner(owner, row.name)) }
    }

    fun requestClearOffline() { controls.update { it.copy(pendingRemoval = OfflineRemovalTarget.All) } }
    fun dismissRemoval() { controls.update { it.copy(pendingRemoval = null) } }

    fun confirmRemoval() {
        if (!controls.value.controlsEnabled) return
        val target = controls.value.pendingRemoval ?: return
        controls.update { it.copy(working = true, pendingRemoval = null, message = null) }
        scope.launch {
            try {
                val result = runCatchingPreservingCancellation {
                    when (target) {
                        is OfflineRemovalTarget.Owner -> {
                            synchronized(preparationLock) { preparations[target.id] }?.cancelAndJoin()
                            offline.remove(target.id)
                        }
                        OfflineRemovalTarget.All -> {
                            val loading = synchronized(preparationLock) { preparations.values.toList() }
                            loading.forEach { it.cancel() }
                            loading.forEach { it.join() }
                            offline.clear()
                        }
                    }
                }
                controls.update { it.copy(message = if (result.isSuccess) "Offline copies removed." else
                    "Offline copies could not be removed. Try again.") }
            } finally {
                controls.update { it.copy(working = false) }
            }
        }
    }

    fun clearDisposableCache() {
        if (!controls.value.controlsEnabled) return
        controls.update { it.copy(working = true, message = null) }
        scope.launch {
            try {
                val cleared = runCatchingPreservingCancellation { withContext(dispatcher) { clearDisposable() } }
                val usage = runCatchingPreservingCancellation { withContext(dispatcher) { readDisposableUsage() } }
                controls.update { it.copy(
                    disposable = usage.getOrNull(),
                    message = when {
                        cleared.isFailure -> "Some cache could not be cleared. Try again."
                        usage.isFailure -> "Cache cleanup finished, but storage usage could not be refreshed. Reopen storage to try again."
                        cleared.getOrNull() == false -> "Cache cleared. Media currently in use was kept; clear again after playback ends."
                        else -> "Disposable cache cleared. Offline copies are still available."
                    },
                ) }
            } finally {
                controls.update { it.copy(working = false) }
            }
        }
    }

    companion object {
        fun factory(codices: CodexRepository, offline: OfflineMediaCoordinator, cache: MediaCacheMaintenance): ViewModelProvider.Factory =
            viewModelFactory { initializer { OfflineStorageViewModel(codices, offline, cache::snapshot, cache::clear) } }
    }
}

private fun offlineStorageOwners(
    snapshot: OfflineMediaSnapshot,
    jobs: Map<String, OfflineMediaJob>,
    codices: List<Codex>,
    preparing: Set<String>,
): List<OfflineStorageOwner> {
    val names = codices.associate { "codex:${it.codexId}" to it.name }
    return (snapshot.postsByOwner.keys + jobs.keys + preparing).map { owner ->
        OfflineStorageOwner(
            id = owner,
            name = if (owner == AUTOMATIC_OFFLINE_OWNER) "Saved posts" else names[owner] ?: "Removed collection",
            availablePosts = snapshot.postsByOwner[owner] ?: 0,
            job = jobs[owner],
            preparing = owner in preparing,
        )
    }.sortedWith(compareBy<OfflineStorageOwner> { it.name.lowercase() }.thenBy { it.id })
}
