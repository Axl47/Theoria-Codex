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
import com.theoriacodex.app.ui.components.EmptyState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.app.search.rememberPostWatchEntry
import com.theoriacodex.app.search.relativeTimeLabel
import com.theoriacodex.app.ui.undo.UndoRow

/**
 * What History's rows and tiles can do to their own entry: save a search in place ([saving]), open a search's
 * long-press menu ([onSearchMenu]), or be removed ([onRemoveSearch], [onRemovePost]). [removedSearch] keeps its
 * row or tile in place to show the Undo offer.
 */
internal class RecentsRowActions(
    val saving: RecentSearchSaving?,
    val removedSearch: RecentSearchEntry?,
    val onRemoveSearch: ((RecentSearchEntry) -> Unit)?,
    val onRemovePost: ((RecentPostEntry) -> Unit)?,
    val onSearchMenu: ((RecentSearchEntry) -> Unit)?,
)

/** The body under the header, for whichever section is selected. */
@Composable
internal fun RecentsTab(
    filter: RecentsFilter,
    visible: VisibleRecents,
    now: Long,
    cards: RecentsPostCards,
    related: RecentsRelatedSlot?,
    onLongPress: (Post, RecentPostSection) -> Unit,
    openWatchedPost: (Int) -> Unit,
    openCodexPost: (Int) -> Unit,
    onOpenSearch: (RecentSearchEntry) -> Unit,
    rows: RecentsRowActions,
) {
    when (filter) {
        RecentsFilter.WATCHED -> RecentPostsGrid(
            visible.watchedPosts, now, cards, related, openWatchedPost, RecentPostSection.WATCHED, onLongPress, rows.onRemovePost,
            showMediaProgress = true,
            emptyMessage = "No watched posts",
        )
        RecentsFilter.CODEX -> RecentPostsGrid(
            visible.codexPosts, now, cards, related, openCodexPost, RecentPostSection.CODEX, onLongPress, rows.onRemovePost,
            showMediaProgress = false,
            emptyMessage = "No Codex activity",
        )
        RecentsFilter.SEARCHES -> SearchHistoryList(
            searches = visible.searches,
            shows = visible::shows,
            now = now,
            onOpenSearch = onOpenSearch,
            rows = rows,
        )
        RecentsFilter.ALL -> ActivityList(
            visible, cards, onLongPress, related,
            now = now,
            onOpenWatchedPost = openWatchedPost,
            onOpenCodexPost = openCodexPost,
            onOpenSearch = onOpenSearch,
            rows = rows,
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
    onRemove: ((RecentPostEntry) -> Unit)?,
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
        onRemove = onRemove,
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
    onRemove: ((RecentPostEntry) -> Unit)?,
    emptyMessage: String,
    showMediaProgress: Boolean,
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
                        RemovableEntry(onRemove?.let { remove -> { remove(row.entry) } }) { removeAction ->
                            RecentPostCard(
                                row.entry, now, cards, removeAction,
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
    modifier: Modifier,
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
        modifier = modifier,
    )
}

@Composable
private fun SearchHistoryList(
    searches: List<RecentSearchEntry>,
    now: Long,
    onOpenSearch: (RecentSearchEntry) -> Unit,
    rows: RecentsRowActions,
    shows: (RecentSearchEntry) -> Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val removed = rows.removedSearch
        val shown = remember(searches, removed, shows) { searches.withRemovedInPlace(removed?.takeIf(shows)) }
        if (shown.isEmpty()) {
            EmptyRecentState("No searches")
            return@Column
        }
        val days = remember(shown, now) { shown.groupBy { recentsDay(it.searchedAtEpochMs) } }
        val list = rememberLazyListState()
        ScrollToTopOnTabReselect(TopLevelDestination.Library) { list.animateScrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            days.forEach { (day, entries) ->
                item(key = "day:$day") { RecentsDayHeading(recentsDayLabel(entries.first().searchedAtEpochMs, now)) }
                itemsIndexed(entries, key = { _, entry -> "${entry.kind}:${entry.queryHash}" }) { index, entry ->
                    // Dividers start under the text, past the source mark.
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    if (entry == removed) {
                        UndoRow(recentSearchRemovalAnchor(entry), Modifier.padding(vertical = 4.dp))
                    } else {
                        RemovableEntry(rows.onRemoveSearch?.let { remove -> { remove(entry) } }) { removeAction ->
                            RecentSearchRow(
                                entry, now, onClick = { onOpenSearch(entry) }, onLongPress = rows.onSearchMenu?.let { { it(entry) } },
                                saving = rows.saving, modifier = removeAction,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The All view: each day's searches as a row of tiles above a shelf of its posts. Both rows scroll sideways, so
 * their entries are removed from the post menu or by TalkBack rather than by a swipe; a removed search keeps its
 * tile's place for the Undo offer.
 */
@Composable
private fun ActivityList(
    visible: VisibleRecents,
    cards: RecentsPostCards,
    onLongPress: (Post, RecentPostSection) -> Unit,
    related: RecentsRelatedSlot?,
    now: Long,
    onOpenWatchedPost: (Int) -> Unit,
    onOpenCodexPost: (Int) -> Unit,
    onOpenSearch: (RecentSearchEntry) -> Unit,
    rows: RecentsRowActions,
) {
    val removed = rows.removedSearch?.takeIf(visible::shows)
    val activity = remember(visible, removed) { visible.activity.withRemovedInPlace(removed) }
    if (activity.isEmpty()) {
        EmptyRecentState("No history")
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
                    item.entries, now, cards, onLongPress, rows.onRemovePost,
                    onOpen = { entry -> openRecentPost(entry, visible.watchedPosts, visible.codexPosts, onOpenWatchedPost, onOpenCodexPost) },
                )
                is RecentsTimelineItem.Searches -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(item.entries, key = { entry -> "${entry.kind}:${entry.queryHash}" }) { entry ->
                        if (entry == removed) {
                            UndoRow(recentSearchRemovalAnchor(entry), Modifier.width(RECENT_SEARCH_TILE_MAX_WIDTH))
                        } else {
                            RecentSearchTile(
                                entry, now, onClick = { onOpenSearch(entry) }, onLongPress = rows.onSearchMenu?.let { { it(entry) } },
                                saving = rows.saving,
                                modifier = historyRemoval(rows.onRemoveSearch?.let { remove -> { remove(entry) } }),
                            )
                        }
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
    onRemove: ((RecentPostEntry) -> Unit)?,
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
                        entry, now, cards, historyRemoval(onRemove?.let { remove -> { remove(entry) } }),
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
    }
    val index = sourcePosts.indexOfFirst { candidate -> candidate.post.id == postEntry.post.id }
    if (index < 0) return
    when (postEntry.section) {
        RecentPostSection.WATCHED -> onOpenWatchedPost(index)
        RecentPostSection.CODEX -> onOpenCodexPost(index)
    }
}

/**
 * Swipe-left removal for one History entry, or just [content] when it cannot be removed here. [content] applies the
 * modifier it receives to its clickable node, which carries the removal for TalkBack.
 */
@Composable
private fun RemovableEntry(onRemove: (() -> Unit)?, content: @Composable (Modifier) -> Unit) {
    if (onRemove == null) content(Modifier) else SwipeToRemove(REMOVE_FROM_HISTORY, onRemove, content = content)
}

/** TalkBack's removal alone, for an entry in a sideways-scrolling row, where a swipe would fight the row's scroll. */
private fun historyRemoval(onRemove: (() -> Unit)?): Modifier =
    onRemove?.let { Modifier.removeAction(REMOVE_FROM_HISTORY, it) } ?: Modifier

/**
 * [removed] back in its time order while its row shows the Undo offer, unless it is already restored. Callers pass
 * null when the current filters would not show it.
 */
internal fun List<RecentSearchEntry>.withRemovedInPlace(removed: RecentSearchEntry?): List<RecentSearchEntry> =
    if (removed == null || any { it.queryHash == removed.queryHash }) this
    else (this + removed).sortedByDescending { it.searchedAtEpochMs }

/** The All view's activity with [removed] back in its time order, as [withRemovedInPlace] does for search lists. */
@JvmName("activityWithRemovedInPlace")
internal fun List<RecentActivityEntry>.withRemovedInPlace(removed: RecentSearchEntry?): List<RecentActivityEntry> =
    if (removed == null || any { it is RecentActivityEntry.Search && it.entry.queryHash == removed.queryHash }) this
    else (this + RecentActivityEntry.Search(removed)).sortedByDescending { it.occurredAtEpochMs }

internal const val REMOVE_FROM_HISTORY = "Remove from History"

@Composable
private fun EmptyRecentState(message: String) {
    EmptyState(message, Modifier.fillMaxSize())
}
