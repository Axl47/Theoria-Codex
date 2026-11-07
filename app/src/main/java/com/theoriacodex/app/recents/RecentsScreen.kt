package com.theoriacodex.app.recents

import com.theoriacodex.app.ui.components.rememberRetainedFeedGridState
import androidx.compose.foundation.layout.height
import com.theoriacodex.app.ui.components.POST_SHELF_CARD_WIDTH
import com.theoriacodex.app.ui.components.POST_SHELF_MEDIA_ASPECT_RATIO
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.MoreVert
import com.theoriacodex.app.ui.components.ActionMenuRow
import com.theoriacodex.app.ui.components.ActionMenuSheet
import com.theoriacodex.app.ui.components.AdaptivePostGrid
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.noMediaDurationStateForPost
import com.theoriacodex.app.media.observedMediaDurationMs
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.viewer.ViewerReturnColumn
import com.theoriacodex.app.viewer.ViewerReturnShelf
import com.theoriacodex.app.viewer.ViewerReturnShelfId
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.components.SingleChoiceMenu
import com.theoriacodex.app.ui.components.SourceSelector
import com.theoriacodex.app.ui.components.expandableControlSemantics
import com.theoriacodex.app.ui.components.FeedPostActionSheet
import com.theoriacodex.app.related.RelatedShelfBinding
import com.theoriacodex.app.viewer.ViewerReturnRequest
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.MAX_SAVED_SEARCH_NAME_LENGTH
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.app.search.rememberPostWatchEntry
import com.theoriacodex.app.search.relativeTimeLabel
import kotlinx.coroutines.flow.Flow

@Composable
internal fun RecentsScreen(
    watchedPosts: List<RecentPostEntry>,
    codexPosts: List<RecentPostEntry>,
    searches: List<RecentSearchEntry>,
    fypSearches: List<RecentSearchEntry>,
    activity: List<RecentActivityEntry>,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    durationStateForPost: (Post) -> Flow<MediaDurationState?> = noMediaDurationStateForPost,
    onDurationPostVisibilityChanged: (Post, Boolean) -> Unit = { _, _ -> },
    onDurationEnvironmentChanged: (Boolean, Boolean) -> Unit = { _, _ -> },
    onAuthoritativeDurationKnown: (Post, Long) -> Unit = { _, _ -> },
    likedPostIds: Set<PostId> = emptySet(),
    onToggleLike: ((Post) -> Unit)? = null,
    creatorBrowsingSources: Set<SourceKey> = emptySet(),
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    onRequestSaveToCodex: (Post) -> Unit,
    onSaveToDevice: (Post) -> Unit,
    onPostUrlCopied: (Post) -> Unit = {},
    onOpenCreatorProfile: (CreatorProfile) -> Unit,
    onOpenLegacyCreatorProfile: (Post) -> Unit,
    onAddIncludeTerm: (Post, SearchTerm) -> Boolean,
    onAddExcludeTerm: (Post, SearchTerm) -> Boolean,
    onRemoveIncludeTerm: (Post, SearchTerm) -> Unit,
    onRemoveExcludeTerm: (Post, SearchTerm) -> Unit,
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)? = null,
    onGoToSearch: () -> Unit,
    onOpenWatchedPost: (List<RecentPostEntry>, Int) -> Unit,
    onOpenCodexPost: (List<RecentPostEntry>, Int) -> Unit,
    onOpenSearch: (RecentSearchEntry) -> Unit,
    onOpenFypSearch: (RecentSearchEntry) -> Unit,
    onClear: (RecentsClearTarget) -> Unit,
    onSaveSearch: ((String, RecentSearchEntry) -> Unit)? = null,
    onStartOver: (Post, RecentPostSection) -> Unit = { _, _ -> },
    related: RelatedShelfBinding? = null,
    onShownPostsChanged: (key: String, posts: List<Post>) -> Unit = { _, _ -> },
) {
    var filter by rememberSaveable { mutableStateOf(RecentsFilter.ALL) }
    var searchFilter by rememberSaveable { mutableStateOf(RecentSearchFilter.ALL) }
    var showMenu by remember { mutableStateOf(false) }
    var selectedSource by rememberSaveable { mutableStateOf<SourceKey?>(null) }
    val visible = rememberVisibleRecents(watchedPosts, codexPosts, searches, fypSearches, activity, selectedSource)
    val sourceEmptyMessage = selectedSource?.let { "No recent activity from ${it.displayName()}." }
    val openWatchedPost: (Int) -> Unit = { onOpenWatchedPost(visible.watchedPosts, it) }
    val openCodexPost: (Int) -> Unit = { onOpenCodexPost(visible.codexPosts, it) }
    val now = remember(watchedPosts, codexPosts, searches, fypSearches, activity) { System.currentTimeMillis() }
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var selectedActionSection by remember { mutableStateOf(RecentPostSection.WATCHED) }
    var searchToPin by remember { mutableStateOf<RecentSearchEntry?>(null) }
    val onPinSearch = onSaveSearch?.let { { entry: RecentSearchEntry -> searchToPin = entry } }
    val cards = RecentsPostCards(pixivUgoiraClient, likedPostIds, durationStateForPost, onDurationPostVisibilityChanged,
        onDurationEnvironmentChanged, onAuthoritativeDurationKnown, onToggleLike)
    val relatedSlot = rememberRecentsRelated(related, filter, selectedSource, visible, cards, onShownPostsChanged) { selectedActionPost = it }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RecentsHeader(onOpenMenu = { showMenu = true }) {
            RecentsSectionSelector(filter, onSelected = { filter = it })
            RecentsSourceSelector(selectedSource, onSelected = { selectedSource = it })
        }

        RecentsTab(
            filter, searchFilter, { searchFilter = it }, visible, sourceEmptyMessage, now, cards, relatedSlot,
            { post, section ->
                selectedActionPost = post
                selectedActionSection = section
            },
            openWatchedPost, openCodexPost, onOpenSearch, onOpenFypSearch, onPinSearch,
        )
    }

    if (showMenu) {
        RecentsClearMenu(
            filter, selectedSource != null, { target -> recentsIsEmpty(target, watchedPosts, codexPosts, searches, fypSearches, activity) },
            onClear, onDismiss = { showMenu = false },
        )
    }

    selectedActionPost?.let { post ->
        FeedPostActionSheet(
            post, creatorBrowsingSources, { selectedActionPost = null }, onSaveToDevice, onRequestSaveToCodex, onOpenCreatorProfile,
            onOpenLegacyCreatorProfile, onGoToSearch, onPostUrlCopied, onAddIncludeTerm, onAddExcludeTerm, onRemoveIncludeTerm,
            onRemoveExcludeTerm, onFavoriteTagLongPress, tagVideoCountProvider, fetchTagVideoCounts,
            onStartOver = {
                onStartOver(post, selectedActionSection)
                selectedActionPost = null
            }.takeIf { maxOf(post.mediaCount ?: 1, post.media.size) > 1 },
            onShowRelated = related?.let { shelf -> { shelf.controller.show(post) } },
        )
    }

    searchToPin?.let { entry ->
        SavedSearchNameDialog(recentSearchPresentation(entry).title.take(MAX_SAVED_SEARCH_NAME_LENGTH), false, { searchToPin = null }) { name ->
            onSaveSearch?.invoke(name, entry)
            searchToPin = null
        }
    }
}

@Composable
private fun RecentsSourceSelector(selectedSource: SourceKey?, onSelected: (SourceKey?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    SourceSelector(
        selectedSource = selectedSource,
        availableSources = SourceKey.entries,
        allSourcesLabel = "All sources",
        accessibilityLabel = "Filter Recents by source",
        enabled = true,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        onSourceSelected = {
            onSelected(it)
            expanded = false
        },
    )
}

/** Picks which Recents section fills the screen; it sits beside the source selector to keep the header on one row. */
@Composable
private fun RecentsSectionSelector(filter: RecentsFilter, onSelected: (RecentsFilter) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(start = 8.dp, end = 4.dp),
            modifier = Modifier
                .semantics { contentDescription = RECENTS_SECTION_SELECTOR_LABEL }
                .expandableControlSemantics(
                    expanded = expanded,
                    description = "${if (expanded) "Expanded" else "Collapsed"}; ${filter.label} selected",
                    onExpandedChange = { expanded = it },
                ),
        ) {
            Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                Text(filter.label)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        SingleChoiceMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            options = RecentsFilter.entries,
            selected = filter,
            label = { it.label },
            onSelect = onSelected,
        )
    }
}

/** The body under the header, for whichever section is selected. */
@Composable
private fun RecentsTab(
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
            visible.activity, cards.pixivUgoiraClient, cards.likedPostIds, cards.durationStateForPost, cards.onDurationPostVisibilityChanged,
            cards.onAuthoritativeDurationKnown, cards.onToggleLike, onLongPress, related,
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

/** Recents after the source filter; everything the tabs show comes from here. */
internal class VisibleRecents(
    val watchedPosts: List<RecentPostEntry>,
    val codexPosts: List<RecentPostEntry>,
    val searches: List<RecentSearchEntry>,
    val fypSearches: List<RecentSearchEntry>,
    val activity: List<RecentActivityEntry>,
)

@Composable
private fun rememberVisibleRecents(
    watchedPosts: List<RecentPostEntry>,
    codexPosts: List<RecentPostEntry>,
    searches: List<RecentSearchEntry>,
    fypSearches: List<RecentSearchEntry>,
    activity: List<RecentActivityEntry>,
    source: SourceKey?,
): VisibleRecents = remember(watchedPosts, codexPosts, searches, fypSearches, activity, source) {
    VisibleRecents(
        filterRecentPostsBySource(watchedPosts, source),
        filterRecentPostsBySource(codexPosts, source),
        searches.filter { it.matchesSource(source) },
        fypSearches.filter { it.matchesSource(source) },
        activity.filter { it.matchesSource(source) },
    )
}

/** What each post tile in Recents shares, whichever tab shows it. */
internal class RecentsPostCards(
    val pixivUgoiraClient: PixivUgoiraClient?,
    val likedPostIds: Set<PostId>,
    val durationStateForPost: (Post) -> Flow<MediaDurationState?>,
    val onDurationPostVisibilityChanged: (Post, Boolean) -> Unit,
    val onDurationEnvironmentChanged: (Boolean, Boolean) -> Unit,
    val onAuthoritativeDurationKnown: (Post, Long) -> Unit,
    val onToggleLike: ((Post) -> Unit)?,
)

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
        pixivUgoiraClient = cards.pixivUgoiraClient,
        likedPostIds = cards.likedPostIds,
        durationStateForPost = cards.durationStateForPost,
        onDurationPostVisibilityChanged = cards.onDurationPostVisibilityChanged,
        onDurationEnvironmentChanged = cards.onDurationEnvironmentChanged,
        onAuthoritativeDurationKnown = cards.onAuthoritativeDurationKnown,
        onToggleLike = cards.onToggleLike,
        related = related,
        onOpenWatchedPost = onOpen,
        onLongPress = { onLongPress(it, section) },
        showMediaProgress = showMediaProgress,
        emptyMessage = emptyMessage,
    )
}

@Composable
private fun RecentsHeader(
    onOpenMenu: () -> Unit,
    selectors: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Recents", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        selectors()
        IconButton(onClick = onOpenMenu) { Icon(Icons.Default.MoreVert, "Recents actions") }
    }
}

/** Names exactly what will be cleared. Clearing a section always covers every source. */
@Composable
private fun RecentsClearMenu(
    filter: RecentsFilter,
    sourceFiltered: Boolean,
    isEmpty: (RecentsClearTarget) -> Boolean,
    onClear: (RecentsClearTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val suffix = if (sourceFiltered) " (all sources)" else ""
    val sectionTargets = when (filter) {
        RecentsFilter.ALL -> emptyList()
        RecentsFilter.WATCHED -> listOf("Clear Watched" to RecentsClearTarget.WATCHED)
        RecentsFilter.CODEX -> listOf("Clear Codex history" to RecentsClearTarget.CODEX)
        RecentsFilter.SEARCHES -> listOf(
            "Clear searches" to RecentsClearTarget.SEARCHES,
            "Clear For You searches" to RecentsClearTarget.FYP,
        )
    }
    val targets = sectionTargets + ("Clear all Recents" to RecentsClearTarget.ALL)
    ActionMenuSheet(onDismiss) {
        targets.forEach { (label, target) ->
            ActionMenuRow(
                icon = Icons.Default.Delete,
                label = label + suffix,
                enabled = !isEmpty(target),
                tint = MaterialTheme.colorScheme.error,
            ) {
                onDismiss()
                onClear(target)
            }
        }
    }
}

private fun recentsIsEmpty(
    target: RecentsClearTarget, watchedPosts: List<RecentPostEntry>, codexPosts: List<RecentPostEntry>,
    searches: List<RecentSearchEntry>, fypSearches: List<RecentSearchEntry>, activity: List<RecentActivityEntry>,
): Boolean = when (target) {
    RecentsClearTarget.WATCHED -> watchedPosts.isEmpty()
    RecentsClearTarget.CODEX -> codexPosts.isEmpty()
    RecentsClearTarget.FYP -> fypSearches.isEmpty()
    RecentsClearTarget.SEARCHES -> searches.isEmpty()
    RecentsClearTarget.ALL -> activity.isEmpty()
}

@Composable
private fun WatchedGrid(
    watchedPosts: List<RecentPostEntry>,
    now: Long,
    pixivUgoiraClient: PixivUgoiraClient?,
    likedPostIds: Set<PostId>,
    durationStateForPost: (Post) -> Flow<MediaDurationState?>,
    onDurationPostVisibilityChanged: (Post, Boolean) -> Unit,
    onDurationEnvironmentChanged: (Boolean, Boolean) -> Unit,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit,
    onToggleLike: ((Post) -> Unit)?,
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
    ScrollToTopOnTabReselect(TopLevelDestination.Recents) { gridState.animateScrollToItem(0) }
    DurationRouteEnvironmentEffect(gridState, onDurationEnvironmentChanged)
    val rows = remember(watchedPosts, now, related?.request) {
        recentsGridRows(watchedPosts, now).withRelatedShelf(related?.request, watchedPosts)
    }
    // Headings and the shelf hold a null so Viewer return still finds each post by its grid position.
    val postIds = remember(rows) { rows.map { (it as? RecentsGridRow.Post)?.entry?.post?.id } }
    val shelfIds = remember(rows, related?.request) {
        rows.map { row -> related?.request?.seed?.id?.takeIf { row == RecentsGridRow.RelatedShelf }?.let(ViewerReturnShelfId::Related) }
    }

    AdaptivePostGrid(postIds = postIds, state = gridState, modifier = Modifier.fillMaxSize(), shelfIds = shelfIds) {
        rows.forEach { row ->
            when (row) {
                RecentsGridRow.RelatedShelf ->
                    item(key = "related-shelf", span = StaggeredGridItemSpan.FullLine) { related?.content?.invoke() }
                is RecentsGridRow.Day ->
                    item(key = "day:${row.date}", span = StaggeredGridItemSpan.FullLine) { RecentsDayHeading(row.label) }
                is RecentsGridRow.Post -> {
                    val post = row.entry.post
                    item(key = "${post.id.source.name}:${post.id.sourcePostId}") {
                        RecentPostCard(
                            row.entry, now, pixivUgoiraClient, likedPostIds, durationStateForPost,
                            onDurationPostVisibilityChanged, onAuthoritativeDurationKnown, onToggleLike,
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
    pixivUgoiraClient: PixivUgoiraClient?,
    likedPostIds: Set<PostId>,
    durationStateForPost: (Post) -> Flow<MediaDurationState?>,
    onDurationPostVisibilityChanged: (Post, Boolean) -> Unit,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit,
    onToggleLike: ((Post) -> Unit)?,
    viewedMediaNumber: Int?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    mediaAspectRatio: Float? = null,
    showTitle: Boolean = true,
) {
    val post = entry.post
    SearchResultCard(
        post = post,
        mediaAspectRatio = mediaAspectRatio,
        showTitle = showTitle,
        pixivUgoiraClient = pixivUgoiraClient,
        acquiredDurationMs = observedMediaDurationMs(post, durationStateForPost),
        showSourceBadge = true,
        metadataLabel = relativeTimeLabel(
            now,
            rememberPostWatchEntry(post.id).value.position?.lastViewedAt
                ?: entry.viewedAtEpochMs,
        ),
        viewedMediaNumber = viewedMediaNumber,
        liked = post.id in likedPostIds,
        onToggleLike = onToggleLike?.let { toggle -> { toggle(post) } },
        onClick = onClick,
        onLongPress = onLongPress,
        onViewportChanged = { visible -> onDurationPostVisibilityChanged(post, visible) },
        onAuthoritativeDurationKnown = { durationMs -> onAuthoritativeDurationKnown(post, durationMs) },
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
        ScrollToTopOnTabReselect(TopLevelDestination.Recents) { list.animateScrollToItem(0) }
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
    pixivUgoiraClient: PixivUgoiraClient?,
    likedPostIds: Set<PostId>,
    durationStateForPost: (Post) -> Flow<MediaDurationState?>,
    onDurationPostVisibilityChanged: (Post, Boolean) -> Unit,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit,
    onToggleLike: ((Post) -> Unit)?,
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
    ScrollToTopOnTabReselect(TopLevelDestination.Recents) { list.animateScrollToItem(0) }
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
                is RecentsTimelineItem.Posts -> {
                val row = rememberLazyListState()
                val postIds = remember(item.entries) { item.entries.map { it.post.id } }
                ViewerReturnShelf(ViewerReturnShelfId.RecentsTimeline, postIds, row) {
                LazyRow(
                    state = row,
                    // The same square frame as other shelves keeps every card in a row the same height.
                    modifier = Modifier.height(POST_SHELF_CARD_WIDTH / POST_SHELF_MEDIA_ASPECT_RATIO),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(item.entries, key = { "${it.section}:${it.post.id.source.name}:${it.post.id.sourcePostId}" }) { entry ->
                        Box(Modifier.width(POST_SHELF_CARD_WIDTH)) {
                            RecentPostCard(
                                entry, now, pixivUgoiraClient, likedPostIds, durationStateForPost,
                                onDurationPostVisibilityChanged, onAuthoritativeDurationKnown, onToggleLike,
                                viewedMediaNumber = null,
                                onClick = {
                                    openRecentPost(entry, watchedPosts, codexPosts, onOpenWatchedPost, onOpenCodexPost)
                                },
                                onLongPress = { onLongPress(entry.post, entry.section) },
                                mediaAspectRatio = POST_SHELF_MEDIA_ASPECT_RATIO, showTitle = false,
                            )
                        }
                    }
                }
                }
                }
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

internal enum class RecentsFilter(val label: String) {
    ALL("All"),
    WATCHED("Watched"),
    CODEX("Codex"),
    SEARCHES("Searches"),
}

private const val RECENTS_SECTION_SELECTOR_LABEL = "Filter Recents by section"

private enum class RecentSearchFilter(val label: String) {
    ALL("All"),
    SEARCH("Search"),
    FOR_YOU("For You"),
}

internal data class RecentSearchPresentation(
    val title: String,
    val subtitle: String?,
)

internal fun recentSearchPresentation(entry: RecentSearchEntry): RecentSearchPresentation {
    val includes = entry.query.effectiveIncludeTermGroups.joinToString(" AND ") { group ->
        if (group.terms.size == 1) group.terms.single().value
        else group.terms.joinToString(prefix = "(", postfix = ")", separator = " OR ") { it.value }
    }
    val exclusions = entry.query.excludeTags.joinToString(" AND ") { tag -> "-$tag" }
    val title = listOf(includes, exclusions).filter(String::isNotBlank).joinToString(" AND ")
        .ifBlank { "No tags" }
    val sourceList = entry.sources.joinToString(", ") { source -> source.displayName() }
        .takeIf(String::isNotBlank)
    val subtitle = when (entry.kind) {
        RecentSearchKind.SOURCE -> null
        RecentSearchKind.UNIFIED -> sourceList
        RecentSearchKind.MULTI_SEARCH -> sourceList?.let { "Multi-Search · $it" } ?: "Multi-Search"
        RecentSearchKind.FYP -> sourceList
    }
    return RecentSearchPresentation(title, subtitle)
}

internal fun RecentSearchEntry.fypSeedBySource(): Map<SourceKey, List<String>> {
    return sourceTags.ifEmpty {
        sources.associateWith { query.includeTags }
    }
}

private fun filterRecentPostsBySource(posts: List<RecentPostEntry>, source: SourceKey?): List<RecentPostEntry> =
    posts.filter { source == null || it.post.id.source == source }
