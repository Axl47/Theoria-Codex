package com.theoriacodex.app.recents

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.post.displayTitleOrNull
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.noMediaDurationStateForPost
import com.theoriacodex.app.media.observedMediaDurationMs
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.SourceLogo
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.ui.components.PostActionSheet
import com.theoriacodex.app.ui.components.TwoColumnPostStaggeredGrid
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import java.util.Locale
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
    onOpenWatchedPost: (Int) -> Unit,
    onOpenCodexPost: (Int) -> Unit,
    onOpenSearch: (RecentSearchEntry) -> Unit,
    onOpenFypSearch: (RecentSearchEntry) -> Unit,
    onClear: (RecentsClearTarget) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(RecentsFilter.WATCHED) }
    val now = remember(watchedPosts, codexPosts, searches, fypSearches, activity) { System.currentTimeMillis() }
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    val hasContent = when (filter) {
        RecentsFilter.WATCHED -> watchedPosts.isNotEmpty()
        RecentsFilter.CODEX -> codexPosts.isNotEmpty()
        RecentsFilter.FYP -> fypSearches.isNotEmpty()
        RecentsFilter.SEARCHES -> searches.isNotEmpty()
        RecentsFilter.ALL -> activity.isNotEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Recents", style = MaterialTheme.typography.titleLarge)
            TextButton(
                enabled = hasContent,
                onClick = {
                    onClear(filter.clearTarget)
                },
            ) {
                Text("Clear")
            }
        }

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
                watchedPosts = watchedPosts,
                now = now,
                pixivUgoiraClient = pixivUgoiraClient,
                likedPostIds = likedPostIds,
                durationStateForPost = durationStateForPost,
                onDurationPostVisibilityChanged = onDurationPostVisibilityChanged,
                onDurationEnvironmentChanged = onDurationEnvironmentChanged,
                onAuthoritativeDurationKnown = onAuthoritativeDurationKnown,
                onToggleLike = onToggleLike,
                onOpenWatchedPost = onOpenWatchedPost,
                onLongPress = { selectedActionPost = it },
                showMediaProgress = true,
            )

            RecentsFilter.CODEX -> WatchedGrid(
                watchedPosts = codexPosts,
                now = now,
                pixivUgoiraClient = pixivUgoiraClient,
                likedPostIds = likedPostIds,
                durationStateForPost = durationStateForPost,
                onDurationPostVisibilityChanged = onDurationPostVisibilityChanged,
                onDurationEnvironmentChanged = onDurationEnvironmentChanged,
                onAuthoritativeDurationKnown = onAuthoritativeDurationKnown,
                onToggleLike = onToggleLike,
                onOpenWatchedPost = onOpenCodexPost,
                onLongPress = { selectedActionPost = it },
                emptyMessage = "Posts appear here after you open them from Codex.",
            )

            RecentsFilter.FYP -> SearchHistoryList(
                searches = fypSearches,
                now = now,
                onOpenSearch = onOpenFypSearch,
                emptyMessage = "Recommendation searches appear here after For You generates them.",
            )

            RecentsFilter.SEARCHES -> SearchHistoryList(
                searches = searches,
                now = now,
                onOpenSearch = onOpenSearch,
            )

            RecentsFilter.ALL -> ActivityList(
                activity = activity,
                watchedPosts = watchedPosts,
                codexPosts = codexPosts,
                now = now,
                onOpenWatchedPost = onOpenWatchedPost,
                onOpenCodexPost = onOpenCodexPost,
                onOpenSearch = onOpenSearch,
                onOpenFypSearch = onOpenFypSearch,
            )
        }
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
    val posts = remember(watchedPosts) { watchedPosts.map(RecentPostEntry::post) }
    val gridState = rememberLazyStaggeredGridState()
    DurationRouteEnvironmentEffect(gridState, onDurationEnvironmentChanged)

    TwoColumnPostStaggeredGrid(
        posts = posts,
        state = gridState,
        modifier = Modifier.fillMaxSize(),
    ) { index, post ->
        val entry = watchedPosts[index]
        val observedDurationMs = observedMediaDurationMs(post, durationStateForPost)
        SearchResultCard(
            post = post,
            pixivUgoiraClient = pixivUgoiraClient,
            acquiredDurationMs = observedDurationMs,
            showSourceBadge = true,
            metadataLabel = relativeTimeLabel(now, entry.viewedAtEpochMs),
            viewedMediaNumber = entry.maxViewedMediaNumber.takeIf { showMediaProgress },
            liked = post.id in likedPostIds,
            onToggleLike = onToggleLike?.let { toggle -> { toggle(post) } },
            onClick = { onOpenWatchedPost(index) },
            onLongPress = { onLongPress(post) },
            onViewportChanged = { visible -> onDurationPostVisibilityChanged(post, visible) },
            onAuthoritativeDurationKnown = { durationMs ->
                onAuthoritativeDurationKnown(post, durationMs)
            },
        )
    }
}

@Composable
private fun SearchHistoryList(
    searches: List<RecentSearchEntry>,
    now: Long,
    onOpenSearch: ((RecentSearchEntry) -> Unit)?,
    emptyMessage: String = "Searches appear here after you press Apply in Search.",
) {
    if (searches.isEmpty()) {
        EmptyRecentState(emptyMessage)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(searches, key = { entry -> entry.queryHash }) { entry ->
            RecentSearchRow(
                entry = entry,
                now = now,
                onClick = onOpenSearch?.let { open -> { open(entry) } },
            )
        }
    }
}

@Composable
private fun ActivityList(
    activity: List<RecentActivityEntry>,
    watchedPosts: List<RecentPostEntry>,
    codexPosts: List<RecentPostEntry>,
    now: Long,
    onOpenWatchedPost: (Int) -> Unit,
    onOpenCodexPost: (Int) -> Unit,
    onOpenSearch: (RecentSearchEntry) -> Unit,
    onOpenFypSearch: (RecentSearchEntry) -> Unit,
) {
    if (activity.isEmpty()) {
        EmptyRecentState("Recent posts and applied searches appear here.")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(activity, key = { entry -> activityKey(entry) }) { entry ->
            RecentActivityRow(
                entry = entry,
                watchedPosts = watchedPosts,
                codexPosts = codexPosts,
                now = now,
                onOpenWatchedPost = onOpenWatchedPost,
                onOpenCodexPost = onOpenCodexPost,
                onOpenSearch = onOpenSearch,
                onOpenFypSearch = onOpenFypSearch,
            )
        }
    }
}

@Composable
private fun RecentActivityRow(
    entry: RecentActivityEntry,
    watchedPosts: List<RecentPostEntry>,
    codexPosts: List<RecentPostEntry>,
    now: Long,
    onOpenWatchedPost: (Int) -> Unit,
    onOpenCodexPost: (Int) -> Unit,
    onOpenSearch: (RecentSearchEntry) -> Unit,
    onOpenFypSearch: (RecentSearchEntry) -> Unit,
) {
    when (entry) {
        is RecentActivityEntry.Watched -> RecentWatchedRow(
            entry = entry.entry,
            now = now,
            onClick = {
                openRecentPost(
                    postEntry = entry.entry,
                    watchedPosts = watchedPosts,
                    codexPosts = codexPosts,
                    onOpenWatchedPost = onOpenWatchedPost,
                    onOpenCodexPost = onOpenCodexPost,
                )
            },
        )

        is RecentActivityEntry.Search -> RecentSearchRow(
            entry = entry.entry,
            now = now,
            onClick = {
                if (entry.entry.kind == RecentSearchKind.FYP) {
                    onOpenFypSearch(entry.entry)
                } else {
                    onOpenSearch(entry.entry)
                }
            },
        )
    }
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
private fun RecentWatchedRow(
    entry: RecentPostEntry,
    now: Long,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.post.displayTitleOrNull()
                        ?: entry.post.authorName?.takeIf(String::isNotBlank)
                        ?: entry.post.id.source.displayName(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${entry.post.id.source.displayName()} / ${relativeTimeLabel(now, entry.viewedAtEpochMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RecentSearchRow(
    entry: RecentSearchEntry,
    now: Long,
    onClick: (() -> Unit)?,
) {
    val presentation = recentSearchPresentation(entry)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecentSearchLeading(entry, now)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = presentation.title,
                    style = MaterialTheme.typography.titleSmall,
                )
                presentation.subtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentSearchLeading(entry: RecentSearchEntry, now: Long) {
    Column(
        modifier = Modifier.widthIn(min = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val source = when (entry.kind) {
            RecentSearchKind.SOURCE -> (entry.query.mode as? QueryMode.Source)?.source
            RecentSearchKind.FYP -> entry.sources.singleOrNull()
            else -> null
        }
        if (source != null) {
            SourceLogo(source = source, size = 24.dp)
        } else {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = when (entry.kind) {
                    RecentSearchKind.MULTI_SEARCH -> "Multi-Search"
                    RecentSearchKind.FYP -> "FYP search"
                    else -> "Unified search"
                },
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = relativeTimeLabel(now, entry.searchedAtEpochMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
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

private enum class RecentsFilter(
    val label: String,
    val clearTarget: RecentsClearTarget,
) {
    WATCHED("Watched", RecentsClearTarget.WATCHED),
    CODEX("Codex", RecentsClearTarget.CODEX),
    SEARCHES("Searches", RecentsClearTarget.SEARCHES),
    FYP("FYP", RecentsClearTarget.FYP),
    ALL("All", RecentsClearTarget.ALL),
}

private fun activityKey(entry: RecentActivityEntry): String {
    return when (entry) {
        is RecentActivityEntry.Watched -> "watched:${entry.entry.section.name}:${entry.entry.post.id.source.name}:${entry.entry.post.id.sourcePostId}:${entry.occurredAtEpochMs}"
        is RecentActivityEntry.Search -> "search:${entry.entry.queryHash}:${entry.occurredAtEpochMs}"
    }
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

private fun relativeTimeLabel(now: Long, eventAt: Long): String {
    val elapsedMs = (now - eventAt).coerceAtLeast(0L)
    val minuteMs = 60_000L
    val hourMs = 60L * minuteMs
    val dayMs = 24L * hourMs
    return when {
        elapsedMs < minuteMs -> "Just now"
        elapsedMs < hourMs -> "${elapsedMs / minuteMs}m ago"
        elapsedMs < dayMs -> "${elapsedMs / hourMs}h ago"
        elapsedMs < 7L * dayMs -> "${elapsedMs / dayMs}d ago"
        else -> String.format(Locale.US, "%.0fw ago", elapsedMs.toDouble() / (7L * dayMs).toDouble())
    }
}
