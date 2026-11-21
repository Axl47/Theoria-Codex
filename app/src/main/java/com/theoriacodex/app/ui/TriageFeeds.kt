package com.theoriacodex.app.ui

import com.theoriacodex.app.recommend.trainingTagsFor
import com.theoriacodex.app.related.ViewerFeedOwner
import com.theoriacodex.app.related.ViewerFeedSnapshot
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.triage.HiddenTag
import com.theoriacodex.app.triage.TriageActions
import com.theoriacodex.app.triage.TriageFeed
import com.theoriacodex.app.triage.TriageFeedSnapshot
import com.theoriacodex.app.triage.TriageReversal
import com.theoriacodex.app.triage.gridVisibilityFilters
import com.theoriacodex.app.ui.components.FOR_YOU_FEED_FAB_CONTEXT
import com.theoriacodex.app.ui.components.SEARCH_FEED_FAB_CONTEXT
import com.theoriacodex.app.ui.components.creatorFeedFabContext
import com.theoriacodex.app.ui.navigation.TriageRequest
import com.theoriacodex.app.ui.routes.BrowsingMetadata
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext

private const val CODEX_FEED_PREFIX = "codex:"

/**
 * The feed [request] names: a live feed owner bound to the query the deck opened over, with the grid's visibility
 * filters applied against [marks], or a Codex's posts. Null when the owner no longer shows that query, such as
 * after the process was restored and the owner is gone.
 */
internal fun TheoriaShell.triageFeed(request: TriageRequest, marks: Flow<BrowsingMetadata?>): TriageFeed? = when (request.source) {
    ViewerStreamSource.SEARCH, ViewerStreamSource.FOR_YOU, ViewerStreamSource.CREATOR_PROFILE -> {
        val filters = gridVisibilityFilters(request.source)
        // The Viewer leaves liked, saved and watched posts in For You, whatever the grid's toggles say.
        val appliesMarks = request.source != ViewerStreamSource.FOR_YOU
        routes.feed(request.source)?.takeIf { it.viewerFeed.queryHash == request.feedKey }?.boundTriageFeed(request.feedKey) { feed ->
            val known = marks.filterNotNull().first()
            filterSearchResults(
                results = feed.results,
                filters = filters,
                likedPostIds = if (appliesMarks) known.likedPostIds else emptySet(),
                savedPostIds = if (appliesMarks) known.savedPostIds else emptySet(),
                watchedPostIds = if (appliesMarks) known.watchedPostIds else emptySet(),
                retainedLikedPostIds = feed.retainedLikedPostIds,
            )
        }
    }
    ViewerStreamSource.CODEX -> request.codexId()?.let(::codexTriageFeed)
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

/** The visibility filters the grid behind [source] applies now, from its saved filter sheet. */
internal fun TheoriaShell.gridVisibilityFilters(source: ViewerStreamSource): SearchVisibilityFilters {
    val context = when (source) {
        ViewerStreamSource.SEARCH -> SEARCH_FEED_FAB_CONTEXT
        ViewerStreamSource.FOR_YOU -> FOR_YOU_FEED_FAB_CONTEXT
        ViewerStreamSource.CREATOR_PROFILE -> routes.creator.current?.state?.value?.creator?.let { creator ->
            creatorFeedFabContext(creator.source.id, creator.profileId ?: creator.uploadsQuery ?: creator.displayName)
        }
        else -> null
    }
    val fab = context?.let(feedFabRestoreRegistry::state) ?: return SearchVisibilityFilters()
    return gridVisibilityFilters(source, fab, routes.search.current?.state?.value?.query?.applied)
}

/**
 * This owner's feed while it shows [queryHash]: the deck's posts and paging follow that one query, and once the
 * owner moves to another query the deck takes nothing more from it and stops asking for pages.
 */
private fun ViewerFeedOwner.boundTriageFeed(queryHash: String, visible: suspend (ViewerFeedSnapshot) -> List<Post>): TriageFeed {
    val owner = this
    return object : TriageFeed {
        override val snapshots = owner.viewerFeedChanges.onStart { emit(owner.viewerFeed) }.transformWhile { feed ->
            val same = feed.queryHash == queryHash
            emit(if (same) TriageFeedSnapshot(visible(feed), feed.canLoadMore) else TriageFeedSnapshot(emptyList(), canLoadMore = false))
            same
        }

        override fun loadNextPage() {
            if (owner.viewerFeed.queryHash == queryHash) owner.loadNextPage()
        }
    }
}

/** The Codex's posts as they are when the deck opens; saves and hides made in the deck do not reshuffle them. */
private fun TheoriaShell.codexTriageFeed(codexId: String): TriageFeed = object : TriageFeed {
    override val snapshots: Flow<TriageFeedSnapshot> = flow {
        val posts = data.codexRepository.observeCodexPosts(codexId, CodexSortMode.NEWEST_SAVED).first()
        emit(TriageFeedSnapshot(posts, canLoadMore = false))
    }

    override fun loadNextPage() = Unit
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
