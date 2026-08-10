package com.theoriacodex.app.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Navigation-scoped demand adapter. It never copies duration values into route post lists. */
internal class MediaDurationRouteViewModel(
    private val coordinator: MediaDurationCoordinator,
    routeName: String,
    coroutineScope: CoroutineScope? = null,
) : ViewModel() {
    private val ownerScope = coroutineScope ?: viewModelScope
    private val routeIdentity = routeName.trim().also {
        require(it.isNotBlank()) { "Duration route name must not be blank" }
    }
    private val environmentIdentity = "$routeIdentity:environment"
    private val demandLock = Mutex()
    private var requestedContentIdentity: String? = null
    private var requestedPostsById: Map<PostId, Post> = emptyMap()
    private var requestedBackgroundEnabled = false
    private var contentIdentity: String? = null
    private var postsById: Map<PostId, Post> = emptyMap()
    private var observedKeys: Set<MediaDurationKey> = emptySet()
    private var backgroundEnabled = false
    private var filterActive = false
    private var visiblePostIds: Set<PostId> = emptySet()
    private var backgroundDemandKeys: Set<MediaDurationKey> = emptySet()
    private var filterDemandKeys: Set<MediaDurationKey> = emptySet()
    private var visibleDemandKeys: Set<MediaDurationKey> = emptySet()

    private val mutableStates = MutableStateFlow<Map<MediaDurationKey, MediaDurationState>>(emptyMap())
    val states: StateFlow<Map<MediaDurationKey, MediaDurationState>> = mutableStates.asStateFlow()

    init {
        ownerScope.launch {
            coordinator.states.collect { allStates ->
                demandLock.withLock { publishObservedStates(allStates) }
            }
        }
    }

    fun synchronize(
        identity: String,
        posts: List<Post>,
        resolveInBackground: Boolean,
    ) {
        require(identity.isNotBlank()) { "Duration content identity must not be blank" }
        val distinctPosts = posts.distinctBy(Post::id).associateBy(Post::id)
        if (
            requestedContentIdentity == identity &&
            requestedPostsById == distinctPosts &&
            requestedBackgroundEnabled == resolveInBackground
        ) {
            return
        }
        requestedContentIdentity = identity
        requestedPostsById = distinctPosts
        requestedBackgroundEnabled = resolveInBackground
        ownerScope.launch {
            demandLock.withLock {
                if (
                    contentIdentity == identity &&
                    postsById == distinctPosts &&
                    backgroundEnabled == resolveInBackground
                ) {
                    return@withLock
                }
                val identityChanged = contentIdentity != identity
                if (identityChanged) releaseContentDemand()
                contentIdentity = identity
                postsById = distinctPosts
                observedKeys = distinctPosts.values
                    .asSequence()
                    .filter(::isAnimatedPost)
                    .map(::mediaDurationKey)
                    .toSet()
                publishObservedStates(coordinator.states.value)
                if (identityChanged) {
                    visiblePostIds = visiblePostIds.intersect(distinctPosts.keys)
                }
                backgroundEnabled = resolveInBackground
                synchronizeDemand()
            }
        }
    }

    fun onFilterChanged(active: Boolean) {
        if (filterActive == active) return
        filterActive = active
        ownerScope.launch { demandLock.withLock { synchronizeDemand() } }
    }

    fun onPostVisibilityChanged(post: Post, visible: Boolean) {
        visiblePostIds = if (visible) visiblePostIds + post.id else visiblePostIds - post.id
        ownerScope.launch { demandLock.withLock { synchronizeDemand() } }
    }

    fun onEnvironmentChanged(lifecycleStarted: Boolean, scrollIdle: Boolean) {
        ownerScope.launch {
            coordinator.updateEnvironment(environmentIdentity, lifecycleStarted, scrollIdle)
        }
    }

    fun publishPlayerDuration(post: Post, durationMs: Long) {
        if (durationMs <= 0L) return
        ownerScope.launch {
            coordinator.publishKnown(
                key = mediaDurationKey(post),
                durationMs = durationMs,
                provenance = MediaDurationProvenance.ACTIVE_PLAYER,
            )
        }
    }

    override fun onCleared() {
        val identities = ownedDemandIdentities()
        coordinator.releaseLater(identities, environmentIdentity)
    }

    private suspend fun synchronizeDemand() {
        if (contentIdentity == null) return
        val candidates = buildMap {
            postsById.values.forEach { post ->
                animatedDurationMs(post)?.let { durationMs ->
                    coordinator.publishKnown(
                        mediaDurationKey(post),
                        durationMs,
                        MediaDurationProvenance.PROVIDER,
                    )
                    return@forEach
                }
                if (!isAnimatedPost(post)) return@forEach
                put(mediaDurationKey(post), post)
            }
        }

        backgroundDemandKeys = reconcileDemandLane(
            current = backgroundDemandKeys,
            desired = if (backgroundEnabled) candidates.keys else emptySet(),
            candidates = candidates,
            lane = DemandLane.BACKGROUND,
            priority = DurationDemandPriority.BACKGROUND_IDLE,
            reason = DurationDemandReason.APPEND,
        )
        filterDemandKeys = reconcileDemandLane(
            current = filterDemandKeys,
            desired = if (filterActive) candidates.keys else emptySet(),
            candidates = candidates,
            lane = DemandLane.FILTER,
            priority = DurationDemandPriority.ACTIVE_FILTER,
            reason = DurationDemandReason.FILTER,
        )
        visibleDemandKeys = reconcileDemandLane(
            current = visibleDemandKeys,
            desired = if (backgroundEnabled || filterActive) {
                candidates.filterValues { post -> post.id in visiblePostIds }.keys
            } else {
                emptySet()
            },
            candidates = candidates,
            lane = DemandLane.VISIBLE,
            priority = DurationDemandPriority.VISIBLE,
            reason = DurationDemandReason.VIEWPORT,
        )
    }

    private suspend fun reconcileDemandLane(
        current: Set<MediaDurationKey>,
        desired: Set<MediaDurationKey>,
        candidates: Map<MediaDurationKey, Post>,
        lane: DemandLane,
        priority: DurationDemandPriority,
        reason: DurationDemandReason,
    ): Set<MediaDurationKey> {
        val removedIdentities = (current - desired)
            .mapTo(mutableSetOf()) { key -> demandIdentity(lane, key) }
        if (removedIdentities.isNotEmpty()) coordinator.releaseIdentities(removedIdentities)
        (desired - current).forEach { key ->
            val post = candidates[key] ?: return@forEach
            submit(post, demandIdentity(lane, key), priority, reason)
        }
        return desired
    }

    private suspend fun submit(
        post: Post,
        identity: String,
        priority: DurationDemandPriority,
        reason: DurationDemandReason,
    ) {
        coordinator.submit(
            post = post,
            demand = DurationDemand(
                identity = identity,
                key = mediaDurationKey(post),
                priority = priority,
                reason = reason,
            ),
        )
    }

    private suspend fun releaseContentDemand() {
        val identities = ownedDemandIdentities()
        if (identities.isNotEmpty()) coordinator.releaseIdentities(identities)
        backgroundDemandKeys = emptySet()
        filterDemandKeys = emptySet()
        visibleDemandKeys = emptySet()
        postsById = emptyMap()
    }

    private fun ownedDemandIdentities(): Set<String> {
        return buildSet {
            backgroundDemandKeys.forEach { key -> add(demandIdentity(DemandLane.BACKGROUND, key)) }
            filterDemandKeys.forEach { key -> add(demandIdentity(DemandLane.FILTER, key)) }
            visibleDemandKeys.forEach { key -> add(demandIdentity(DemandLane.VISIBLE, key)) }
            observedKeys.forEach { key ->
                DemandLane.entries.forEach { lane -> add(demandIdentity(lane, key)) }
            }
        }
    }

    private fun demandIdentity(lane: DemandLane, key: MediaDurationKey): String {
        return "$routeIdentity:${contentIdentity.orEmpty()}:${lane.name}:" +
            "${key.postId.source.name}:${key.postId.sourcePostId}:${key.mediaFingerprint}"
    }

    private fun publishObservedStates(allStates: Map<MediaDurationKey, MediaDurationState>) {
        val observed = buildMap {
            observedKeys.forEach { key -> allStates[key]?.let { state -> put(key, state) } }
        }
        if (mutableStates.value != observed) mutableStates.value = observed
    }

    private enum class DemandLane {
        BACKGROUND,
        FILTER,
        VISIBLE,
    }

    companion object {
        fun factory(
            coordinator: MediaDurationCoordinator,
            routeName: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MediaDurationRouteViewModel(coordinator, routeName) as T
            }
        }
    }
}
