package com.theoriacodex.app.codex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.app.media.knownMediaDurations
import com.theoriacodex.app.search.postMediaCount
import com.theoriacodex.app.statistics.statisticsPostKey
import com.theoriacodex.app.ui.components.FeedTraceSections
import com.theoriacodex.app.ui.components.rememberComputedOffMain
import com.theoriacodex.app.viewer.traceMediaSection
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.first

/** Stats figures a Codex, or a post within one, can sort by: time watched and times opened. */
internal data class CodexUsage(val watchingMs: Long = 0L, val opens: Long = 0L) {
    companion object {
        val MostWatched: Comparator<CodexUsage> =
            compareByDescending(CodexUsage::watchingMs).thenByDescending(CodexUsage::opens)
        val MostOpened: Comparator<CodexUsage> =
            compareByDescending(CodexUsage::opens).thenByDescending(CodexUsage::watchingMs)
    }
}

internal val CodexSortMode.label: String
    get() = when (this) {
        CodexSortMode.NEWEST_SAVED -> "Recently added"
        CodexSortMode.OLDEST_SAVED -> "Oldest first"
        CodexSortMode.MOST_WATCHED -> "Most watched"
        CodexSortMode.MOST_OPENED -> "Most opened"
        CodexSortMode.MOST_PAGES -> "Most pages"
        CodexSortMode.FEWEST_PAGES -> "Fewest pages"
        CodexSortMode.LONGEST -> "Longest"
        CodexSortMode.SHORTEST -> "Shortest"
        CodexSortMode.CREATOR_ASCENDING -> "Creator (A–Z)"
        CodexSortMode.BY_SOURCE -> "By source"
    }

internal val CodexSortMode.usesUsage: Boolean
    get() = this == CodexSortMode.MOST_WATCHED || this == CodexSortMode.MOST_OPENED

/** Length sorts order animated posts by duration, so they ask for every unknown duration to be resolved. */
internal val CodexSortMode.usesDuration: Boolean
    get() = this == CodexSortMode.LONGEST || this == CodexSortMode.SHORTEST

/** The order to ask the store for; it orders only by save time or source, and the app refines the rest. */
internal val CodexSortMode.storeOrder: CodexSortMode
    get() = takeIf { it == CodexSortMode.OLDEST_SAVED || it == CodexSortMode.BY_SOURCE } ?: CodexSortMode.NEWEST_SAVED

/**
 * What [sort] orders by beyond a list's date order, or null when the date order already is the sort.
 * Sorting is stable, so ties keep the date order. [usage] is keyed by [statisticsPostKey]; [durations]
 * holds the animation lengths known so far.
 */
internal fun codexPostRefinement(
    sort: CodexSortMode,
    usage: Map<String, CodexUsage>,
    durations: Map<PostId, Long> = emptyMap(),
): Comparator<Post>? {
    val usageOf = { post: Post -> usage[statisticsPostKey(post.id)] ?: CodexUsage() }
    return when (sort) {
        CodexSortMode.NEWEST_SAVED, CodexSortMode.OLDEST_SAVED -> null
        CodexSortMode.MOST_WATCHED -> compareBy(CodexUsage.MostWatched, usageOf)
        CodexSortMode.MOST_OPENED -> compareBy(CodexUsage.MostOpened, usageOf)
        CodexSortMode.MOST_PAGES -> compareByDescending(::postMediaCount)
        CodexSortMode.FEWEST_PAGES -> compareBy(::postMediaCount)
        CodexSortMode.LONGEST -> lengthGroups(durations).thenByDescending { durations[it.id] }
        CodexSortMode.SHORTEST -> lengthGroups(durations).thenBy { durations[it.id] }
        // Posts without a creator go last.
        CodexSortMode.CREATOR_ASCENDING -> compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) { post: Post ->
            (post.creatorProfile?.displayName ?: post.authorName)?.takeIf(String::isNotBlank)
        }
        CodexSortMode.BY_SOURCE -> compareBy { it.id.source }
    }
}

/** Known lengths first, then animated posts still unresolved, then posts with no animation. */
private fun lengthGroups(durations: Map<PostId, Long>): Comparator<Post> = compareBy { post ->
    when {
        post.id in durations -> 0
        isAnimatedPost(post) -> 1
        else -> 2
    }
}

/** Orders a saved Codex's [posts], which the store returned in [CodexSortMode.storeOrder] for [sort]. */
internal fun sortCodexPosts(
    posts: List<Post>,
    sort: CodexSortMode,
    usage: Map<String, CodexUsage>,
    durations: Map<PostId, Long> = emptyMap(),
): List<Post> = traceMediaSection(FeedTraceSections.SORT) {
    codexPostRefinement(sort, usage, durations)?.let(posts::sortedWith) ?: posts
}

/**
 * Known animation lengths of [posts] while [sort] is a length sort; empty otherwise. Recomputed off the main
 * thread as durations resolve, so posts settle into place while resolution runs. Other sorts ignore [states].
 */
@Composable
internal fun rememberCodexPostDurations(
    posts: List<Post>?,
    sort: CodexSortMode,
    states: Map<MediaDurationKey, MediaDurationState>,
): Map<PostId, Long> {
    if (!sort.usesDuration || posts == null) return emptyMap()
    return rememberComputedOffMain(posts, states, extend = { known, appended -> known + knownMediaDurations(appended, states) }) {
        knownMediaDurations(posts, states)
    }
}

/** Per-post usage within [codexId]. A statistics failure degrades to no usage, leaving the date order. */
internal suspend fun StatisticsRepository.codexPostUsage(codexId: String): Map<String, CodexUsage> =
    runCatchingPreservingCancellation { observeStatistics().first() }.getOrNull()
        ?.codexStatistics?.get(codexId)?.posts.orEmpty()
        .mapValues { (_, post) -> CodexUsage(watchingMs = post.videoMs + post.imageMs, opens = post.opens) }

/** Usage read once per Codex visit, so posts keep their places while the Viewer adds watch time and opens. */
internal class CodexUsageSnapshots : ViewModel() {
    val taken = mutableMapOf<String, Map<String, CodexUsage>>()
}

/**
 * Usage for [codexId]'s posts as it stood when this visit first chose a usage sort; null until read.
 * Other sorts need none and get an empty map.
 */
@Composable
internal fun rememberCodexPostUsage(
    statistics: StatisticsRepository,
    codexId: String,
    sort: CodexSortMode,
): Map<String, CodexUsage>? {
    if (!sort.usesUsage) return emptyMap()
    val snapshots = viewModel<CodexUsageSnapshots>()
    return produceState(snapshots.taken[codexId], snapshots, codexId) {
        value = snapshots.taken.getOrPut(codexId) { statistics.codexPostUsage(codexId) }
    }.value
}
