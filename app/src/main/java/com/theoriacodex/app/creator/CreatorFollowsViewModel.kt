package com.theoriacodex.app.creator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.data.repository.CreatorFollowsRepository
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.followKey
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

internal class CreatorFollowsViewModel(
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

    fun cancelRefresh() { refreshJob?.cancel() }

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
