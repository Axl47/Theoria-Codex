package com.theoriacodex.app.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor

/** Navigation-scoped demand adapter. It never copies duration values into route post lists. */
internal class MediaDurationRouteViewModel(
    private val coordinator: MediaDurationCoordinator,
    routeName: String,
    coroutineScope: CoroutineScope? = null,
    private val keyFactory: (Post) -> MediaDurationKey = ::mediaDurationKey,
    snapshotDispatcher: CoroutineDispatcher? = null,
) : ViewModel() {
    private val ownerScope = coroutineScope ?: viewModelScope
    private val snapshotDispatcher = snapshotDispatcher
        ?: if (coroutineScope == null) {
            Dispatchers.Default
        } else {
            coroutineScope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
                ?: Dispatchers.Default
        }
    private val routeIdentity = routeName.trim().also {
        require(it.isNotBlank()) { "Duration route name must not be blank" }
    }
    private val environmentIdentity = "$routeIdentity:environment"
    private val demandLock = Mutex()
    private var requestedContentIdentity: String? = null
    private var requestedPostsReference: List<Post>? = null
    private var requestedSnapshot = MediaDurationPostSnapshot.EMPTY
    private var requestedSnapshotIdentity: String? = null
    private var requestGeneration = 0L
    private var requestedBackgroundEnabled = false
    private var contentIdentity: String? = null
    private var postsById: Map<PostId, Post> = emptyMap()
    private var keysByPostId: Map<PostId, MediaDurationKey> = emptyMap()
    private var candidatesByKey: Map<MediaDurationKey, Post> = emptyMap()
    private var knownDurationsByKey: Map<MediaDurationKey, Long> = emptyMap()
    private var observedKeys: Set<MediaDurationKey> = emptySet()
    private var backgroundEnabled = false
    private var filterActive = false
    private val visiblePostIds = mutableSetOf<PostId>()
    private var backgroundDemandKeys: Set<MediaDurationKey> = emptySet()
    private var filterDemandKeys: Set<MediaDurationKey> = emptySet()
    private var visibleDemandKeys: Set<MediaDurationKey> = emptySet()

    private val mutableStates = MutableStateFlow<Map<MediaDurationKey, MediaDurationState>>(emptyMap())
    val states: StateFlow<Map<MediaDurationKey, MediaDurationState>> = mutableStates.asStateFlow()

    init {
        ownerScope.launch {
            coordinator.states.collect { allStates ->
                if (filterActive) demandLock.withLock { publishObservedStates(allStates) }
            }
        }
    }

    fun synchronize(
        identity: String,
        posts: List<Post>,
        resolveInBackground: Boolean,
    ) {
        require(identity.isNotBlank()) { "Duration content identity must not be blank" }
        if (
            requestedContentIdentity == identity &&
            requestedPostsReference === posts &&
            requestedBackgroundEnabled == resolveInBackground
        ) {
            return
        }
        val request = DurationSnapshotRequest(
            generation = ++requestGeneration,
            identity = identity,
            posts = posts,
            resolveInBackground = resolveInBackground,
            previousIdentity = requestedContentIdentity,
            previousPosts = requestedPostsReference,
            previousSnapshot = requestedSnapshot.takeIf {
                requestedSnapshotIdentity == requestedContentIdentity
            },
        )
        requestedContentIdentity = identity
        requestedPostsReference = posts
        requestedBackgroundEnabled = resolveInBackground
        ownerScope.launch { synchronize(request) }
    }

    private suspend fun synchronize(request: DurationSnapshotRequest) {
        val snapshot = buildSnapshot(request)
        demandLock.withLock {
            if (!isCurrent(request)) return@withLock
            applySnapshot(request, snapshot)
        }
    }

    private suspend fun buildSnapshot(request: DurationSnapshotRequest): MediaDurationPostSnapshot {
        val unchangedSnapshot = request.previousSnapshot
            ?.takeIf { request.previousIdentity == request.identity && request.previousPosts === request.posts }
        return unchangedSnapshot ?: withContext(snapshotDispatcher) {
            val canExtend = request.previousIdentity == request.identity
            mediaDurationPostSnapshot(
                posts = request.posts,
                keyFactory = keyFactory,
                previousPosts = request.previousPosts.takeIf { canExtend },
                previousSnapshot = request.previousSnapshot.takeIf { canExtend },
            )
        }
    }

    private fun isCurrent(request: DurationSnapshotRequest): Boolean {
        return requestGeneration == request.generation &&
            requestedContentIdentity == request.identity &&
            requestedPostsReference === request.posts &&
            requestedBackgroundEnabled == request.resolveInBackground
    }

    private suspend fun applySnapshot(
        request: DurationSnapshotRequest,
        snapshot: MediaDurationPostSnapshot,
    ) {
        requestedSnapshot = snapshot
        requestedSnapshotIdentity = request.identity
        if (
            contentIdentity == request.identity &&
            postsById == snapshot.postsById &&
            backgroundEnabled == request.resolveInBackground
        ) {
            return
        }
        if (contentIdentity != request.identity) releaseContentDemand()
        val previouslyPublishedDurations = knownDurationsByKey
        contentIdentity = request.identity
        postsById = snapshot.postsById
        keysByPostId = snapshot.keysByPostId
        candidatesByKey = snapshot.candidatesByKey
        knownDurationsByKey = snapshot.knownDurationsByKey
        observedKeys = snapshot.observedKeys
        publishObservedStates(coordinator.states.value)
        visiblePostIds.retainAll(snapshot.postsById.keys)
        backgroundEnabled = request.resolveInBackground
        publishSnapshotDurations(previouslyPublishedDurations)
        synchronizeDemand()
    }

    fun onFilterChanged(active: Boolean) {
        if (filterActive == active) return
        filterActive = active
        ownerScope.launch {
            demandLock.withLock {
                if (active) publishObservedStates(coordinator.states.value) else mutableStates.value = emptyMap()
                synchronizeDemand()
            }
        }
    }

    fun observeState(post: Post): Flow<MediaDurationState?> {
        if (!isAnimatedPost(post)) return flowOf(null)
        val key = if (requestedSnapshotIdentity == requestedContentIdentity) {
            requestedSnapshot.keysByPostId[post.id]
        } else {
            null
        } ?: keyFactory(post)
        return coordinator.states
            .map { states -> states[key] }
            .distinctUntilChanged()
    }

    fun onPostVisibilityChanged(post: Post, visible: Boolean) {
        val changed = if (visible) visiblePostIds.add(post.id) else visiblePostIds.remove(post.id)
        if (!changed || (!requestedBackgroundEnabled && !filterActive)) return
        ownerScope.launch {
            demandLock.withLock { synchronizeVisibleDemand(post.id) }
        }
    }

    fun onEnvironmentChanged(lifecycleStarted: Boolean, scrollIdle: Boolean) {
        ownerScope.launch {
            coordinator.updateEnvironment(environmentIdentity, lifecycleStarted, scrollIdle)
        }
    }

    fun publishPlayerDuration(post: Post, durationMs: Long) {
        if (durationMs <= 0L) return
        val key = requestedSnapshot
            .takeIf { requestedSnapshotIdentity == requestedContentIdentity }
            ?.keysByPostId
            ?.get(post.id)
            ?: keyFactory(post)
        if (coordinator.states.value[key] is MediaDurationState.Known) return
        ownerScope.launch {
            coordinator.publishKnown(
                key = key,
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
        backgroundDemandKeys = reconcileDemandLane(
            current = backgroundDemandKeys,
            desired = if (backgroundEnabled) candidatesByKey.keys else emptySet(),
            lane = DemandLane.BACKGROUND,
            priority = DurationDemandPriority.BACKGROUND_IDLE,
            reason = DurationDemandReason.APPEND,
        )
        filterDemandKeys = reconcileDemandLane(
            current = filterDemandKeys,
            desired = if (filterActive) candidatesByKey.keys else emptySet(),
            lane = DemandLane.FILTER,
            priority = DurationDemandPriority.ACTIVE_FILTER,
            reason = DurationDemandReason.FILTER,
        )
        visibleDemandKeys = reconcileDemandLane(
            current = visibleDemandKeys,
            desired = if (backgroundEnabled || filterActive) {
                visiblePostIds.mapNotNullTo(mutableSetOf()) { postId ->
                    keysByPostId[postId]?.takeIf(candidatesByKey::containsKey)
                }
            } else {
                emptySet()
            },
            lane = DemandLane.VISIBLE,
            priority = DurationDemandPriority.VISIBLE,
            reason = DurationDemandReason.VIEWPORT,
        )
    }

    private suspend fun reconcileDemandLane(
        current: Set<MediaDurationKey>,
        desired: Set<MediaDurationKey>,
        lane: DemandLane,
        priority: DurationDemandPriority,
        reason: DurationDemandReason,
    ): Set<MediaDurationKey> {
        val removedIdentities = (current - desired)
            .mapTo(mutableSetOf()) { key -> demandIdentity(lane, key) }
        if (removedIdentities.isNotEmpty()) coordinator.releaseIdentities(removedIdentities)
        (desired - current).forEach { key ->
            val post = candidatesByKey[key] ?: return@forEach
            submit(post, key, demandIdentity(lane, key), priority, reason)
        }
        return desired
    }

    private suspend fun synchronizeVisibleDemand(postId: PostId) {
        val key = keysByPostId[postId] ?: return
        val desired = (backgroundEnabled || filterActive) &&
            postId in visiblePostIds &&
            key in candidatesByKey
        val current = key in visibleDemandKeys
        if (desired == current) return
        if (!desired) {
            coordinator.releaseIdentity(demandIdentity(DemandLane.VISIBLE, key))
            visibleDemandKeys = visibleDemandKeys - key
            return
        }
        val terminal = coordinator.states.value[key]
        if (terminal is MediaDurationState.Known || terminal is MediaDurationState.Unsupported) return
        val post = candidatesByKey[key] ?: return
        submit(
            post = post,
            key = key,
            identity = demandIdentity(DemandLane.VISIBLE, key),
            priority = DurationDemandPriority.VISIBLE,
            reason = DurationDemandReason.VIEWPORT,
        )
        visibleDemandKeys = visibleDemandKeys + key
    }

    private suspend fun publishSnapshotDurations(previouslyPublished: Map<MediaDurationKey, Long>) {
        knownDurationsByKey.forEach { (key, durationMs) ->
            if (previouslyPublished[key] == durationMs) return@forEach
            coordinator.publishKnown(
                key = key,
                durationMs = durationMs,
                provenance = MediaDurationProvenance.PROVIDER,
            )
        }
    }

    private suspend fun submit(
        post: Post,
        key: MediaDurationKey,
        identity: String,
        priority: DurationDemandPriority,
        reason: DurationDemandReason,
    ) {
        coordinator.submit(
            post = post,
            demand = DurationDemand(
                identity = identity,
                key = key,
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
        keysByPostId = emptyMap()
        candidatesByKey = emptyMap()
        knownDurationsByKey = emptyMap()
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
        if (!filterActive) return
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

    private data class DurationSnapshotRequest(
        val generation: Long,
        val identity: String,
        val posts: List<Post>,
        val resolveInBackground: Boolean,
        val previousIdentity: String?,
        val previousPosts: List<Post>?,
        val previousSnapshot: MediaDurationPostSnapshot?,
    )

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
