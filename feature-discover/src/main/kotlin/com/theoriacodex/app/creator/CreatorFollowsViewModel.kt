package com.theoriacodex.app.creator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.app.ui.undo.UndoOffer
import com.theoriacodex.app.ui.undo.UndoSlot
import com.theoriacodex.data.repository.CreatorFollowsRepository
import com.theoriacodex.data.repository.RemovedFollow
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.followKey
import com.theoriacodex.data.repository.newPostsCheckDue
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    /** The follow whose Undo is live, so the followed-creators sheet keeps its row's place. */
    val unfollowed = mutableUnfollowed.asStateFlow()
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private var refreshJob: Job? = null
    private var lastAutomaticCheck: Long? = null

    /** The creator page's Follow button: follows, or unfollows with Undo drawn in the button's place. */
    fun toggle(creator: CreatorProfile, posts: List<Post>, undo: UndoSlot) {
        viewModelScope.launch {
            try {
                if (repository.observe().first().any { it.creator.followKey() == creator.followKey() }) {
                    unfollowWithUndo(creator, undo, CreatorFollowAnchors.page(creator))
                } else repository.follow(creator, posts)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { messageChannel.send(error.message ?: "Could not update followed creator") }
        }
    }

    /** The followed-creators sheet's unfollow, with Undo drawn in the row it vacated. */
    fun unfollow(creator: CreatorProfile, undo: UndoSlot) {
        viewModelScope.launch {
            try { unfollowWithUndo(creator, undo, CreatorFollowAnchors.sheetRow(creator)) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { messageChannel.send("Could not unfollow creator") }
        }
    }

    /** Unfollows at once; Undo restores the same record, so nothing the creator posted meanwhile counts as seen. */
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

    fun cancelRefresh() { refreshJob?.cancel() }

    /**
     * The app-open check: refreshes only when the newest check, and the last automatic attempt, are
     * older than the check interval, so a failing source waits for the next interval instead of retrying.
     */
    suspend fun refreshIfStale(nowEpochMs: Long) {
        val follows = repository.observe().first()
        if (follows.isEmpty() || !newPostsCheckDue(follows.map { it.checkedAtEpochMs } + lastAutomaticCheck, nowEpochMs)) return
        lastAutomaticCheck = nowEpochMs
        refresh()
    }

    /** The Followed feed is open, so its creators' latest posts are no longer new. */
    fun markLatestSeen() {
        viewModelScope.launch {
            try { repository.markLatestSeen() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { messageChannel.send("Could not update followed creators") }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            mutableRefreshing.value = true
            mutableErrors.value = emptyMap()
            try {
                repository.observe().first().mapConcurrent(concurrency = 2) { follow ->
                    try {
                        val adapter = registry.adapterFor(follow.creator.source) as? CreatorPostsSourceAdapter
                            ?: error("Source unavailable; check source accounts")
                        val page = withTimeoutOrNull(15_000L) { adapter.searchCreatorPosts(follow.creator, null) }
                            ?: error("Source timed out; check again")
                        repository.recordCheck(follow, page.items, System.currentTimeMillis())
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        mutableErrors.update { it + (follow.creator.followKey() to (error.message ?: "Could not check creator")) }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { messageChannel.send("Could not load followed creators") }
            finally { mutableRefreshing.value = false }
        }
    }

    companion object {
        fun factory(settings: SettingsRepository, registry: SourceAdapterRegistry) = viewModelFactory {
            initializer { CreatorFollowsViewModel(settings, registry) }
        }
    }
}
