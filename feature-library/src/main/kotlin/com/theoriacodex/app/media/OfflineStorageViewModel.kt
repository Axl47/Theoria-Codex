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
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.app.ui.undo.DeferredRemovals
import com.theoriacodex.app.ui.undo.PendingRemoval
import com.theoriacodex.app.ui.undo.UndoLifetime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One offline owner's row: a Codex kept offline, saved posts, or a removed Codex. */
data class OfflineStorageOwner(
    val id: String,
    val name: String,
    val availablePosts: Int,
    val job: OfflineMediaJob? = null,
    val preparing: Boolean = false,
)

/**
 * Offline copies and cache. [isOpen] is the Storage sheet (Settings); [owners] are the offline rows the
 * Library's sheet lists, without rows hidden while a removal holds them ([hiddenOwners]). [pendingRemovals] are
 * those removals by owner id, or [OFFLINE_ALL_REMOVAL] for Remove all, each drawn as its Undo row.
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
    val pendingRemovals: Map<String, PendingRemoval> = emptyMap(),
) {
    val controlsEnabled: Boolean get() = !working && !refreshing

    /** The [pendingRemovals] of Codex copies, by Codex id, for their Undo on Codex detail and in the Library. */
    val codexRemovals: Map<String, PendingRemoval>
        get() = pendingRemovals.mapNotNull { (owner, removal) -> codexIdOfOfflineOwner(owner)?.let { it to removal } }.toMap()
}

/** Where the Library's offline sheet draws its offers; rows use their owner id as the key. */
const val LIBRARY_TRANSFERS_UNDO_SCOPE = "library-transfers"

/** The [OfflineStorageUiState.pendingRemovals] key of Remove all, which hides every row. */
const val OFFLINE_ALL_REMOVAL = "*"

/**
 * Activity-owned presentation and collection preparation; the coordinator owns acquisition and bytes. Removals
 * commit in [removalScope], which must be application-lived: clearing this owner commits what is still pending.
 */
class OfflineStorageViewModel(
    private val codices: CodexRepository,
    private val offline: OfflineMediaCoordinator,
    private val readDisposableUsage: suspend () -> DisposableMediaCacheSnapshot,
    private val clearDisposable: suspend () -> Boolean,
    removalScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val removals = DeferredRemovals<String>(removalScope, ::commitRemoval)
    private val controls = MutableStateFlow(OfflineStorageUiState())
    private val failures = Channel<String>(Channel.BUFFERED)

    /** Why "Make available offline" could not start, once per attempt; the app shows it when the sheet is closed. */
    val preparationFailures: Flow<String> = failures.receiveAsFlow()
    private val names = codices.observeCodices().flowOn(dispatcher).catch {
        controls.update { it.copy(message = "Codex names could not be loaded.") }
        emit(emptyList())
    }
    /** The current posts of every Codex kept offline, by Codex id, for the ring's share of settled posts. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val members = combine(offline.store.snapshot, offline.jobs) { snapshot, jobs ->
        (snapshot.postsByOwner.keys + jobs.keys).mapNotNullTo(mutableSetOf(), ::codexIdOfOfflineOwner)
    }.distinctUntilChanged().flatMapLatest { ids ->
        if (ids.isEmpty()) return@flatMapLatest flowOf(emptyMap())
        combine(ids.map { id -> codices.observeCodexItems(id).map { items -> id to items.mapTo(mutableSetOf(), CodexItem::postId) } }) {
            it.toMap()
        }
    }.flowOn(dispatcher).catch { emit(emptyMap()) }
    private val presented = combine(controls, removals.pending) { ui, pending ->
        ui.copy(pendingRemovals = pending, hiddenOwners = pending.keys)
    }
    val state = combine(presented, offline.store.snapshot, offline.jobs, names, members) { ui, snapshot, jobs, collections, posts ->
        val owners = offlineStorageOwners(snapshot, jobs, collections, ui)
        ui.copy(offline = snapshot, owners = owners, codexStates = codexOfflineStates(owners, posts, snapshot.availablePostIds))
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

    /**
     * Keeps [codexId] offline, or tops up its copy: posts already retained are skipped, not downloaded again.
     * When it cannot start, the reason goes to [message] and to [preparationFailures]. The coordinator owns the
     * loading, so removing the copy (or deleting the Codex) stops it before clearing.
     */
    fun makeAvailableOffline(codexId: String) {
        val owner = codexOfflineOwner(codexId)
        if (controls.value.working || owner in controls.value.preparingOwners || offline.jobs.value[owner]?.isRunning == true) return
        controls.update { it.copy(preparingOwners = it.preparingOwners + owner, message = null) }
        val preparation = offline.prepare(owner) { start ->
            val result = runCatchingPreservingCancellation {
                withContext(dispatcher) { codices.observeCodexPosts(codexId, CodexSortMode.NEWEST_SAVED).first() }
            }
            val posts = result.getOrNull()
            when {
                posts == null -> preparationFailed("The Codex could not be loaded. Try again.")
                posts.isEmpty() -> preparationFailed("This Codex has no posts to keep offline.")
                else -> start(posts)
            }
        }
        preparation.invokeOnCompletion { controls.update { it.copy(preparingOwners = it.preparingOwners - owner) } }
    }

    private fun preparationFailed(reason: String) {
        controls.update { it.copy(message = reason) }
        failures.trySend(reason)
    }

    fun retry(owner: String) {
        if (!controls.value.controlsEnabled) return
        controls.update { it.copy(message = null) }
        offline.retry(owner)
    }

    fun cancel(owner: String) = offline.cancel(owner)

    /**
     * Removes [owner]'s offline copies as deferred destruction: the row hides at once with Undo in its place, and
     * the files go only when [lifetime] ends (copies another owner retains stay). Timed by default; until the
     * surface is left for a surface that calls [commitRemovals] when left.
     */
    fun removeDeferred(owner: String, lifetime: UndoLifetime = UndoLifetime.Timed()) {
        val row = state.value.owners.firstOrNull { it.id == owner } ?: return
        removals.remove(owner, "Removed ${row.name}", lifetime)
    }

    /**
     * Removes every offline copy as deferred destruction, like [removeDeferred]. It takes over the single removals
     * still waiting, so its one Undo puts every copy back and no single Undo is left to outlive the copies it names.
     */
    fun removeAll() {
        if (state.value.owners.isEmpty() && state.value.offline.bytes == 0L) return
        removals.pending.value.keys.forEach(removals::undo)
        removals.remove(OFFLINE_ALL_REMOVAL, "Removed all offline copies")
    }

    /** Undo, or Retry after a failure, for the pending removal under [key] (see [OfflineStorageUiState.pendingRemovals]). */
    fun actOnRemoval(key: String) = removals.act(key)

    /** Commits every pending removal now; called when the surface that shows them is left. */
    fun commitRemovals() = removals.commitAll()

    override fun onCleared() = removals.commitAll()

    /** Removes [key]'s copies (see [OFFLINE_ALL_REMOVAL]); the coordinator stops preparations first. False on failure. */
    private suspend fun commitRemoval(key: String): Boolean {
        controls.update { it.copy(working = true) }
        try {
            return runCatchingPreservingCancellation {
                if (key == OFFLINE_ALL_REMOVAL) offline.clear() else offline.remove(key)
            }.isSuccess
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
        fun factory(
            codices: CodexRepository,
            offline: OfflineMediaCoordinator,
            cache: MediaCacheMaintenance,
            removalScope: CoroutineScope,
        ): ViewModelProvider.Factory =
            viewModelFactory { initializer { OfflineStorageViewModel(codices, offline, cache::snapshot, cache::clear, removalScope) } }
    }
}

private fun offlineStorageOwners(
    snapshot: OfflineMediaSnapshot,
    jobs: Map<String, OfflineMediaJob>,
    codices: List<Codex>,
    ui: OfflineStorageUiState,
): List<OfflineStorageOwner> {
    val names = codices.associate { codexOfflineOwner(it.codexId) to it.name }
    if (OFFLINE_ALL_REMOVAL in ui.hiddenOwners) return emptyList()
    return (snapshot.postsByOwner.keys + jobs.keys + ui.preparingOwners - ui.hiddenOwners).map { owner ->
        OfflineStorageOwner(
            id = owner,
            name = if (owner == AUTOMATIC_OFFLINE_OWNER) "Saved posts" else names[owner] ?: "Removed Codex",
            availablePosts = snapshot.postsByOwner[owner] ?: 0,
            job = jobs[owner],
            preparing = owner in ui.preparingOwners,
        )
    }.sortedWith(compareBy<OfflineStorageOwner> { it.name.lowercase() }.thenBy { it.id })
}

