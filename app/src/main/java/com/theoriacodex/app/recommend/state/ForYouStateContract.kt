package com.theoriacodex.app.recommend.state

import com.theoriacodex.app.recommend.ForYouCoordinator
import com.theoriacodex.app.recommend.buildForYouSeedId
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.source.inPresentationOrder
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.repository.defaultRecommendationProfiles
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunStatus

enum class ForYouEmptyReason {
    NO_LIKES,
    NO_ENABLED_SOURCES,
    SEEDS_EXHAUSTED,
    NO_RESULTS,
}

enum class ForYouRequestKind {
    REFRESH,
    PAGE,
}

data class ForYouRequestIdentity(
    val generation: Long,
    val kind: ForYouRequestKind,
    val profileId: String,
    val source: SourceKey?,
    val sortMode: SortMode,
    val seedId: String,
) {
    init {
        require(generation > 0L) { "For You request generation must be positive" }
    }
}

data class ForYouUiState(
    val profiles: List<RecommendationProfile> = defaultRecommendationProfiles(),
    val activeProfileId: String = defaultRecommendationProfiles().first().profileId,
    val activeProfileLikesCount: Int = 0,
    val availableSources: List<SourceKey> = emptyList(),
    val selectedSource: SourceKey? = null,
    val sortMode: SortMode = SortMode.NEWEST,
    val seedId: String = "init",
    val seedSummaryBySource: Map<SourceKey, List<String>> = emptyMap(),
    val blacklistEntries: List<ForYouBlacklistEntry> = emptyList(),
    val results: List<Post> = emptyList(),
    val statuses: List<SourceRunStatus> = emptyList(),
    val isRefreshing: Boolean = false,
    val isPaging: Boolean = false,
    val canLoadMore: Boolean = false,
    val errorMessage: String? = null,
    val emptyReason: ForYouEmptyReason? = null,
    val nextRequestGeneration: Long = 1L,
    val activeRequest: ForYouRequestIdentity? = null,
) {
    val canRefresh: Boolean
        get() = activeProfileLikesCount > 0 && !isRefreshing && !isPaging

    val canBlacklistCurrentSeed: Boolean
        get() = canRefresh && seedSummaryBySource.isNotEmpty()
}

sealed interface ForYouAction {
    data class Refresh(val shuffle: Boolean = true) : ForYouAction
    data class SelectProfile(val profileId: String) : ForYouAction
    data class SelectSource(val source: SourceKey?) : ForYouAction
    data class SelectSort(val sortMode: SortMode) : ForYouAction
    data class ReplaySearch(
        val seedBySource: Map<SourceKey, List<String>>,
        val sortMode: SortMode,
    ) : ForYouAction
    data object BlacklistCurrentSeed : ForYouAction
    data class UndoSeedBlacklist(
        val profileId: String,
        val entries: List<ForYouBlacklistEntry>,
    ) : ForYouAction
    data object LoadNextPage : ForYouAction
    data class OpenResult(
        val index: Int,
        val scrollOffsetHint: Int = 0,
        val visibleResults: List<Post>? = null,
        val visibilityFilters: SearchVisibilityFilters = SearchVisibilityFilters(),
    ) : ForYouAction
    data object GoToSearch : ForYouAction
    data class RequestAnimatedDurationEnrichment(val seedId: String) : ForYouAction

    data class RefreshCompleted(
        val request: ForYouRequestIdentity,
        val snapshot: ForYouCoordinatorSnapshot,
    ) : ForYouAction

    data class RefreshFailed(
        val request: ForYouRequestIdentity,
        val message: String,
    ) : ForYouAction

    data class PageLoaded(
        val request: ForYouRequestIdentity,
        val posts: List<Post>,
        val statuses: List<SourceRunStatus>,
        val canLoadMore: Boolean,
    ) : ForYouAction

    data class PageFailed(
        val request: ForYouRequestIdentity,
        val message: String,
    ) : ForYouAction

    data class RequestCancelled(val request: ForYouRequestIdentity) : ForYouAction
}

sealed interface ForYouEffect {
    val request: ForYouRequestIdentity?

    data class RefreshFeed(
        override val request: ForYouRequestIdentity,
        val shuffle: Boolean,
    ) : ForYouEffect

    data class ChangeProfile(
        override val request: ForYouRequestIdentity,
        val profileId: String,
    ) : ForYouEffect

    data class ChangeSource(
        override val request: ForYouRequestIdentity,
        val source: SourceKey?,
    ) : ForYouEffect

    data class ChangeSort(
        override val request: ForYouRequestIdentity,
        val sortMode: SortMode,
    ) : ForYouEffect

    data class ReplaySearch(
        override val request: ForYouRequestIdentity,
        val seedBySource: Map<SourceKey, List<String>>,
        val sortMode: SortMode,
    ) : ForYouEffect

    data class BlacklistSeed(
        override val request: ForYouRequestIdentity,
        val profileId: String,
        val seedSummaryBySource: Map<SourceKey, List<String>>,
    ) : ForYouEffect

    data class UndoSeedBlacklist(
        override val request: ForYouRequestIdentity,
        val profileId: String,
        val entries: List<ForYouBlacklistEntry>,
    ) : ForYouEffect

    data class LoadNextPage(
        override val request: ForYouRequestIdentity,
    ) : ForYouEffect

    data class OpenViewer(
        val posts: List<Post>,
        val context: ViewerLaunchContext,
        val visibilityFilters: SearchVisibilityFilters = SearchVisibilityFilters(),
    ) : ForYouEffect {
        override val request: ForYouRequestIdentity? = null
    }

    data object NavigateToSearch : ForYouEffect {
        override val request: ForYouRequestIdentity? = null
    }

    data class SeedHidden(
        val profileId: String,
        val entries: List<ForYouBlacklistEntry>,
    ) : ForYouEffect {
        override val request: ForYouRequestIdentity? = null
    }

    data class ShowMessage(
        val message: String,
    ) : ForYouEffect {
        override val request: ForYouRequestIdentity? = null
    }
}

data class ForYouTransition(
    val state: ForYouUiState,
    val effect: ForYouEffect? = null,
)

data class ForYouCoordinatorSnapshot(
    val profiles: List<RecommendationProfile> = defaultRecommendationProfiles(),
    val activeProfileId: String,
    val activeProfileLikesCount: Int,
    val availableSources: List<SourceKey>,
    val selectedSource: SourceKey?,
    val sortMode: SortMode,
    val seedId: String,
    val seedSummaryBySource: Map<SourceKey, List<String>>,
    val blacklistEntries: List<ForYouBlacklistEntry> = emptyList(),
    val results: List<Post>,
    val statuses: List<SourceRunStatus>,
    val loading: Boolean,
    val loadingMore: Boolean,
    val canLoadMore: Boolean,
    val errorMessage: String?,
)

fun ForYouCoordinator.toUiState(
    profiles: List<RecommendationProfile> = defaultRecommendationProfiles(),
    blacklistEntries: List<ForYouBlacklistEntry> = emptyList(),
): ForYouUiState {
    return ForYouCoordinatorSnapshot(
        profiles = profiles,
        activeProfileId = activeProfileId,
        activeProfileLikesCount = activeProfileLikesCount,
        availableSources = availableSourceSelections,
        selectedSource = selectedSource,
        sortMode = sortMode,
        seedId = seedId,
        seedSummaryBySource = seedSummaryBySource,
        blacklistEntries = blacklistEntries,
        results = results,
        statuses = statuses,
        loading = loading,
        loadingMore = loadingMore,
        canLoadMore = canLoadMore,
        errorMessage = errorMessage,
    ).toUiState()
}

fun ForYouCoordinatorSnapshot.toUiState(): ForYouUiState {
    val copiedSeeds = seedSummaryBySource
        .toSortedMap(compareBy(SourceKey::name))
        .mapValues { (_, tags) -> tags.toList() }
    val copiedResults = results.toList()
    val copiedError = errorMessage?.takeIf(String::isNotBlank)
    return ForYouUiState(
        profiles = profiles.toList(),
        activeProfileId = activeProfileId,
        activeProfileLikesCount = activeProfileLikesCount.coerceAtLeast(0),
        availableSources = availableSources.inPresentationOrder(),
        selectedSource = selectedSource,
        sortMode = sortMode,
        seedId = seedId,
        seedSummaryBySource = copiedSeeds,
        blacklistEntries = blacklistEntries.toList(),
        results = copiedResults,
        statuses = statuses.sortedBy { status -> status.source.name },
        isRefreshing = loading,
        isPaging = loadingMore,
        canLoadMore = canLoadMore,
        errorMessage = copiedError,
        emptyReason = inferForYouEmptyReason(
            seedId = seedId,
            hasSeed = copiedSeeds.isNotEmpty(),
            likesCount = activeProfileLikesCount,
            results = copiedResults,
            isRefreshing = loading,
            errorMessage = copiedError,
        ),
    )
}

fun ForYouUiState.reduce(action: ForYouAction): ForYouTransition {
    return when (action) {
        is ForYouAction.Refresh -> {
            if (!canRefresh) unchanged() else beginRefresh { request ->
                ForYouEffect.RefreshFeed(request = request, shuffle = action.shuffle)
            }
        }

        is ForYouAction.SelectProfile -> {
            if (action.profileId == activeProfileId || profiles.none { it.profileId == action.profileId }) {
                unchanged()
            } else {
                copy(activeProfileId = action.profileId, activeProfileLikesCount = 0)
                    .beginRefresh { request ->
                        ForYouEffect.ChangeProfile(request = request, profileId = action.profileId)
                    }
            }
        }

        is ForYouAction.SelectSource -> {
            val normalized = action.source?.takeIf { source -> source in availableSources }
            if (isRefreshing || isPaging || normalized == selectedSource) {
                unchanged()
            } else {
                copy(selectedSource = normalized)
                    .beginRefresh { request ->
                        ForYouEffect.ChangeSource(request = request, source = normalized)
                    }
            }
        }

        is ForYouAction.SelectSort -> {
            if (isRefreshing || isPaging || action.sortMode == sortMode) {
                unchanged()
            } else {
                copy(sortMode = action.sortMode)
                    .beginRefresh { request ->
                        ForYouEffect.ChangeSort(request = request, sortMode = action.sortMode)
                    }
            }
        }

        is ForYouAction.ReplaySearch -> {
            if (action.seedBySource.isEmpty()) {
                unchanged()
            } else {
                copy(
                    selectedSource = action.seedBySource.keys.singleOrNull(),
                    sortMode = action.sortMode,
                    seedId = buildForYouSeedId(action.seedBySource),
                    seedSummaryBySource = action.seedBySource.mapValues { (_, tags) -> tags.toList() },
                ).beginRefresh { request ->
                    ForYouEffect.ReplaySearch(
                        request = request,
                        seedBySource = action.seedBySource.mapValues { (_, tags) -> tags.toList() },
                        sortMode = action.sortMode,
                    )
                }
            }
        }

        ForYouAction.BlacklistCurrentSeed -> {
            if (!canBlacklistCurrentSeed) {
                unchanged()
            } else {
                beginRefresh { request ->
                    ForYouEffect.BlacklistSeed(
                        request = request,
                        profileId = activeProfileId,
                        seedSummaryBySource = seedSummaryBySource.mapValues { (_, tags) -> tags.toList() },
                    )
                }
            }
        }

        is ForYouAction.UndoSeedBlacklist -> {
            if (action.entries.isEmpty()) {
                unchanged()
            } else {
                beginRefresh { request ->
                    ForYouEffect.UndoSeedBlacklist(
                        request = request,
                        profileId = action.profileId,
                        entries = action.entries.toList(),
                    )
                }
            }
        }

        ForYouAction.LoadNextPage -> {
            if (isRefreshing || isPaging || !canLoadMore) {
                unchanged()
            } else {
                val request = nextRequest(ForYouRequestKind.PAGE)
                ForYouTransition(
                    state = copy(
                        isPaging = true,
                        errorMessage = null,
                        emptyReason = null,
                        nextRequestGeneration = request.generation + 1L,
                        activeRequest = request,
                    ),
                    effect = ForYouEffect.LoadNextPage(request),
                )
            }
        }

        is ForYouAction.OpenResult -> {
            val viewerResults = action.visibleResults ?: results
            if (action.index !in viewerResults.indices) {
                unchanged()
            } else {
                ForYouTransition(
                    state = this,
                    effect = ForYouEffect.OpenViewer(
                        posts = viewerResults.toList(),
                        context = ViewerLaunchContext(
                            queryHash = "for_you:$seedId",
                            startIndex = action.index,
                            streamSource = ViewerStreamSource.FOR_YOU,
                            scrollOffsetHint = action.scrollOffsetHint,
                        ),
                        visibilityFilters = action.visibilityFilters,
                    ),
                )
            }
        }

        ForYouAction.GoToSearch -> ForYouTransition(this, ForYouEffect.NavigateToSearch)
        is ForYouAction.RequestAnimatedDurationEnrichment -> unchanged()
        is ForYouAction.RefreshCompleted -> {
            if (!accepts(action.request, ForYouRequestKind.REFRESH)) {
                unchanged()
            } else {
                val completed = action.snapshot.copy(loading = false, loadingMore = false).toUiState()
                ForYouTransition(
                    completed.copy(
                        nextRequestGeneration = nextRequestGeneration,
                        activeRequest = null,
                    )
                )
            }
        }

        is ForYouAction.RefreshFailed -> {
            if (!accepts(action.request, ForYouRequestKind.REFRESH)) {
                unchanged()
            } else {
                ForYouTransition(
                    copy(
                        results = emptyList(),
                        statuses = emptyList(),
                        isRefreshing = false,
                        isPaging = false,
                        canLoadMore = false,
                        errorMessage = action.message,
                        emptyReason = null,
                        activeRequest = null,
                    )
                )
            }
        }

        is ForYouAction.PageLoaded -> {
            if (!accepts(action.request, ForYouRequestKind.PAGE)) {
                unchanged()
            } else {
                ForYouTransition(
                    copy(
                        results = mergePosts(results, action.posts),
                        statuses = action.statuses.sortedBy { status -> status.source.name },
                        isPaging = false,
                        canLoadMore = action.canLoadMore,
                        errorMessage = null,
                        emptyReason = null,
                        activeRequest = null,
                    )
                )
            }
        }

        is ForYouAction.PageFailed -> {
            if (!accepts(action.request, ForYouRequestKind.PAGE)) {
                unchanged()
            } else {
                ForYouTransition(
                    copy(
                        isPaging = false,
                        canLoadMore = false,
                        errorMessage = action.message,
                        activeRequest = null,
                    )
                )
            }
        }

        is ForYouAction.RequestCancelled -> {
            if (activeRequest != action.request) {
                unchanged()
            } else {
                val cancelled = copy(
                    isRefreshing = false,
                    isPaging = false,
                    errorMessage = null,
                    activeRequest = null,
                )
                ForYouTransition(
                    cancelled.copy(
                        emptyReason = inferForYouEmptyReason(
                            seedId = cancelled.seedId,
                            hasSeed = cancelled.seedSummaryBySource.isNotEmpty(),
                            likesCount = cancelled.activeProfileLikesCount,
                            results = cancelled.results,
                            isRefreshing = false,
                            errorMessage = null,
                        )
                    )
                )
            }
        }
    }
}

private fun ForYouUiState.beginRefresh(
    effect: (ForYouRequestIdentity) -> ForYouEffect,
): ForYouTransition {
    val request = nextRequest(ForYouRequestKind.REFRESH)
    return ForYouTransition(
        state = copy(
            statuses = emptyList(),
            isRefreshing = true,
            isPaging = false,
            canLoadMore = false,
            errorMessage = null,
            emptyReason = null,
            nextRequestGeneration = request.generation + 1L,
            activeRequest = request,
        ),
        effect = effect(request),
    )
}

private fun ForYouUiState.nextRequest(kind: ForYouRequestKind): ForYouRequestIdentity {
    return ForYouRequestIdentity(
        generation = nextRequestGeneration,
        kind = kind,
        profileId = activeProfileId,
        source = selectedSource,
        sortMode = sortMode,
        seedId = seedId,
    )
}

private fun ForYouUiState.accepts(
    request: ForYouRequestIdentity,
    kind: ForYouRequestKind,
): Boolean = request.kind == kind && activeRequest == request

private fun ForYouUiState.unchanged(): ForYouTransition = ForYouTransition(this)

private fun inferForYouEmptyReason(
    seedId: String,
    hasSeed: Boolean,
    likesCount: Int,
    results: List<Post>,
    isRefreshing: Boolean,
    errorMessage: String?,
): ForYouEmptyReason? {
    if (seedId == "init" || isRefreshing || errorMessage != null || results.isNotEmpty()) return null
    return when {
        seedId == "empty-enabled" -> ForYouEmptyReason.NO_ENABLED_SOURCES
        seedId == "empty-seed" -> ForYouEmptyReason.SEEDS_EXHAUSTED
        hasSeed -> ForYouEmptyReason.NO_RESULTS
        likesCount <= 0 -> ForYouEmptyReason.NO_LIKES
        else -> ForYouEmptyReason.NO_RESULTS
    }
}

private fun mergePosts(existing: List<Post>, incoming: List<Post>): List<Post> {
    if (incoming.isEmpty()) return existing
    val byId = LinkedHashMap(existing.associateBy(Post::id))
    incoming.forEach { post -> byId[post.id] = post }
    return byId.values.toList()
}
