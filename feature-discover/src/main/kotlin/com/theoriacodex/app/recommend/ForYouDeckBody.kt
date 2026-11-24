package com.theoriacodex.app.recommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.codex.ChosenCodex
import com.theoriacodex.app.codex.LocalNewCodexOpener
import com.theoriacodex.app.feed.FeedPageDemandInput
import com.theoriacodex.app.related.RelatedShelfBinding
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.triage.TriageChoice
import com.theoriacodex.app.triage.TriageDeckCallbacks
import com.theoriacodex.app.triage.TriageDeckScreen
import com.theoriacodex.app.triage.TriageFeedSnapshot
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.ui.components.FeedContinueLoading
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.FeedFailureTile
import com.theoriacodex.app.ui.components.FeedGrid
import com.theoriacodex.app.ui.components.FeedLoadingState
import com.theoriacodex.app.ui.components.FeedPostCard
import com.theoriacodex.app.ui.components.FeedRelatedPostsShelf
import com.theoriacodex.app.ui.components.pagedFeedFooterMessage
import com.theoriacodex.app.ui.components.rememberFeedPageDemand
import com.theoriacodex.app.ui.components.rememberFeedView
import com.theoriacodex.app.ui.components.rememberRetainedFeedGridState
import com.theoriacodex.domain.model.Post

/** The level's posts as the deck or the grid, or what to say when it has none. */
@Composable
internal fun ForYouBody(
    owner: ForYouDeckViewModel,
    state: ForYouDeckUiState,
    level: ForYouLevel,
    config: ForYouScreenConfig,
    related: RelatedShelfBinding,
    callbacks: ForYouDeckCallbacks,
    onLongPress: (Post) -> Unit,
) {
    val feed by level.feed.collectAsStateWithLifecycle()
    val end: @Composable () -> Unit = {
        ForYouEnd(state.mix.takeIf { level.fork == null }, feed.posts.isEmpty(), owner::rebuild, callbacks.onGoToSearch, callbacks.onOpenSourceAccounts)
    }
    when {
        feed.posts.isEmpty() && feed.pageFailed && !feed.loadingMore -> Column(Modifier.padding(16.dp)) {
            // The tile words a lost connection itself.
            FeedFailureTile(state.failure?.takeIf { level.fork == null } ?: LOAD_FAILED, onRetry = level::retryPage)
        }
        config.grid -> ForYouGrid(level, feed, config, related, callbacks, onLongPress, end)
        else -> ForYouDeck(owner, level, feed, config, callbacks, end)
    }
}

@Composable
private fun ForYouDeck(
    owner: ForYouDeckViewModel,
    level: ForYouLevel,
    feed: TriageFeedSnapshot,
    config: ForYouScreenConfig,
    callbacks: ForYouDeckCallbacks,
    end: @Composable () -> Unit,
) {
    val newCodex = LocalNewCodexOpener.current
    // The picker and the Create Codex dialog hand the chosen Codex back to the deck, which saves it, so the card moves
    // on and Undo covers it like any other save.
    val deckCallbacks = remember(level, callbacks, newCodex) {
        val save = { post: Post -> { codex: ChosenCodex -> level.deck.decide(TriageChoice.Save(codex), post.id) } }
        TriageDeckCallbacks(
            onOpenPost = { posts, index -> posts.getOrNull(index)?.let { callbacks.onOpenPost(level, it.id, 0) } },
            onOpenSavePicker = { post -> callbacks.onPickCodex(post, save(post)) },
            onNewCodex = { post -> newCodex.openNewCodex { codex -> save(post)(codex) } },
            onClose = {},
        )
    }
    val feedShare = remember(feed.posts) { feedTagShare(feed.posts) }
    TriageDeckScreen(
        deck = level.deck,
        query = level.query,
        support = config.support,
        callbacks = deckCallbacks,
        // The screen's own bar sits above the deck and the grid alike.
        showTopBar = false,
        cardFooter = { post -> ForYouCardFooter(owner, level, post, feedShare, config.creatorBrowsingSources) },
        endContent = end,
    )
}

@Composable
private fun ForYouGrid(
    level: ForYouLevel,
    feed: TriageFeedSnapshot,
    config: ForYouScreenConfig,
    related: RelatedShelfBinding,
    callbacks: ForYouDeckCallbacks,
    onLongPress: (Post) -> Unit,
    end: @Composable () -> Unit,
) {
    // Every level keeps its own grid place while the Viewer or a deeper trail covers it, and a new one starts at the top.
    val retainKey = level.id
    val gridState = rememberRetainedFeedGridState(retainKey)
    ScrollToTopOnTabReselect(TopLevelDestination.ForYou) { gridState.animateScrollToItem(0) }
    DurationRouteEnvironmentEffect(gridState, config.durations.onEnvironmentChanged)
    // The owner already applied For You's filters, so the grid hides nothing itself.
    val view = rememberFeedView(retainKey, feed.posts, NO_FILTERS, config.durations, related.state)
    val demand = rememberFeedPageDemand(
        input = FeedPageDemandInput(
            contextKey = level.id,
            completedGeneration = feed.loaded.toLong(),
            canonicalCount = feed.posts.size,
            visibleCount = view.visiblePosts.size,
            canLoadMore = feed.canLoadMore,
            paging = feed.loadingMore,
            failed = feed.pageFailed,
            durationsBlockPaging = view.durations.blocksPaging,
        ),
        filters = view.filters,
        gridState = gridState,
        projection = view.related.projection,
        onLoadNextPage = level::loadNextPage,
    )
    when {
        view.related.projection.entries.isNotEmpty() -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // A later page that failed, above the posts as on a creator's page. The Mix says it in its status row.
            if (feed.pageFailed && level.fork != null) {
                Box(Modifier.padding(bottom = 8.dp)) { FeedFailureTile(LOAD_FAILED, onRetry = level::retryPage) }
            }
            FeedContinueLoading(demand)
            FeedGrid(
                view = view,
                state = gridState,
                footerMessage = pagedFeedFooterMessage(
                    resolvingDurations = false, hasVisibleResults = true, canLoadMore = feed.canLoadMore,
                    requestRunning = feed.loadingMore, failed = feed.pageFailed,
                ),
                modifier = Modifier.fillMaxSize().testTag(FOR_YOU_GRID_TAG),
                showPagingTile = feed.loadingMore,
                shelf = { FeedRelatedPostsShelf(related, view.related, config.support.environment, onLongPress) },
                post = { _, post ->
                    FeedPostCard(
                        post = post,
                        environment = config.support.environment,
                        onClick = { callbacks.onOpenPost(level, post.id, gridState.firstVisibleItemScrollOffset) },
                        onLongPress = { onLongPress(post) },
                        showWatchHistory = true,
                        showSourceBadge = true,
                    )
                },
            )
        }
        feed.loadingMore || feed.canLoadMore -> FeedLoadingState()
        else -> end()
    }
}

/**
 * What a level with nothing left says. [mix] is the Mix's state, or null on a trail; [empty] is whether the level
 * never had a post to show.
 */
@Composable
private fun ForYouEnd(
    mix: ForYouMixState?,
    empty: Boolean,
    onStartOver: () -> Unit,
    onGoToSearch: () -> Unit,
    onManageSources: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).testTag(FOR_YOU_END_TAG),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            mix == ForYouMixState.NoSources -> {
                FeedEmptyTile("No sources enabled")
                TextButton(onManageSources) { Text("Manage sources") }
            }
            mix == ForYouMixState.NoLanes -> {
                FeedEmptyTile("No recommendations yet")
                TextButton(onGoToSearch) { Text("Go to Search") }
            }
            mix == null -> FeedEmptyTile(if (empty) "No posts found" else "No more posts")
            else -> {
                FeedEmptyTile("No more posts")
                TextButton(onStartOver) { Text("Start over") }
            }
        }
    }
}

private val NO_FILTERS = SearchVisibilityFilters()
private const val LOAD_FAILED = "Couldn't load posts"

const val FOR_YOU_GRID_TAG = "for-you-grid"
const val FOR_YOU_END_TAG = "for-you-end"
