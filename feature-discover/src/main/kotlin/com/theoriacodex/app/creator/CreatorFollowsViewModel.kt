package com.theoriacodex.app.creator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.app.ui.undo.UndoOffer
import com.theoriacodex.app.ui.undo.UndoSlot
import com.theoriacodex.data.repository.CreatorFollowsRepository
import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.data.repository.RemovedFollow
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.followKey
import com.theoriacodex.data.repository.newPostsCheckDue
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.coroutines.runCatchingExceptionsPreservingCancellation
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class CreatorFollowsViewModel(
    settings: SettingsRepository,
    private val registry: SourceAdapterRegistry,
) : ViewModel() {
    private val repository = CreatorFollowsRepository(settings)
    val follows = repository.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val mutableRefreshing = MutableStateFlow(false)
    val refreshing = mutableRefreshing.asStateFlow()
    private val mutableErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors = mutableErrors.asStateFlow()
    private val mutableUnfollowed = MutableStateFlow<RemovedFollow?>(null)

    val unfollowed = mutableUnfollowed.asStateFlow()
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private var refreshJob: Job? = null
    private var refreshIsAutomatic = false

    fun toggle(creator: CreatorProfile, posts: List<Post>, undo: UndoSlot) {
        viewModelScope.launch {
            runCatchingExceptionsPreservingCancellation {
                if (repository.observe().first().any { it.creator.followKey() == creator.followKey() }) {
                    unfollowWithUndo(creator, undo, CreatorFollowAnchors.page(creator))
                } else repository.follow(creator, posts)
            }.onFailure { error -> messageChannel.send(error.message ?: "Could not update followed creator") }
        }
    }

    fun unfollow(creator: CreatorProfile, undo: UndoSlot) {
        viewModelScope.launch {
            runCatchingExceptionsPreservingCancellation {
                unfollowWithUndo(creator, undo, CreatorFollowAnchors.sheetRow(creator))
            }.onFailure { messageChannel.send("Could not unfollow creator") }
        }
    }

    private suspend fun unfollowWithUndo(creator: CreatorProfile, undo: UndoSlot, anchor: UndoAnchor) {
        val removed = repository.unfollow(creator) ?: return
        mutableUnfollowed.value = removed
        val offer = UndoOffer("Unfollowed ${creator.displayName}", anchor, onExpire = { mutableUnfollowed.compareAndSet(removed, null) }) {
            try { repository.restore(removed) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { messageChannel.send("Could not follow creator again") }
            finally { mutableUnfollowed.compareAndSet(removed, null) }
        }
        undo.offer(viewModelScope, offer)
    }

    fun cancelAutomaticRefresh() { if (refreshIsAutomatic) refreshJob?.cancel() }

    suspend fun refreshIfStale(nowEpochMs: Long) {
        if (repository.observe().first().none { it.checkDue(nowEpochMs) }) return
        startRefresh(automatic = true, at = nowEpochMs)
    }

    fun refresh() = startRefresh(automatic = false, at = System.currentTimeMillis())

    private fun startRefresh(automatic: Boolean, at: Long) {
        val running = refreshJob?.takeIf { it.isActive }
        if (running != null && (automatic || !refreshIsAutomatic)) return
        refreshIsAutomatic = automatic
        refreshJob = viewModelScope.launch {
            running?.cancelAndJoin()
            mutableRefreshing.value = true
            mutableErrors.value = emptyMap()
            try {
                val due = { follow: FollowedCreator -> !automatic || follow.checkDue(at) }
                repository.observe().first().filter(due).chunked(WRITE_BATCH_SIZE).forEach { batch ->
                    val results = Collections.synchronizedMap(LinkedHashMap<String, List<Post>?>())
                    try {
                        batch.mapConcurrent(concurrency = 2) { listed ->
                            repository.claimCheck(listed.membershipId, at, due)?.let { follow -> results[follow.membershipId] = checkCreator(follow) }
                        }
                    } finally {
                        withContext(NonCancellable) { repository.recordChecks(results.toMap(), at) }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { messageChannel.send("Could not load followed creators") }
            finally { mutableRefreshing.value = false }
        }
    }

    private suspend fun checkCreator(follow: FollowedCreator): List<Post>? = runCatchingExceptionsPreservingCancellation {
        val adapter = registry.adapterFor(follow.creator.source) as? CreatorPostsSourceAdapter
            ?: error("Source unavailable; check source accounts")
        withTimeoutOrNull(15_000L) { adapter.searchCreatorPosts(follow.creator, null) }?.items
            ?: error("Source timed out; check again")
    }.getOrElse { error ->
        mutableErrors.update { it + (follow.creator.followKey() to (error.message ?: "Could not check creator")) }
        null
    }

    private fun FollowedCreator.checkDue(nowEpochMs: Long) = newPostsCheckDue(listOf(checkedAtEpochMs, attemptedAtEpochMs), nowEpochMs)

    companion object {
        private const val WRITE_BATCH_SIZE = 25

        fun factory(settings: SettingsRepository, registry: SourceAdapterRegistry) = viewModelFactory {
            initializer { CreatorFollowsViewModel(settings, registry) }
        }
    }
}
