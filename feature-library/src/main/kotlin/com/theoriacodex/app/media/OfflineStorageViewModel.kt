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
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.app.ui.undo.UndoOffer
import com.theoriacodex.app.ui.undo.UndoSlot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One offline owner's row: a Codex kept offline, saved posts, or a removed Codex. [message] is about this row. */
data class OfflineStorageOwner(
    val id: String,
    val name: String,
    val availablePosts: Int,
    val job: OfflineMediaJob? = null,
    val preparing: Boolean = false,
    val message: String? = null,
)

private sealed interface OfflineRemovalTarget {
    data class Owner(val id: String) : OfflineRemovalTarget
    data object All : OfflineRemovalTarget
}

/**
 * Offline copies and cache. [isOpen] is the Storage sheet (Settings); [owners] are the offline rows the
 * Library's sheet lists, without rows hidden while their removal can still be undone ([hiddenOwners]).
 * [message] is about something that has no row, such as a Codex that could not be loaded.
 */
data class OfflineStorageUiState(
    val isOpen: Boolean = false,
    val offline: OfflineMediaSnapshot = OfflineMediaSnapshot(),
    val owners: List<OfflineStorageOwner> = emptyList(),
    val codexStates: Map<String, CodexOfflineState> = emptyMap(),
    val disposable: DisposableMediaCacheSnapshot? = null,
    val refreshing: Boolean = false,
    val working: Boolean = false,
    val message: String? = null,
    val preparingOwners: Set<String> = emptySet(),
    val hiddenOwners: Set<String> = emptySet(),
    val ownerMessages: Map<String, String> = emptyMap(),
) {
    val controlsEnabled: Boolean get() = !working && !refreshing
}

/** Where the Library's offline sheet draws its offers; rows use their owner id as the key. */
const val LIBRARY_TRANSFERS_UNDO_SCOPE = "library-transfers"
internal val OfflineAllUndoAnchor = UndoAnchor(LIBRARY_TRANSFERS_UNDO_SCOPE, "offline")
internal fun offlineOwnerUndoAnchor(owner: String) = UndoAnchor(LIBRARY_TRANSFERS_UNDO_SCOPE, owner)

/** Activity-owned presentation and collection preparation; the coordinator owns acquisition and bytes. */
class OfflineStorageViewModel(
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
        controls.update { it.copy(message = "Codex names could not be loaded.") }
        emit(emptyList())
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val postCounts = combine(offline.store.snapshot, offline.jobs) { snapshot, jobs ->
        (snapshot.postsByOwner.keys + jobs.keys).mapNotNullTo(mutableSetOf(), ::codexIdOfOfflineOwner)
    }.distinctUntilChanged().flatMapLatest { ids ->
        codices.observeCodexSummaries(ids, coverLimit = 0).map { summaries -> summaries.associate { it.codexId to it.itemCount } }
    }.flowOn(dispatcher).catch { emit(emptyMap()) }
    val state = combine(controls, offline.store.snapshot, offline.jobs, names, postCounts) { ui, snapshot, jobs, collections, counts ->
        val owners = offlineStorageOwners(snapshot, jobs, collections, ui)
        ui.copy(offline = snapshot, owners = owners, codexStates = codexOfflineStates(owners, counts))
    }.stateIn(scope, SharingStarted.Eagerly, OfflineStorageUiState())

    fun open() {
        controls.update { it.copy(isOpen = true, message = null) }
        refreshUsage()
    }

    fun refreshUsage() {
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

    fun dismiss() { controls.update { it.copy(isOpen = false) } }

    /** Keeps [codexId] offline, or tops up its copy: posts already retained are skipped, not downloaded again. */
    fun makeAvailableOffline(codexId: String) {
        val owner = codexOfflineOwner(codexId)
        if (controls.value.working || owner in controls.value.preparingOwners || offline.jobs.value[owner]?.isRunning == true) return
        controls.update { it.copy(preparingOwners = it.preparingOwners + owner, message = null, ownerMessages = it.ownerMessages - owner) }
        val preparation = scope.launch(start = CoroutineStart.LAZY) {
            val result = runCatchingPreservingCancellation {
                withContext(dispatcher) { codices.observeCodexPosts(codexId, CodexSortMode.NEWEST_SAVED).first() }
            }
            val posts = result.getOrNull()
            when {
                posts == null -> controls.update { it.copy(message = "The Codex could not be loaded. Try again.") }
                posts.isEmpty() -> controls.update { it.copy(message = "This Codex has no posts to keep offline.") }
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
        controls.update { it.copy(message = null, ownerMessages = it.ownerMessages - owner) }
        offline.retry(owner)
    }

    fun cancel(owner: String) {
        synchronized(preparationLock) { preparations[owner] }?.cancel()
        offline.cancel(owner)
    }

    /**
     * Removes [owner]'s offline copies as deferred destruction: the row hides at once with Undo in its place,
     * and the files go only when the offer expires (copies another owner retains stay).
     */
    fun remove(owner: String, undo: UndoSlot) {
        val row = state.value.owners.firstOrNull { it.id == owner } ?: return
        removeDeferred(undo, setOf(owner), OfflineRemovalTarget.Owner(owner), offlineOwnerUndoAnchor(owner), "Removed ${row.name}")
    }

    /** Removes every offline copy as deferred destruction, like [remove]. */
    fun removeAll(undo: UndoSlot) {
        val owners = state.value.owners.mapTo(mutableSetOf()) { it.id }
        if (owners.isEmpty() && state.value.offline.bytes == 0L) return
        removeDeferred(undo, owners, OfflineRemovalTarget.All, OfflineAllUndoAnchor, "Removed all offline copies")
    }

    private fun removeDeferred(undo: UndoSlot, owners: Set<String>, target: OfflineRemovalTarget, anchor: UndoAnchor, label: String) {
        controls.update { it.copy(hiddenOwners = it.hiddenOwners + owners) }
        val reveal = { controls.update { it.copy(hiddenOwners = it.hiddenOwners - owners) } }
        undo.offer(scope, UndoOffer(label, anchor, onExpire = { commitRemoval(target); reveal() }) { reveal() })
    }

    private suspend fun commitRemoval(target: OfflineRemovalTarget) {
        controls.update { it.copy(working = true) }
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
            if (result.isFailure) controls.update { ui ->
                when (target) {
                    is OfflineRemovalTarget.Owner -> ui.copy(ownerMessages = ui.ownerMessages + (target.id to REMOVAL_FAILED))
                    OfflineRemovalTarget.All -> ui.copy(message = REMOVAL_FAILED)
                }
            }
        } finally {
            controls.update { it.copy(working = false) }
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
                        usage.isFailure -> "Cache cleared, but storage usage could not be refreshed."
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
    ui: OfflineStorageUiState,
): List<OfflineStorageOwner> {
    val names = codices.associate { codexOfflineOwner(it.codexId) to it.name }
    return (snapshot.postsByOwner.keys + jobs.keys + ui.preparingOwners - ui.hiddenOwners).map { owner ->
        OfflineStorageOwner(
            id = owner,
            name = if (owner == AUTOMATIC_OFFLINE_OWNER) "Saved posts" else names[owner] ?: "Removed Codex",
            availablePosts = snapshot.postsByOwner[owner] ?: 0,
            job = jobs[owner],
            preparing = owner in ui.preparingOwners,
            message = ui.ownerMessages[owner],
        )
    }.sortedWith(compareBy<OfflineStorageOwner> { it.name.lowercase() }.thenBy { it.id })
}

private const val REMOVAL_FAILED = "Offline copies could not be removed. Try again."
