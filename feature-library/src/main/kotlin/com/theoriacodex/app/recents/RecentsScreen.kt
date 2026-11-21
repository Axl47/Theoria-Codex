package com.theoriacodex.app.recents

import com.theoriacodex.app.library.BareSectionHeader
import com.theoriacodex.app.library.LibrarySectionHeader
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import com.theoriacodex.app.ui.components.FeedCardEnvironment
import com.theoriacodex.app.ui.components.FeedDurations
import com.theoriacodex.app.source.SourcePresentationCatalog
import com.theoriacodex.app.source.NullableSourceKeySaver
import androidx.compose.material.icons.filled.MoreVert
import com.theoriacodex.app.ui.components.ActionMenuRow
import com.theoriacodex.app.ui.components.ActionMenuSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.components.SingleChoiceMenu
import com.theoriacodex.app.ui.components.SourceSelector
import com.theoriacodex.app.ui.components.expandableControlSemantics
import com.theoriacodex.app.ui.components.FeedPostActionSheet
import com.theoriacodex.app.related.RelatedShelfBinding
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.MAX_SAVED_SEARCH_NAME_LENGTH
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

@Composable
fun RecentsScreen(
    watchedPosts: List<RecentPostEntry>,
    codexPosts: List<RecentPostEntry>,
    searches: List<RecentSearchEntry>,
    fypSearches: List<RecentSearchEntry>,
    activity: List<RecentActivityEntry>,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    durations: FeedDurations = FeedDurations.NONE,
    likedPostIds: Set<PostId> = emptySet(),
    onToggleLike: ((Post) -> Unit)? = null,
    creatorBrowsingSources: Set<SourceKey> = emptySet(),
    onRequestSaveToCodex: (Post) -> Unit,
    onSaveToDevice: (Post) -> Unit,
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
    header: LibrarySectionHeader = BareSectionHeader,
) {
    var filter by rememberSaveable { mutableStateOf(RecentsFilter.ALL) }
    var searchFilter by rememberSaveable { mutableStateOf(RecentSearchFilter.ALL) }
    var showMenu by remember { mutableStateOf(false) }
    var selectedSource by rememberSaveable(stateSaver = NullableSourceKeySaver) { mutableStateOf<SourceKey?>(null) }
    val visible = rememberVisibleRecents(watchedPosts, codexPosts, searches, fypSearches, activity, selectedSource)
    val sourceEmptyMessage = selectedSource?.let { "No recent activity from ${it.displayName()}." }
    val openWatchedPost: (Int) -> Unit = { onOpenWatchedPost(visible.watchedPosts, it) }
    val openCodexPost: (Int) -> Unit = { onOpenCodexPost(visible.codexPosts, it) }
    val now = remember(watchedPosts, codexPosts, searches, fypSearches, activity) { System.currentTimeMillis() }
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var selectedActionSection by remember { mutableStateOf(RecentPostSection.WATCHED) }
    var searchToPin by remember { mutableStateOf<RecentSearchEntry?>(null) }
    val onPinSearch = onSaveSearch?.let { { entry: RecentSearchEntry -> searchToPin = entry } }
    val cards = rememberRecentsPostCards(pixivUgoiraClient, likedPostIds, durations, onToggleLike)
    val relatedSlot = rememberRecentsRelated(related, filter, selectedSource, visible, cards, onShownPostsChanged) { selectedActionPost = it }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header {
            RecentsSectionSelector(filter, onSelected = { filter = it })
            RecentsSourceSelector(selectedSource, onSelected = { selectedSource = it })
            IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Recents actions") }
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
            post, creatorBrowsingSources, { selectedActionPost = null }, onSaveToDevice, onRequestSaveToCodex,
            onGoToSearch,
            onStartOver = {
                onStartOver(post, selectedActionSection)
                selectedActionPost = null
            }.takeIf { maxOf(post.mediaCount ?: 1, post.media.size) > 1 },
            onShowRelated = related?.let { shelf -> { shelf.controller.show(post) } },
            liked = post.id in likedPostIds, onToggleLike = onToggleLike,
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
        availableSources = SourceKey.builtIns + SourcePresentationCatalog.customSources(),
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

/** What each post tile and the related shelf in Recents share, and where the grid reports when it is idle. */
internal class RecentsPostCards(
    val environment: FeedCardEnvironment,
    val onEnvironmentChanged: (lifecycleStarted: Boolean, scrollIdle: Boolean) -> Unit,
)

@Composable
private fun rememberRecentsPostCards(
    pixivUgoiraClient: PixivUgoiraClient?,
    likedPostIds: Set<PostId>,
    durations: FeedDurations,
    onToggleLike: ((Post) -> Unit)?,
) = RecentsPostCards(
    rememberFeedCardEnvironment(pixivUgoiraClient, likedPostIds, emptyMap(), durations, onToggleLike),
    durations.onEnvironmentChanged,
)

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

internal enum class RecentsFilter(val label: String) {
    ALL("All"),
    WATCHED("Watched"),
    CODEX("Codex"),
    SEARCHES("Searches"),
}

private const val RECENTS_SECTION_SELECTOR_LABEL = "Filter Recents by section"

fun RecentSearchEntry.fypSeedBySource(): Map<SourceKey, List<String>> {
    return sourceTags.ifEmpty {
        sources.associateWith { query.includeTags }
    }
}

private fun filterRecentPostsBySource(posts: List<RecentPostEntry>, source: SourceKey?): List<RecentPostEntry> =
    posts.filter { source == null || it.post.id.source == source }
