package com.theoriacodex.app.ui

import com.theoriacodex.app.recommend.trainingTagsFor
import com.theoriacodex.app.related.LikeToggleOutcome
import com.theoriacodex.app.related.ViewerFeedOwner
import com.theoriacodex.app.triage.HiddenTag
import com.theoriacodex.app.triage.TriageActions
import com.theoriacodex.app.triage.TriageFeed
import com.theoriacodex.app.triage.TriageFeedSnapshot
import com.theoriacodex.app.ui.navigation.TriageRequest
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

private const val CODEX_FEED_PREFIX = "codex:"

/**
 * The feed [request] names: a live feed owner that still shows the query the deck was opened over, or a Codex's
 * posts. Null when there is nothing to work through, such as after the process was restored and the owner is gone.
 */
internal fun TheoriaShell.triageFeed(request: TriageRequest): TriageFeed? = when (request.source) {
    ViewerStreamSource.SEARCH, ViewerStreamSource.FOR_YOU, ViewerStreamSource.CREATOR_PROFILE ->
        routes.feed(request.source)?.takeIf { it.showsFeed(request.feedKey) }?.asTriageFeed()
    ViewerStreamSource.CODEX -> request.codexId()?.let(::codexTriageFeed)
    ViewerStreamSource.RELATED, ViewerStreamSource.RECENTS, ViewerStreamSource.TRAIL -> null
}

/** The Codex a [ViewerStreamSource.CODEX] request names, from its `codex:<id>` feed key. */
internal fun TriageRequest.codexId(): String? = feedKey.takeIf { it.startsWith(CODEX_FEED_PREFIX) }?.removePrefix(CODEX_FEED_PREFIX)

private fun ViewerFeedOwner.showsFeed(feedKey: String): Boolean = feedKey.isEmpty() || viewerFeed.queryHash == feedKey

private fun ViewerFeedOwner.asTriageFeed(): TriageFeed {
    val owner = this
    return object : TriageFeed {
        override val snapshots = owner.viewerFeedChanges.onStart { emit(owner.viewerFeed) }
            .map { feed -> TriageFeedSnapshot(feed.results, feed.canLoadMore) }

        override fun loadNextPage() = owner.loadNextPage()
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
 * The deck's likes and hides. Likes write straight through the likes workflow, not the queued toggle feed cards
 * use, so each call returns once its Like commits and throws when it fails; hides go to the active profile's For
 * You tag blacklist, whose single-tag entries are what hiding adds.
 */
internal class ShellTriageActions(private val shell: TheoriaShell) : TriageActions {
    private val settings get() = shell.data.settingsRepository

    override val hiddenTags: Flow<Set<HiddenTag>> = settings.observeSettings().map { app ->
        app.forYouBlacklistByProfile[app.activeProfileId].orEmpty()
            .filter { entry -> entry.tags.size == 1 }
            .mapTo(mutableSetOf()) { entry -> HiddenTag(entry.source, entry.tags.single()) }
    }.distinctUntilChanged()

    override suspend fun like(post: Post): Boolean {
        val profile = shell.owners.settings.state.value.activeProfile
        if (isLiked(profile, post)) return false
        check(toggleLike(profile, post) == LikeToggleOutcome.LIKED) { "The Like was not added" }
        return true
    }

    override suspend fun unlike(post: Post) {
        val profile = shell.owners.settings.state.value.activeProfile
        if (isLiked(profile, post)) toggleLike(profile, post)
    }

    override suspend fun hide(source: SourceKey, tag: String): Boolean =
        settings.addForYouBlacklistEntry(settings.observeSettings().first().activeProfileId, source, listOf(tag))

    override suspend fun unhide(source: SourceKey, tag: String) {
        settings.removeForYouBlacklistEntry(settings.observeSettings().first().activeProfileId, source, listOf(tag))
    }

    private suspend fun isLiked(profile: RecommendationProfile, post: Post): Boolean =
        post.id in shell.data.likesRepository.observeLikedPostIds(profile.profileId).first()

    private suspend fun toggleLike(profile: RecommendationProfile, post: Post): LikeToggleOutcome {
        val tags = withContext(Dispatchers.Default) { trainingTagsFor(post) }
        return shell.workflows.likesCodexSync.toggle(profile, post, tags)
    }
}
