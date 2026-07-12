package com.theoriacodex.app.recents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import java.util.Locale

@Composable
fun RecentsScreen(
    watchedPosts: List<RecentPostEntry>,
    codexPosts: List<RecentPostEntry>,
    searches: List<RecentSearchEntry>,
    activity: List<RecentActivityEntry>,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    likedPostIds: Set<PostId> = emptySet(),
    onToggleLike: ((Post) -> Unit)? = null,
    onOpenWatchedPost: (Int) -> Unit,
    onOpenCodexPost: (Int) -> Unit,
    onOpenSearch: (RecentSearchEntry) -> Unit,
    onClearWatched: () -> Unit,
    onClearCodex: () -> Unit,
    onClearSearches: () -> Unit,
    onClearAll: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(RecentsFilter.WATCHED) }
    val now = remember(watchedPosts, searches, activity) { System.currentTimeMillis() }
    val hasContent = when (filter) {
        RecentsFilter.WATCHED -> watchedPosts.isNotEmpty()
        RecentsFilter.CODEX -> codexPosts.isNotEmpty()
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
                    when (filter) {
                        RecentsFilter.WATCHED -> onClearWatched()
                        RecentsFilter.CODEX -> onClearCodex()
                        RecentsFilter.SEARCHES -> onClearSearches()
                        RecentsFilter.ALL -> onClearAll()
                    }
                },
            ) {
                Text("Clear")
            }
        }

        Row(
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
                onToggleLike = onToggleLike,
                onOpenWatchedPost = onOpenWatchedPost,
            )

            RecentsFilter.CODEX -> WatchedGrid(
                watchedPosts = codexPosts,
                now = now,
                pixivUgoiraClient = pixivUgoiraClient,
                likedPostIds = likedPostIds,
                onToggleLike = onToggleLike,
                onOpenWatchedPost = onOpenCodexPost,
                emptyMessage = "Posts appear here after you open them from Codex.",
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
            )
        }
    }
}

@Composable
private fun WatchedGrid(
    watchedPosts: List<RecentPostEntry>,
    now: Long,
    pixivUgoiraClient: PixivUgoiraClient?,
    likedPostIds: Set<PostId>,
    onToggleLike: ((Post) -> Unit)?,
    onOpenWatchedPost: (Int) -> Unit,
    emptyMessage: String = "Posts appear here after you open them in Viewer.",
) {
    if (watchedPosts.isEmpty()) {
        EmptyRecentState(emptyMessage)
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(
            items = watchedPosts,
            key = { _, entry -> "${entry.post.id.source.name}:${entry.post.id.sourcePostId}" },
        ) { index, entry ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SearchResultCard(
                    post = entry.post,
                    pixivUgoiraClient = pixivUgoiraClient,
                    showSourceBadge = true,
                    liked = entry.post.id in likedPostIds,
                    onToggleLike = onToggleLike?.let { toggle -> { toggle(entry.post) } },
                    onClick = { onOpenWatchedPost(index) },
                )
                Text(
                    text = relativeTimeLabel(now, entry.viewedAtEpochMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchHistoryList(
    searches: List<RecentSearchEntry>,
    now: Long,
    onOpenSearch: (RecentSearchEntry) -> Unit,
) {
    if (searches.isEmpty()) {
        EmptyRecentState("Searches appear here after you press Apply in Search.")
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
                onClick = { onOpenSearch(entry) },
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
) {
    if (activity.isEmpty()) {
        EmptyRecentState("Watched posts and applied searches appear here.")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(activity, key = { entry -> activityKey(entry) }) { entry ->
            when (entry) {
                is RecentActivityEntry.Watched -> RecentWatchedRow(
                    entry = entry.entry,
                    now = now,
                    onClick = {
                        if (entry.entry.origin == ViewerStreamSource.CODEX) {
                            val index = codexPosts.indexOfFirst { watched -> watched.post.id == entry.entry.post.id }
                            if (index >= 0) onOpenCodexPost(index)
                        } else {
                            val index = watchedPosts.indexOfFirst { watched -> watched.post.id == entry.entry.post.id }
                            if (index >= 0) onOpenWatchedPost(index)
                        }
                    },
                )

                is RecentActivityEntry.Search -> RecentSearchRow(
                    entry = entry.entry,
                    now = now,
                    onClick = { onOpenSearch(entry.entry) },
                )
            }
        }
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
                    text = entry.post.title?.takeIf(String::isNotBlank)
                        ?: entry.post.authorName?.takeIf(String::isNotBlank)
                        ?: entry.post.id.sourcePostId,
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
                imageVector = Icons.Default.Search,
                contentDescription = null,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = queryTitle(entry.query),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${querySubtitle(entry.query)} / ${relativeTimeLabel(now, entry.searchedAtEpochMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
    WATCHED("Watched"),
    CODEX("Codex"),
    SEARCHES("Searches"),
    ALL("All"),
}

private fun activityKey(entry: RecentActivityEntry): String {
    return when (entry) {
        is RecentActivityEntry.Watched -> "watched:${entry.entry.post.id.source.name}:${entry.entry.post.id.sourcePostId}:${entry.occurredAtEpochMs}"
        is RecentActivityEntry.Search -> "search:${entry.entry.queryHash}:${entry.occurredAtEpochMs}"
    }
}

private fun queryTitle(query: Query): String {
    return when (val mode = query.mode) {
        QueryMode.Unified -> "Unified search"
        is QueryMode.Source -> "${mode.source.displayName()} search"
    }
}

private fun querySubtitle(query: Query): String {
    val included = query.includeTags.joinToString(" ")
    val excluded = query.excludeTags.joinToString(" ") { tag -> "-$tag" }
    val tagText = listOf(included, excluded)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .ifBlank { "No tags" }
    val sort = when (query.sort) {
        SortMode.NEWEST -> "Newest"
        SortMode.POPULAR -> "Popular"
        SortMode.TOP -> "Top"
        SortMode.RANDOM -> "Random"
    }
    return "$tagText / $sort"
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
