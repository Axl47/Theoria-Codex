package com.theoriacodex.app.recents

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import com.theoriacodex.app.source.SourceIcon
import com.theoriacodex.app.ui.components.ActionMenuRow
import com.theoriacodex.app.ui.components.ActionMenuSheet
import com.theoriacodex.app.ui.components.AdaptivePostGrid
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.noMediaDurationStateForPost
import com.theoriacodex.app.media.observedMediaDurationMs
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.ui.components.SourceSelector
import com.theoriacodex.app.ui.components.PostActionSheet
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
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.app.search.relativeTimeLabel
import kotlinx.coroutines.flow.Flow

@Composable
fun RecentsScreen(
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
) {
    var filter by rememberSaveable { mutableStateOf(RecentsFilter.ALL) }
    var searchFilter by rememberSaveable { mutableStateOf(RecentSearchFilter.ALL) }
    var showMenu by remember { mutableStateOf(false) }
    var selectedSource by rememberSaveable { mutableStateOf<SourceKey?>(null) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    val visibleWatchedPosts = remember(watchedPosts, selectedSource) {
        watchedPosts.filter { selectedSource == null || it.post.id.source == selectedSource }
    }
    val visibleCodexPosts = remember(codexPosts, selectedSource) {
        codexPosts.filter { selectedSource == null || it.post.id.source == selectedSource }
    }
    val visibleSearches = remember(searches, selectedSource) { searches.filter { it.matchesSource(selectedSource) } }
    val visibleFypSearches = remember(fypSearches, selectedSource) { fypSearches.filter { it.matchesSource(selectedSource) } }
    val visibleActivity = remember(activity, selectedSource) { activity.filter { it.matchesSource(selectedSource) } }
    val sourceEmptyMessage = selectedSource?.let { "No recent activity from ${it.displayName()}." }
    val openWatchedPost: (Int) -> Unit = { onOpenWatchedPost(visibleWatchedPosts, it) }
    val openCodexPost: (Int) -> Unit = { onOpenCodexPost(visibleCodexPosts, it) }
    val now = remember(watchedPosts, codexPosts, searches, fypSearches, activity) { System.currentTimeMillis() }
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var selectedActionSection by remember { mutableStateOf(RecentPostSection.WATCHED) }
    var searchToPin by remember { mutableStateOf<RecentSearchEntry?>(null) }
    val onPinSearch: ((RecentSearchEntry) -> Unit)? = onSaveSearch?.let {
        { entry: RecentSearchEntry -> searchToPin = entry }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RecentsHeader(
            onOpenMenu = { showMenu = true },
            sourceSelector = {
                SourceSelector(
                    selectedSource = selectedSource,
                    availableSources = SourceKey.entries,
                    allSourcesLabel = "All sources",
                    accessibilityLabel = "Filter Recents by source",
                    iconTestTag = "Recents source icon",
                    enabled = true,
                    expanded = sourceMenuExpanded,
                    onExpandedChange = { sourceMenuExpanded = it },
                    onSourceSelected = {
                        selectedSource = it
                        sourceMenuExpanded = false
                    },
                )
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecentsFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option.label) },
                )
            }
        }

        when (filter) {
            RecentsFilter.WATCHED -> WatchedGrid(
                watchedPosts = visibleWatchedPosts,
                now = now,
                pixivUgoiraClient = pixivUgoiraClient,
                likedPostIds = likedPostIds,
                durationStateForPost = durationStateForPost,
                onDurationPostVisibilityChanged = onDurationPostVisibilityChanged,
                onDurationEnvironmentChanged = onDurationEnvironmentChanged,
                onAuthoritativeDurationKnown = onAuthoritativeDurationKnown,
                onToggleLike = onToggleLike,
                onOpenWatchedPost = openWatchedPost,
                onLongPress = {
                    selectedActionPost = it
                    selectedActionSection = RecentPostSection.WATCHED
                },
                showMediaProgress = true,
                emptyMessage = sourceEmptyMessage ?: "Posts appear here after you open them in Viewer.",
            )

            RecentsFilter.CODEX -> WatchedGrid(
                watchedPosts = visibleCodexPosts,
                now = now,
                pixivUgoiraClient = pixivUgoiraClient,
                likedPostIds = likedPostIds,
                durationStateForPost = durationStateForPost,
                onDurationPostVisibilityChanged = onDurationPostVisibilityChanged,
                onDurationEnvironmentChanged = onDurationEnvironmentChanged,
                onAuthoritativeDurationKnown = onAuthoritativeDurationKnown,
                onToggleLike = onToggleLike,
                onOpenWatchedPost = openCodexPost,
                onLongPress = {
                    selectedActionPost = it
                    selectedActionSection = RecentPostSection.CODEX
                },
                emptyMessage = sourceEmptyMessage ?: "Posts appear here after you open them from Codex.",
            )

            RecentsFilter.SEARCHES -> SearchHistoryList(
                searches = when (searchFilter) {
                    RecentSearchFilter.ALL -> (visibleSearches + visibleFypSearches).sortedByDescending { it.searchedAtEpochMs }
                    RecentSearchFilter.SEARCH -> visibleSearches
                    RecentSearchFilter.FOR_YOU -> visibleFypSearches
                },
                selectedFilter = searchFilter,
                onFilterSelected = { searchFilter = it },
                emptyMessage = sourceEmptyMessage ?: "Searches appear here after you search or For You generates them.",
                now = now,
                onOpenSearch = { entry ->
                    if (entry.kind == RecentSearchKind.FYP) onOpenFypSearch(entry) else onOpenSearch(entry)
                },
                onPinSearch = onPinSearch,
            )

            RecentsFilter.ALL -> ActivityList(
                activity = visibleActivity,
                pixivUgoiraClient = pixivUgoiraClient,
                likedPostIds = likedPostIds,
                durationStateForPost = durationStateForPost,
                onDurationPostVisibilityChanged = onDurationPostVisibilityChanged,
                onAuthoritativeDurationKnown = onAuthoritativeDurationKnown,
                onToggleLike = onToggleLike,
                onLongPress = { post, section ->
                    selectedActionPost = post
                    selectedActionSection = section
                },
                emptyMessage = sourceEmptyMessage ?: "Recent posts and applied searches appear here.",
                watchedPosts = visibleWatchedPosts,
                codexPosts = visibleCodexPosts,
                now = now,
                onOpenWatchedPost = openWatchedPost,
                onOpenCodexPost = openCodexPost,
                onOpenSearch = onOpenSearch,
                onOpenFypSearch = onOpenFypSearch,
                onPinSearch = onPinSearch,
            )
        }
    }

    if (showMenu) {
        RecentsClearMenu(
            filter = filter,
            sourceFiltered = selectedSource != null,
            isEmpty = { target -> recentsIsEmpty(target, watchedPosts, codexPosts, searches, fypSearches, activity) },
            onClear = onClear,
            onDismiss = { showMenu = false },
        )
    }

    selectedActionPost?.let { post ->
        PostActionSheet(
            post = post,
            creatorBrowsingSources = creatorBrowsingSources,
            onDismiss = { selectedActionPost = null },
            onSaveToDevice = { onSaveToDevice(post) },
            onSaveToCodex = { onRequestSaveToCodex(post) },
            onOpenCreatorProfile = onOpenCreatorProfile,
            onOpenLegacyCreatorProfile = { onOpenLegacyCreatorProfile(post) },
            onGoToSearch = onGoToSearch,
            onPostUrlCopied = onPostUrlCopied,
            onStartOver = if (maxOf(post.mediaCount ?: 1, post.media.size) > 1) {
                {
                    onStartOver(post, selectedActionSection)
                    selectedActionPost = null
                }
            } else null,
            tagContent = {
                PostTagActionSection(
                    post = post,
                    tagVideoCountProvider = tagVideoCountProvider,
                    fetchTagVideoCounts = fetchTagVideoCounts,
                    onAddIncludeTerm = { term -> onAddIncludeTerm(post, term) },
                    onAddExcludeTerm = { term -> onAddExcludeTerm(post, term) },
                    onRemoveIncludeTerm = { term -> onRemoveIncludeTerm(post, term) },
                    onRemoveExcludeTerm = { term -> onRemoveExcludeTerm(post, term) },
                    onFavoriteTagLongPress = onFavoriteTagLongPress,
                )
            },
        )
    }

    searchToPin?.let { entry ->
        SavedSearchNameDialog(
            initialName = recentSearchPresentation(entry).title.take(MAX_SAVED_SEARCH_NAME_LENGTH),
            renaming = false,
            onDismiss = { searchToPin = null },
            onConfirm = { name ->
                onSaveSearch?.invoke(name, entry)
                searchToPin = null
            },
        )
    }
}

@Composable
private fun RecentsHeader(
    onOpenMenu: () -> Unit,
    sourceSelector: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Recents", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        sourceSelector()
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
    onOpenWatchedPost: (Int) -> Unit,
    onLongPress: (Post) -> Unit,
    emptyMessage: String = "Posts appear here after you open them in Viewer.",
    showMediaProgress: Boolean = false,
) {
    if (watchedPosts.isEmpty()) {
        EmptyRecentState(emptyMessage)
        return
    }
    val gridState = rememberLazyStaggeredGridState()
    DurationRouteEnvironmentEffect(gridState, onDurationEnvironmentChanged)
    val rows = remember(watchedPosts, now) { recentsGridRows(watchedPosts, now) }
    // Headings hold a null so Viewer return still finds each post by its grid position.
    val postIds = remember(rows) { rows.map { (it as? RecentsGridRow.Post)?.entry?.post?.id } }

    AdaptivePostGrid(postIds = postIds, state = gridState, modifier = Modifier.fillMaxSize()) {
        rows.forEach { row ->
            when (row) {
                is RecentsGridRow.Day ->
                    item(key = "day:${row.label}", span = StaggeredGridItemSpan.FullLine) { RecentsDayHeading(row.label) }
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
) {
    val post = entry.post
    SearchResultCard(
        post = post,
        pixivUgoiraClient = pixivUgoiraClient,
        acquiredDurationMs = observedMediaDurationMs(post, durationStateForPost),
        showSourceBadge = true,
        metadataLabel = relativeTimeLabel(
            now,
            com.theoriacodex.app.search.LocalPostWatchPresentation.current.positions[post.id]?.lastViewedAt
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
        val days = remember(searches, now) { searches.groupBy { recentsDayLabel(it.searchedAtEpochMs, now) } }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEach { (day, entries) ->
                item(key = "day:$day") { RecentsDayHeading(day) }
                items(entries, key = { entry -> "${entry.kind}:${entry.queryHash}" }) { entry ->
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
    val timeline = remember(activity, now) { recentsTimeline(activity, now) }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(timeline, key = { index, item -> timelineKey(index, item) }) { _, item ->
            when (item) {
                is RecentsTimelineItem.Day -> RecentsDayHeading(item.label)
                is RecentsTimelineItem.Posts -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(item.entries, key = { "${it.section}:${it.post.id.source.name}:${it.post.id.sourcePostId}" }) { entry ->
                        Box(Modifier.width(TIMELINE_CARD_WIDTH)) {
                            RecentPostCard(
                                entry, now, pixivUgoiraClient, likedPostIds, durationStateForPost,
                                onDurationPostVisibilityChanged, onAuthoritativeDurationKnown, onToggleLike,
                                viewedMediaNumber = null,
                                onClick = {
                                    openRecentPost(entry, watchedPosts, codexPosts, onOpenWatchedPost, onOpenCodexPost)
                                },
                                onLongPress = { onLongPress(entry.post, entry.section) },
                            )
                        }
                    }
                }
                is RecentsTimelineItem.Search -> RecentSearchRow(
                    entry = item.entry,
                    now = now,
                    onClick = {
                        if (item.entry.kind == RecentSearchKind.FYP) onOpenFypSearch(item.entry) else onOpenSearch(item.entry)
                    },
                    onPin = onPinSearch?.let { pin -> { pin(item.entry) } },
                )
            }
        }
    }
}

private fun timelineKey(index: Int, item: RecentsTimelineItem): String = when (item) {
    is RecentsTimelineItem.Day -> "day:${item.label}"
    is RecentsTimelineItem.Posts -> "posts:$index:${item.entries.first().viewedAtEpochMs}"
    is RecentsTimelineItem.Search -> "search:${item.entry.queryHash}:${item.entry.searchedAtEpochMs}"
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
private fun RecentSearchRow(
    entry: RecentSearchEntry,
    now: Long,
    onClick: () -> Unit,
    onPin: (() -> Unit)?,
) {
    val source = when (entry.kind) {
        RecentSearchKind.SOURCE -> (entry.query.mode as? QueryMode.Source)?.source
        RecentSearchKind.FYP -> entry.sources.singleOrNull()
        else -> null
    }
    val context = listOfNotNull(
        "For You".takeIf { entry.kind == RecentSearchKind.FYP },
        recentSearchPresentation(entry).subtitle,
        relativeTimeLabel(now, entry.searchedAtEpochMs),
    ).joinToString(" · ")
    Card(
        modifier = Modifier
            .testTag("Recent search:${entry.queryHash}")
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClickLabel = "Pin search", onLongClick = onPin),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (source != null) {
                SourceIcon(source)
            } else {
                Icon(Icons.Default.Search, if (entry.kind == RecentSearchKind.MULTI_SEARCH) "Multi-Search" else "Unified search")
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    recentSearchRowTitle(entry.query),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    context,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            onPin?.let { pin ->
                IconButton(onClick = pin) { Icon(Icons.Default.PushPin, "Pin search", tint = MaterialTheme.colorScheme.primary) }
            }
        }
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

private enum class RecentsFilter(val label: String) {
    ALL("All"),
    WATCHED("Watched"),
    CODEX("Codex"),
    SEARCHES("Searches"),
}

private enum class RecentSearchFilter(val label: String) {
    ALL("All"),
    SEARCH("Search"),
    FOR_YOU("For You"),
}

private val TIMELINE_CARD_WIDTH = 132.dp

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
