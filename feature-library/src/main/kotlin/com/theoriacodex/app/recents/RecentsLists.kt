package com.theoriacodex.app.recents

import com.theoriacodex.app.ui.components.FeedPostCard
import com.theoriacodex.app.ui.components.rememberRetainedFeedGridState
import androidx.compose.foundation.layout.height
import com.theoriacodex.app.ui.components.POST_SHELF_CARD_WIDTH
import com.theoriacodex.app.ui.components.POST_SHELF_MEDIA_ASPECT_RATIO
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.layout.width
import com.theoriacodex.app.ui.components.AdaptivePostGrid
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.viewer.ViewerReturnColumn
import com.theoriacodex.app.viewer.ViewerReturnShelf
import com.theoriacodex.app.viewer.ViewerReturnShelfId
import com.theoriacodex.app.viewer.ViewerReturnRequest
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.app.search.rememberPostWatchEntry
import com.theoriacodex.app.search.relativeTimeLabel

/** The body under the header, for whichever section is selected. */
@Composable
internal fun RecentsTab(
    filter: RecentsFilter,
    searchFilter: RecentSearchFilter,
    onSearchFilterChange: (RecentSearchFilter) -> Unit,
    visible: VisibleRecents,
    sourceEmptyMessage: String?,
    now: Long,
    cards: RecentsPostCards,
    related: RecentsRelatedSlot?,
    onLongPress: (Post, RecentPostSection) -> Unit,
    openWatchedPost: (Int) -> Unit,
    openCodexPost: (Int) -> Unit,
    onOpenSearch: (RecentSearchEntry) -> Unit,
    onOpenFypSearch: (RecentSearchEntry) -> Unit,
    onPinSearch: ((RecentSearchEntry) -> Unit)?,
) {
    when (filter) {
        RecentsFilter.WATCHED -> RecentPostsGrid(
            visible.watchedPosts, now, cards, related, openWatchedPost, RecentPostSection.WATCHED, onLongPress,
            showMediaProgress = true,
            emptyMessage = sourceEmptyMessage ?: "Posts appear here after you open them in Viewer.",
        )
        RecentsFilter.CODEX -> RecentPostsGrid(
            visible.codexPosts, now, cards, related, openCodexPost, RecentPostSection.CODEX, onLongPress,
            showMediaProgress = false,
            emptyMessage = sourceEmptyMessage ?: "Posts appear here after you open them from Codex.",
        )
        RecentsFilter.SEARCHES -> SearchHistoryList(
            searches = when (searchFilter) {
                RecentSearchFilter.ALL -> (visible.searches + visible.fypSearches).sortedByDescending { it.searchedAtEpochMs }
                RecentSearchFilter.SEARCH -> visible.searches
                RecentSearchFilter.FOR_YOU -> visible.fypSearches
            },
            selectedFilter = searchFilter,
            onFilterSelected = onSearchFilterChange,
            emptyMessage = sourceEmptyMessage ?: "Searches appear here after you search or For You generates them.",
            now = now,
            onOpenSearch = { entry -> if (entry.kind == RecentSearchKind.FYP) onOpenFypSearch(entry) else onOpenSearch(entry) },
            onPinSearch = onPinSearch,
        )
        RecentsFilter.ALL -> ActivityList(
            visible.activity, cards, onLongPress, related,
            emptyMessage = sourceEmptyMessage ?: "Recent posts and applied searches appear here.",
            watchedPosts = visible.watchedPosts,
            codexPosts = visible.codexPosts,
            now = now,
            onOpenWatchedPost = openWatchedPost,
            onOpenCodexPost = openCodexPost,
            onOpenSearch = onOpenSearch,
            onOpenFypSearch = onOpenFypSearch,
            onPinSearch = onPinSearch,
        )
    }
}

@Composable
private fun RecentPostsGrid(
    posts: List<RecentPostEntry>,
    now: Long,
    cards: RecentsPostCards,
    related: RecentsRelatedSlot?,
    onOpen: (Int) -> Unit,
    section: RecentPostSection,
    onLongPress: (Post, RecentPostSection) -> Unit,
    showMediaProgress: Boolean,
    emptyMessage: String,
) {
    WatchedGrid(
        watchedPosts = posts,
        now = now,
        cards = cards,
        related = related,
        onOpenWatchedPost = onOpen,
        onLongPress = { onLongPress(it, section) },
        showMediaProgress = showMediaProgress,
        emptyMessage = emptyMessage,
    )
}

@Composable
private fun WatchedGrid(
    watchedPosts: List<RecentPostEntry>,
    now: Long,
    cards: RecentsPostCards,
    related: RecentsRelatedSlot?,
    onOpenWatchedPost: (Int) -> Unit,
    onLongPress: (Post) -> Unit,
    emptyMessage: String = "Posts appear here after you open them in Viewer.",
    showMediaProgress: Boolean = false,
) {
    if (watchedPosts.isEmpty()) {
        EmptyRecentState(emptyMessage)
        return
    }
    val gridState = rememberRetainedFeedGridState("recents")
    ScrollToTopOnTabReselect(TopLevelDestination.Library) { gridState.animateScrollToItem(0) }
    DurationRouteEnvironmentEffect(gridState, cards.onEnvironmentChanged)
    val rows = remember(watchedPosts, now, related?.request) {
        recentsGridRows(watchedPosts, now).withRelatedShelf(related?.request, watchedPosts)
    }
    // Headings and the shelf hold a null so Viewer return still finds each post by its grid position.
    val posts = remember(rows) { rows.map { (it as? RecentsGridRow.Post)?.entry?.post } }
    val shelfIds = remember(rows, related?.request) {
        rows.map { row -> related?.request?.seed?.id?.takeIf { row == RecentsGridRow.RelatedShelf }?.let(ViewerReturnShelfId::Related) }
    }

    AdaptivePostGrid(posts = posts, state = gridState, modifier = Modifier.fillMaxSize(), shelfIds = shelfIds) {
        rows.forEach { row ->
            when (row) {
                RecentsGridRow.RelatedShelf ->
                    item(key = "related-shelf", span = StaggeredGridItemSpan.FullLine) { related?.content?.invoke() }
                is RecentsGridRow.Day ->
                    item(key = "day:${row.date}", span = StaggeredGridItemSpan.FullLine) { RecentsDayHeading(row.label) }
                is RecentsGridRow.Post -> {
                    val post = row.entry.post
                    item(key = "${post.id.source.id}:${post.id.sourcePostId}") {
                        RecentPostCard(
                            row.entry, now, cards,
                            viewedMediaNumber = row.entry.maxViewedMediaNumber.takeIf { showMediaProgress },
                            onClick = { onOpenWatchedPost(row.index) },
                            onLongPress = { onLongPress(post) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentsDayHeading(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** A real feed card, so animated posts in Recents autoplay like everywhere else. */
@Composable
private fun RecentPostCard(
    entry: RecentPostEntry,
    now: Long,
    cards: RecentsPostCards,
    viewedMediaNumber: Int?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    mediaAspectRatio: Float? = null,
    showTitle: Boolean = true,
) {
    val post = entry.post
    FeedPostCard(
        post = post,
        environment = cards.environment,
        onClick = onClick,
        onLongPress = onLongPress,
        showSourceBadge = true,
        showTitle = showTitle,
        metadataLabel = relativeTimeLabel(
            now,
            rememberPostWatchEntry(post.id).value.position?.lastViewedAt
                ?: entry.viewedAtEpochMs,
        ),
        viewedMediaNumber = viewedMediaNumber,
        mediaAspectRatio = mediaAspectRatio,
    )
}

@Composable
private fun SearchHistoryList(
    searches: List<RecentSearchEntry>,
    selectedFilter: RecentSearchFilter,
    onFilterSelected: (RecentSearchFilter) -> Unit,
    now: Long,
    onOpenSearch: (RecentSearchEntry) -> Unit,
    onPinSearch: ((RecentSearchEntry) -> Unit)?,
    emptyMessage: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecentSearchFilter.entries.forEach { option ->
                FilterChip(
                    selected = selectedFilter == option,
                    onClick = { onFilterSelected(option) },
                    label = { Text(option.label) },
                )
            }
        }
        if (searches.isEmpty()) {
            EmptyRecentState(emptyMessage)
            return@Column
        }
        val days = remember(searches, now) { searches.groupBy { recentsDay(it.searchedAtEpochMs) } }
        val list = rememberLazyListState()
        ScrollToTopOnTabReselect(TopLevelDestination.Library) { list.animateScrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            days.forEach { (day, entries) ->
                item(key = "day:$day") { RecentsDayHeading(recentsDayLabel(entries.first().searchedAtEpochMs, now)) }
                itemsIndexed(entries, key = { _, entry -> "${entry.kind}:${entry.queryHash}" }) { index, entry ->
                    // Dividers start under the text, past the source mark.
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    RecentSearchRow(entry, now, onClick = { onOpenSearch(entry) }, onPin = onPinSearch?.let { pin -> { pin(entry) } })
                }
            }
        }
    }
}

@Composable
private fun ActivityList(
    activity: List<RecentActivityEntry>,
    cards: RecentsPostCards,
    onLongPress: (Post, RecentPostSection) -> Unit,
    related: RecentsRelatedSlot?,
    watchedPosts: List<RecentPostEntry>,
    codexPosts: List<RecentPostEntry>,
    now: Long,
    onOpenWatchedPost: (Int) -> Unit,
    onOpenCodexPost: (Int) -> Unit,
    onOpenSearch: (RecentSearchEntry) -> Unit,
    onOpenFypSearch: (RecentSearchEntry) -> Unit,
    onPinSearch: ((RecentSearchEntry) -> Unit)?,
    emptyMessage: String,
) {
    if (activity.isEmpty()) {
        EmptyRecentState(emptyMessage)
        return
    }
    val timeline = remember(activity, now, related?.request) { recentsTimeline(activity, now).withRelatedShelf(related?.request) }
    val list = rememberLazyListState()
    ScrollToTopOnTabReselect(TopLevelDestination.Library) { list.animateScrollToItem(0) }
    val shelfSeed = related?.request?.seed?.id
    ViewerReturnColumn(list, timeline, { request -> timeline.indexOfFirst { it.holdsReturn(request, shelfSeed) } }) {
    LazyColumn(modifier = Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(
            timeline,
            key = { index, item -> timelineKey(index, item) },
            // Headings, post rows, search rows, and the shelf only reuse compositions of their own kind.
            contentType = { _, item -> item.javaClass },
        ) { _, item ->
            when (item) {
                is RecentsTimelineItem.Day -> RecentsDayHeading(item.label)
                RecentsTimelineItem.RelatedShelf -> related?.content?.invoke()
                is RecentsTimelineItem.Posts -> RecentPostsRow(
                    item.entries, now, cards, onLongPress,
                    onOpen = { entry -> openRecentPost(entry, watchedPosts, codexPosts, onOpenWatchedPost, onOpenCodexPost) },
                )
                is RecentsTimelineItem.Searches -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(item.entries, key = { entry -> "${entry.kind}:${entry.queryHash}" }) { entry ->
                        RecentSearchTile(
                            entry = entry,
                            now = now,
                            onClick = { if (entry.kind == RecentSearchKind.FYP) onOpenFypSearch(entry) else onOpenSearch(entry) },
                            onPin = onPinSearch?.let { pin -> { pin(entry) } },
                        )
                    }
                }
            }
        }
    }
    }
}

/** One day's posts as a horizontal shelf, newest first. */
@Composable
private fun RecentPostsRow(
    entries: List<RecentPostEntry>,
    now: Long,
    cards: RecentsPostCards,
    onLongPress: (Post, RecentPostSection) -> Unit,
    onOpen: (RecentPostEntry) -> Unit,
) {
    val row = rememberLazyListState()
    val postIds = remember(entries) { entries.map { it.post.id } }
    // Rows are newest first, but a list keeps its place by key, so a post viewed since would
    // land off-screen to the left. Show the newest whenever it changes.
    LaunchedEffect(postIds.firstOrNull()) { if (row.firstVisibleItemIndex != 0) row.scrollToItem(0) }
    ViewerReturnShelf(ViewerReturnShelfId.RecentsTimeline, postIds, row) {
        LazyRow(
            state = row,
            // The same square frame as other shelves keeps every card in a row the same height.
            modifier = Modifier.height(POST_SHELF_CARD_WIDTH / POST_SHELF_MEDIA_ASPECT_RATIO),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { "${it.section}:${it.post.id.source.id}:${it.post.id.sourcePostId}" }) { entry ->
                Box(Modifier.width(POST_SHELF_CARD_WIDTH)) {
                    RecentPostCard(
                        entry, now, cards,
                        viewedMediaNumber = null,
                        onClick = { onOpen(entry) },
                        onLongPress = { onLongPress(entry.post, entry.section) },
                        mediaAspectRatio = POST_SHELF_MEDIA_ASPECT_RATIO, showTitle = false,
                    )
                }
            }
        }
    }
}

/** Whether a Viewer return lands on this row: the related shelf by its seed, otherwise the row holding the post. */
private fun RecentsTimelineItem.holdsReturn(request: ViewerReturnRequest, shelfSeed: PostId?): Boolean = when (this) {
    RecentsTimelineItem.RelatedShelf -> shelfSeed != null && request.shelfId == ViewerReturnShelfId.Related(shelfSeed)
    is RecentsTimelineItem.Posts -> request.shelfId !is ViewerReturnShelfId.Related && entries.any { it.post.id == request.postId }
    else -> false
}

private fun timelineKey(index: Int, item: RecentsTimelineItem): String = when (item) {
    is RecentsTimelineItem.Day -> "day:${item.date}"
    is RecentsTimelineItem.Searches -> "searches:${item.date}"
    is RecentsTimelineItem.Posts -> "posts:${item.date}"
    RecentsTimelineItem.RelatedShelf -> "related-shelf"
}

private fun openRecentPost(
    postEntry: RecentPostEntry,
    watchedPosts: List<RecentPostEntry>,
    codexPosts: List<RecentPostEntry>,
    onOpenWatchedPost: (Int) -> Unit,
    onOpenCodexPost: (Int) -> Unit,
) {
    val sourcePosts = when (postEntry.section) {
        RecentPostSection.WATCHED -> watchedPosts
        RecentPostSection.CODEX -> codexPosts
        RecentPostSection.FYP -> return
    }
    val index = sourcePosts.indexOfFirst { candidate -> candidate.post.id == postEntry.post.id }
    if (index < 0) return
    when (postEntry.section) {
        RecentPostSection.WATCHED -> onOpenWatchedPost(index)
        RecentPostSection.CODEX -> onOpenCodexPost(index)
        RecentPostSection.FYP -> Unit
    }
}

@Composable
private fun EmptyRecentState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = message,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal enum class RecentSearchFilter(val label: String) {
    ALL("All"),
    SEARCH("Search"),
    FOR_YOU("For You"),
}
