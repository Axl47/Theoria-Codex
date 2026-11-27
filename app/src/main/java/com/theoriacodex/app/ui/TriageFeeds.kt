package com.theoriacodex.app.ui

import com.theoriacodex.app.codex.codexCleaningReviewPosts
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.animatedDurationMs
import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.app.media.mediaDurationKey
import com.theoriacodex.app.recommend.trainingTagsFor
import com.theoriacodex.app.related.ViewerFeedOwner
import com.theoriacodex.app.related.ViewerFeedSnapshot
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.triage.HiddenTag
import com.theoriacodex.app.triage.TriageActions
import com.theoriacodex.app.triage.TriageFeed
import com.theoriacodex.app.triage.TriageFeedSnapshot
import com.theoriacodex.app.triage.TriageRemoval
import com.theoriacodex.app.triage.TriageReversal
import com.theoriacodex.app.triage.settled
import com.theoriacodex.app.ui.navigation.TriageMode
import com.theoriacodex.app.ui.navigation.TriageRequest
import com.theoriacodex.app.ui.navigation.TriageSeed
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext

internal class SeededTriageFeed(
    override val snapshots: Flow<TriageFeedSnapshot>,
    val candidates: Flow<List<Post>>,
    private val nextPage: () -> Unit,
    private val retry: () -> Unit = {},
) : TriageFeed {
    override fun loadNextPage() = nextPage()
    override fun retryPage() = retry()
}

internal fun TheoriaShell.triageFeed(request: TriageRequest, seed: TriageSeed): SeededTriageFeed? = when (request.source) {
    ViewerStreamSource.SEARCH, ViewerStreamSource.CREATOR_PROFILE ->
        routes.feed(request.source)?.takeIf { it.viewerFeed.queryHash == request.feedKey }
            ?.seededTriageFeed(request.feedKey, seed, features.mediaDurationCoordinator.states)
    ViewerStreamSource.CODEX -> request.codexId?.let { codexId ->
        val alsoIn = (request.mode as? TriageMode.RemoveFromCodex)?.alsoIn.orEmpty()
        val snapshots = if (alsoIn.isEmpty()) flowOf(TriageFeedSnapshot(seed.posts, canLoadMore = false))
        else cleaningReviewSnapshots(codexId, alsoIn)
        SeededTriageFeed(snapshots, flowOf(emptyList()), nextPage = {})
    }
    ViewerStreamSource.FOR_YOU, ViewerStreamSource.RELATED, ViewerStreamSource.RECENTS, ViewerStreamSource.TRAIL,
    ViewerStreamSource.SERIES -> null
}

internal fun TheoriaShell.triageRemoval(request: TriageRequest): TriageRemoval? {
    val codexId = request.codexId?.takeIf { request.mode is TriageMode.RemoveFromCodex } ?: return null
    val commands = owners.codexCommands
    return TriageRemoval { post -> commands.removeWithOwnUndo(codexId, post) }
}

internal suspend fun TheoriaShell.settledTriage(request: TriageRequest, seed: TriageSeed): Pair<TriageRequest, TriageSeed> {
    val search = routes.search.current?.takeIf { request.source == ViewerStreamSource.SEARCH } ?: return request to seed
    routes.dispatchOrQueue(SearchAction.SettleSearch)
    val query = search.state.first { state -> !state.loading }.query
    val settled = request.copy(feedKey = query.appliedQueryHash.ifBlank { request.feedKey })
    return settled to seed.settled(sameQuery = settled == request, query.applied.dateRange?.boundsAt(System.currentTimeMillis()))
}

private fun ViewerFeedOwner.seededTriageFeed(
    queryHash: String,
    seed: TriageSeed,
    lengths: Flow<Map<MediaDurationKey, MediaDurationState>>,
): SeededTriageFeed {
    val owner = this
    val feed: Flow<ViewerFeedSnapshot?> = owner.viewerFeedChanges.onStart { emit(owner.viewerFeed) }.transformWhile { feed ->
        val same = feed.queryHash == queryHash
        emit(feed.takeIf { same })
        same
    }
    val snapshots = combine(feed, if (seed.lengthFilterActive) lengths else flowOf(emptyMap())) { current, decided ->
        if (current == null) return@combine TriageFeedSnapshot(emptyList(), canLoadMore = false)
        val shown = seed.filter(current.results, decidedLengths(current.results, decided))
        TriageFeedSnapshot(
            seed.posts + shown, current.canLoadMore, loaded = current.results.size,
            loadingMore = current.loadingMore, pageFailed = current.pageFailed,
        )
    }.distinctUntilChanged().flowOn(Dispatchers.Default)
    return SeededTriageFeed(
        snapshots,
        feed.map { current -> current?.results.orEmpty() },
        nextPage = { if (owner.viewerFeed.queryHash == queryHash) owner.loadNextPage() },
        retry = { if (owner.viewerFeed.queryHash == queryHash) owner.retryPage() },
    )
}

private fun TheoriaShell.cleaningReviewSnapshots(codexId: String, alsoIn: Set<String>): Flow<TriageFeedSnapshot> = flow {
    val codices = data.codexRepository
    val posts = codices.observeCodexPosts(codexId, CodexSortMode.NEWEST_SAVED).first()
    emit(TriageFeedSnapshot(codexCleaningReviewPosts(posts, alsoIn.map { codices.observeCodexItems(it).first() }), canLoadMore = false))
}

private fun decidedLengths(posts: List<Post>, decided: Map<MediaDurationKey, MediaDurationState>): Map<PostId, Long> =
    if (decided.isEmpty()) emptyMap() else buildMap {
        posts.forEach { post ->
            if (!isAnimatedPost(post) || animatedDurationMs(post) != null) return@forEach
            (decided[mediaDurationKey(post)] as? MediaDurationState.Known)?.let { known -> put(post.id, known.durationMs) }
        }
    }

internal class ShellTriageActions(private val shell: TheoriaShell) : TriageActions {
    private val settings get() = shell.data.settingsRepository
    private val likes get() = shell.workflows.likesCodexSync
    private val activeProfile get() = shell.owners.settings.state.value.activeProfile

    override val hiddenTags: Flow<Set<HiddenTag>> = settings.observeSettings().map { app ->
        app.forYouBlacklistByProfile[app.activeProfileId].orEmpty()
            .filter { entry -> entry.tags.size == 1 }
            .mapTo(mutableSetOf()) { entry -> HiddenTag(entry.source, entry.tags.single()) }
    }.distinctUntilChanged()

    override suspend fun like(post: Post): TriageReversal? = like(activeProfile, post)

    override suspend fun hide(source: SourceKey, tag: String): TriageReversal? = hide(activeProfile.profileId, source, tag)

    override fun atDecision(): TriageActions {
        val profile = activeProfile
        val actions = this
        return object : TriageActions by actions {
            override suspend fun like(post: Post) = actions.like(profile, post)
            override suspend fun hide(source: SourceKey, tag: String) = actions.hide(profile.profileId, source, tag)
        }
    }

    private suspend fun like(profile: RecommendationProfile, post: Post): TriageReversal? {
        val tags = withContext(Dispatchers.Default) { trainingTagsFor(post) }
        val like = likes.likeIfAbsent(profile, post, tags) ?: return null
        return { likes.removeLike(profile, post, like) }
    }

    private suspend fun hide(profileId: String, source: SourceKey, tag: String): TriageReversal? {
        if (!settings.addForYouBlacklistEntry(profileId, source, listOf(tag))) return null
        return { settings.removeForYouBlacklistEntry(profileId, source, listOf(tag)) }
    }
}
