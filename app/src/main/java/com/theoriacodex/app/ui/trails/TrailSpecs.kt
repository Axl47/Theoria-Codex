package com.theoriacodex.app.ui.trails

import com.theoriacodex.app.codex.SavedSearchBranch
import com.theoriacodex.app.codex.SavedSearchFeedLoader
import com.theoriacodex.app.codex.SavedSearchQueryBranch
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.MediaFilter
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** What a trail shows: its crumb [label], the [query] it searches (tag trails only) and the [pager] that feeds it. */
internal class TrailSpec(val label: String, val query: Query?, val pager: TrailPager)

/** Where each kind of trail gets its posts. */
internal interface TrailSources {
    fun tag(query: Query): TrailPager
    fun creator(creator: CreatorProfile): TrailPager?
    fun similar(seed: Post): TrailPager?
}

/** The trail [request] asks for, or null when its source cannot supply one. */
internal fun trailSpec(request: TrailRequest, sources: TrailSources): TrailSpec? = when (request) {
    is TrailRequest.Tag -> trailTagQuery(request.tag, request.source, request.context).let { query ->
        TrailSpec(request.tag.replace('_', ' '), query, sources.tag(query))
    }
    is TrailRequest.Creator -> sources.creator(request.creator)?.let { TrailSpec(request.creator.displayName, null, it) }
    is TrailRequest.Similar -> sources.similar(request.from)?.let { TrailSpec("Similar", null, it) }
}

/**
 * One tag, searched where the level it came from searched (everywhere for a Unified level, else the post's own source)
 * and under that level's filters: its sort, media, dates and, where it searched the same place, its exclusions.
 */
internal fun trailTagQuery(tag: String, source: SourceKey, context: Query?): Query {
    val mode = if (context?.mode == QueryMode.Unified) QueryMode.Unified else QueryMode.Source(source)
    val tagOnly = Query(mode, listOf(tag), emptyList(), context?.sort ?: SortMode.NEWEST, context?.dateRange, null)
    if (context == null) return tagOnly
    // Another source's exclusions may name tags this one spells differently, so they stay with their level.
    val excluded = if (context.mode == mode) context.excludeTerms.filterNot { it.value.equals(tag, ignoreCase = true) } else emptyList()
    return tagOnly.copy(excludeTerms = excluded, media = context.media)
}

/**
 * The phone-side filters a fork keeps from the level it leaves. A tag trail's own query decides its media and dates.
 * A length filter is dropped: a fork's lengths are not known yet, and an unknown length would hide every post.
 */
internal fun trailVisibilityFilters(parent: SearchVisibilityFilters, query: Query?): SearchVisibilityFilters = parent.copy(
    animatedOnly = query?.let { it.media == MediaFilter.ANIMATED } ?: parent.animatedOnly,
    dateRange = query?.dateRange,
    animatedDurationRange = AnimatedDurationRange.Full,
)

/**
 * Pages [query] through the read-only saved-search loader, which records nothing in Search, Recents or statistics. A page
 * that failed throws, so the feed keeps its posts and can ask again, and the next call retries what failed.
 */
internal fun tagTrailPager(loader: SavedSearchFeedLoader, query: Query): TrailPager {
    val entry = SavedSearchEntry("trail", "trail", RecentSearchEntry(query, "trail", 0L), 0L)
    var branch = SavedSearchBranch(entry, listOf(SavedSearchQueryBranch(query, SearchSourceScope.fromQuery(query))))
    var failed = false
    return TrailPager {
        branch = loader.load(branch, retry = failed)
        // A request error stops the loader paging; a source failing while others page on does not.
        failed = branch.requestError != null || branch.parts.any { it.requestError != null }
        if (failed || branch.posts.isEmpty()) branch.error?.let(::error)
        TrailPage(branch.posts, branch.canLoadMore)
    }
}

/** Pages [creator]'s uploads, keeping only posts from the creator's own source; [learn] sees every page. */
internal fun creatorTrailPager(
    adapter: CreatorPostsSourceAdapter,
    creator: CreatorProfile,
    learn: (List<Post>) -> Unit,
    providerContext: CoroutineContext = Dispatchers.IO,
): TrailPager {
    var posts = emptyList<Post>()
    var token: String? = null
    return TrailPager {
        val page = withTimeout(TRAIL_PAGE_TIMEOUT_MS) { withContext(providerContext) { adapter.searchCreatorPosts(creator, token) } }
        learn(page.items)
        posts = (posts + page.items.filter { it.id.source == creator.source }).distinctBy(Post::id)
        token = page.nextPageToken
        TrailPage(posts, !token.isNullOrBlank())
    }
}

/** One page of posts like [seed]; related posts do not page. */
internal fun similarTrailPager(loader: RelatedPostsLoading, seed: Post): TrailPager? =
    if (loader.supports(seed.id.source)) TrailPager { TrailPage(loader.load(seed.id).filter { it.id != seed.id }, canLoadMore = false) } else null

private const val TRAIL_PAGE_TIMEOUT_MS = 15_000L
