package com.theoriacodex.app.ui

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
import com.theoriacodex.app.triage.TriageReversal
import com.theoriacodex.app.ui.navigation.TriageRequest
import com.theoriacodex.app.ui.navigation.TriageSeed
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext

private const val CODEX_FEED_PREFIX = "codex:"

/**
 * A deck's feed, plus [candidates]: the live owner's posts the deck picks from, whose lengths the deck resolves while
 * its grid filters by length, as the grid would.
 */
internal class SeededTriageFeed(
    override val snapshots: Flow<TriageFeedSnapshot>,
    val candidates: Flow<List<Post>>,
    private val nextPage: () -> Unit,
) : TriageFeed {
    override fun loadNextPage() = nextPage()
}

/**
 * The feed [request] names, seeded with what its grid handed over ([seed]). A live feed gives the grid's posts in its
 * order, then whatever else of the owner's posts the grid's filter keeps with the lengths known so far, while the owner
 * still shows the deck's query; a Codex or Likes gives exactly the posts its grid showed. Null when the owner no
 * longer shows that query.
 */
internal fun TheoriaShell.triageFeed(request: TriageRequest, seed: TriageSeed): SeededTriageFeed? = when (request.source) {
    ViewerStreamSource.SEARCH, ViewerStreamSource.FOR_YOU, ViewerStreamSource.CREATOR_PROFILE ->
        routes.feed(request.source)?.takeIf { it.viewerFeed.queryHash == request.feedKey }
            ?.seededTriageFeed(request.feedKey, seed, features.mediaDurationCoordinator.states)
    ViewerStreamSource.CODEX -> request.codexId()?.let {
        SeededTriageFeed(flowOf(TriageFeedSnapshot(seed.posts, canLoadMore = false)), flowOf(emptyList())) {}
    }
    ViewerStreamSource.RELATED, ViewerStreamSource.RECENTS, ViewerStreamSource.TRAIL -> null
}

/** The Codex a [ViewerStreamSource.CODEX] request names, from its `codex:<id>` feed key. */
internal fun TriageRequest.codexId(): String? = feedKey.takeIf { it.startsWith(CODEX_FEED_PREFIX) }?.removePrefix(CODEX_FEED_PREFIX)

/**
 * [request] once Search's filters have applied: a filter change still waiting to run starts now, and its results
 * arrive before the deck opens, so the deck works through the query the grid is about to show. Other feeds apply
 * their filters on the spot.
 */
internal suspend fun TheoriaShell.settledTriageRequest(request: TriageRequest): TriageRequest {
    val search = routes.search.current?.takeIf { request.source == ViewerStreamSource.SEARCH } ?: return request
    routes.dispatchOrQueue(SearchAction.SettleSearch)
    val settled = search.state.first { state -> !state.loading }
    return request.copy(feedKey = settled.query.appliedQueryHash.ifBlank { request.feedKey })
}

/**
 * This owner's feed while it shows [queryHash], filtered as [seed]'s grid filters it: the deck's posts and paging
 * follow that one query, and once the owner moves to another query the deck takes nothing more from it and stops
 * asking for pages. [lengths] are the app's decided lengths, which the filter reads while it filters by length.
 */
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
        TriageFeedSnapshot(seed.posts + shown, current.canLoadMore)
    }.distinctUntilChanged().flowOn(Dispatchers.Default)
    return SeededTriageFeed(snapshots, feed.map { current -> current?.results.orEmpty() }) {
        if (owner.viewerFeed.queryHash == queryHash) owner.loadNextPage()
    }
}

/** The lengths decided for [posts] whose media does not state its own, by post. */
private fun decidedLengths(posts: List<Post>, decided: Map<MediaDurationKey, MediaDurationState>): Map<PostId, Long> =
    if (decided.isEmpty()) emptyMap() else buildMap {
        posts.forEach { post ->
            if (!isAnimatedPost(post) || animatedDurationMs(post) != null) return@forEach
            (decided[mediaDurationKey(post)] as? MediaDurationState.Known)?.let { known -> put(post.id, known.durationMs) }
        }
    }

/**
 * The deck's likes and hides. Likes go straight through the likes workflow, not the queued toggle feed cards use, so
 * each call returns once its Like commits and throws when it fails; hides go to the active profile's For You tag
 * blacklist, whose single-tag entries are what hiding adds. Each reversal names the profile its write used.
 */
internal class ShellTriageActions(private val shell: TheoriaShell) : TriageActions {
    private val settings get() = shell.data.settingsRepository
    private val likes get() = shell.workflows.likesCodexSync

    override val hiddenTags: Flow<Set<HiddenTag>> = settings.observeSettings().map { app ->
        app.forYouBlacklistByProfile[app.activeProfileId].orEmpty()
            .filter { entry -> entry.tags.size == 1 }
            .mapTo(mutableSetOf()) { entry -> HiddenTag(entry.source, entry.tags.single()) }
    }.distinctUntilChanged()

    override suspend fun like(post: Post): TriageReversal? {
        val profile = shell.owners.settings.state.value.activeProfile
        val tags = withContext(Dispatchers.Default) { trainingTagsFor(post) }
        val like = likes.likeIfAbsent(profile, post, tags) ?: return null
        return { likes.removeLike(profile, post, like) }
    }

    override suspend fun hide(source: SourceKey, tag: String): TriageReversal? {
        val profileId = settings.observeSettings().first().activeProfileId
        if (!settings.addForYouBlacklistEntry(profileId, source, listOf(tag))) return null
        return { settings.removeForYouBlacklistEntry(profileId, source, listOf(tag)) }
    }
}
