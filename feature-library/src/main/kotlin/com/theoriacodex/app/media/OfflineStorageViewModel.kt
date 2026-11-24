package com.theoriacodex.app.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.OfflineMediaSnapshot
import com.theoriacodex.data.repository.SeriesDownloads
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.seriesIdOfOfflineOwner
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
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

data class OfflineStorageOwner(
    val id: String,
    val name: String,
    val availablePosts: Int,
    val job: OfflineMediaJob? = null,
    val preparing: Boolean = false,
)

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

    val codexRemovals: Map<String, PendingRemoval>
        get() = pendingRemovals.mapNotNull { (owner, removal) -> codexIdOfOfflineOwner(owner)?.let { it to removal } }.toMap()
}

const val LIBRARY_TRANSFERS_UNDO_SCOPE = "library-transfers"

const val OFFLINE_ALL_REMOVAL = "*"

class OfflineStorageViewModel(
    private val codices: CodexRepository,
    private val offline: OfflineMediaCoordinator,
    private val readDisposableUsage: suspend () -> DisposableMediaCacheSnapshot,
    private val clearDisposable: suspend () -> Boolean,
    removalScope: CoroutineScope,
    private val seriesRepository: SeriesRepository? = null,
    private val seriesDownloads: SeriesDownloads? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val removals = DeferredRemovals<String>(removalScope, ::commitRemoval)
    private val controls = MutableStateFlow(OfflineStorageUiState())
    private val failures = Channel<String>(Channel.BUFFERED)

    val preparationFailures: Flow<String> = failures.receiveAsFlow()
    private val codexNames = codices.observeCodices().map { list -> list.associate { codexOfflineOwner(it.codexId) to it.name } }
        .flowOn(dispatcher).catch {
            controls.update { it.copy(message = "Codex names could not be loaded.") }
            emit(emptyMap())
        }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val seriesNames = offline.store.snapshot.map { snapshot ->
        snapshot.postsByOwner.keys.mapNotNull { owner -> seriesIdOfOfflineOwner(owner)?.let { owner to it } }
    }.distinctUntilChanged().flatMapLatest { owners ->
        val repository = seriesRepository
        if (owners.isEmpty() || repository == null) return@flatMapLatest flowOf(emptyMap())
        combine(owners.map { (owner, id) -> repository.observeSeries(id).map { owner to it?.series?.title } }) { titles ->
            titles.mapNotNull { (owner, title) -> title?.let { owner to it } }.toMap()
        }
    }.flowOn(dispatcher).catch { emit(emptyMap()) }
    private val names = combine(codexNames, seriesNames) { codex, series -> codex + series }
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
    val state = combine(presented, offline.store.snapshot, offline.jobs, names, members) { ui, snapshot, jobs, ownerNames, posts ->
        val owners = offlineStorageOwners(snapshot, jobs, ownerNames, ui)
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

    fun removeDeferred(owner: String, lifetime: UndoLifetime = UndoLifetime.Timed()) {
        val row = state.value.owners.firstOrNull { it.id == owner } ?: return
        removals.remove(owner, "Removed ${row.name}", lifetime)
    }

    fun removeAll() {
        if (state.value.owners.isEmpty() && state.value.offline.bytes == 0L) return
        removals.pending.value.keys.forEach(removals::undo)
        removals.remove(OFFLINE_ALL_REMOVAL, "Removed all offline copies")
    }

    fun actOnRemoval(key: String) = removals.act(key)

    fun commitRemovals() = removals.commitAll()

    override fun onCleared() = removals.commitAll()

    private suspend fun commitRemoval(key: String): Boolean {
        controls.update { it.copy(working = true) }
        try {
            return runCatchingPreservingCancellation {
                val series = seriesIdOfOfflineOwner(key)
                when {
                    key == OFFLINE_ALL_REMOVAL -> {
                        seriesDownloads?.cancelAll()
                        offline.clear()
                    }
                    series != null && seriesDownloads != null -> seriesDownloads.deleteSeries(series)
                    else -> offline.remove(key)
                }
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
            seriesRepository: SeriesRepository?,
            seriesDownloads: SeriesDownloads?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                OfflineStorageViewModel(codices, offline, cache::snapshot, cache::clear, removalScope, seriesRepository, seriesDownloads)
            }
        }
    }
}

private fun offlineStorageOwners(
    snapshot: OfflineMediaSnapshot,
    jobs: Map<String, OfflineMediaJob>,
    names: Map<String, String>,
    ui: OfflineStorageUiState,
): List<OfflineStorageOwner> {
    if (OFFLINE_ALL_REMOVAL in ui.hiddenOwners) return emptyList()
    return (snapshot.postsByOwner.keys + jobs.keys + ui.preparingOwners - ui.hiddenOwners).map { owner ->
        val removed = if (seriesIdOfOfflineOwner(owner) != null) "Removed series" else "Removed Codex"
        OfflineStorageOwner(
            id = owner,
            name = if (owner == AUTOMATIC_OFFLINE_OWNER) "Saved posts" else names[owner] ?: removed,
            availablePosts = snapshot.postsByOwner[owner]?.size ?: 0,
            job = jobs[owner],
            preparing = owner in ui.preparingOwners,
        )
    }.sortedWith(compareBy<OfflineStorageOwner> { it.name.lowercase() }.thenBy { it.id })
}

