package com.theoriacodex.app.creator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.data.repository.CreatorFollowsRepository
import com.theoriacodex.data.repository.FollowedCreator
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
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private var refreshJob: Job? = null
    private var refreshIsAutomatic = false

    fun toggle(creator: CreatorProfile, posts: List<Post>) {
        viewModelScope.launch {
            try {
                if (repository.observe().first().any { it.creator.followKey() == creator.followKey() }) {
                    repository.unfollow(creator)
                } else repository.follow(creator, posts)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { messageChannel.send(error.message ?: "Could not update followed creator") }
        }
    }

    fun unfollow(creator: CreatorProfile) {
        viewModelScope.launch {
            try { repository.unfollow(creator) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { messageChannel.send("Could not unfollow creator") }
        }
    }

    /** Stops the app-open check when the app leaves the foreground; a refresh the user started carries on. */
    fun cancelAutomaticRefresh() { if (refreshIsAutomatic) refreshJob?.cancel() }

    /**
     * The app-open check: refreshes only when the newest check or failed attempt, which are saved with
     * the follows, is older than the check interval, so a failing source waits for the next interval
     * instead of retrying, restarts included.
     */
    suspend fun refreshIfStale(nowEpochMs: Long) {
        val follows = repository.observe().first()
        if (follows.isEmpty() || !newPostsCheckDue(follows.flatMap { listOf(it.checkedAtEpochMs, it.attemptedAtEpochMs) }, nowEpochMs)) return
        startRefresh(automatic = true, at = nowEpochMs)
    }

    fun refresh() = startRefresh(automatic = false, at = System.currentTimeMillis())

    private fun startRefresh(automatic: Boolean, at: Long) {
        if (refreshJob?.isActive == true) {
            // A refresh the user asks for while the automatic one runs is theirs from then on.
            if (!automatic) refreshIsAutomatic = false
            return
        }
        refreshIsAutomatic = automatic
        refreshJob = viewModelScope.launch {
            mutableRefreshing.value = true
            mutableErrors.value = emptyMap()
            try {
                repository.observe().first().chunked(WRITE_BATCH_SIZE).forEach { batch ->
                    val results = batch.mapConcurrent(concurrency = 2) { follow -> follow.membershipId to checkCreator(follow) }
                    repository.recordChecks(results.toMap(), at)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { messageChannel.send("Could not load followed creators") }
            finally { mutableRefreshing.value = false }
        }
    }

    /** [follow]'s newest posts, or null after recording why the check failed. */
    private suspend fun checkCreator(follow: FollowedCreator): List<Post>? = try {
        val adapter = registry.adapterFor(follow.creator.source) as? CreatorPostsSourceAdapter
            ?: error("Source unavailable; check source accounts")
        withTimeoutOrNull(15_000L) { adapter.searchCreatorPosts(follow.creator, null) }?.items
            ?: error("Source timed out; check again")
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) {
        mutableErrors.update { it + (follow.creator.followKey() to (error.message ?: "Could not check creator")) }
        null
    }

    companion object {
        /** Creators checked per settings write, so a large list neither rewrites settings per creator nor loses its progress when cancelled. */
        private const val WRITE_BATCH_SIZE = 25

        fun factory(settings: SettingsRepository, registry: SourceAdapterRegistry) = viewModelFactory {
            initializer { CreatorFollowsViewModel(settings, registry) }
        }
    }
}
