package com.theoriacodex.app.codex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.media.MediaDurationStates
import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.app.media.knownMediaDurations
import com.theoriacodex.app.search.postMediaCount
import com.theoriacodex.app.statistics.statisticsPostKey
import com.theoriacodex.app.ui.components.FeedTraceSections
import com.theoriacodex.app.ui.components.appendedTo
import com.theoriacodex.app.ui.components.RetainedFrames
import com.theoriacodex.app.ui.components.rememberComputedOffMain
import com.theoriacodex.app.ui.components.SortOption
import com.theoriacodex.data.repository.CodexPostSortKey
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.sortKeys
import java.util.IdentityHashMap
import kotlinx.coroutines.flow.first

data class CodexUsage(val watchingMs: Long = 0L, val opens: Long = 0L) {
    companion object {
        val MostWatched: Comparator<CodexUsage> =
            compareByDescending(CodexUsage::watchingMs).thenByDescending(CodexUsage::opens)
        val MostOpened: Comparator<CodexUsage> =
            compareByDescending(CodexUsage::opens).thenByDescending(CodexUsage::watchingMs)
    }
}

internal val CodexPostSortOptions: List<SortOption<CodexPostSortKey>> = CodexSortMode.entries.sortKeys().map { key ->
    SortOption(
        key,
        when (key) {
            CodexPostSortKey.DATE_ADDED -> "Date added"
            CodexPostSortKey.WATCH_TIME -> "Watch time"
            CodexPostSortKey.OPENS -> "Opens"
            CodexPostSortKey.PAGES -> "Pages"
            CodexPostSortKey.LENGTH -> "Length"
            CodexPostSortKey.CREATOR -> "Creator"
            CodexPostSortKey.SOURCE -> "By source"
        },
    )
}

internal val CodexSortMode.usesUsage: Boolean
    get() = choice.key == CodexPostSortKey.WATCH_TIME || choice.key == CodexPostSortKey.OPENS

val CodexSortMode.usesDuration: Boolean
    get() = choice.key == CodexPostSortKey.LENGTH

val CodexSortMode.storeOrder: CodexSortMode
    get() = takeIf { it == CodexSortMode.OLDEST_SAVED || it == CodexSortMode.BY_SOURCE } ?: CodexSortMode.NEWEST_SAVED

internal fun codexPostRefinement(
    sort: CodexSortMode,
    usage: Map<String, CodexUsage>,
    durations: Map<PostId, Long> = emptyMap(),
): Comparator<Post>? {
    val usageOf = { post: Post -> usage[statisticsPostKey(post.id)] ?: CodexUsage() }
    return when (sort) {
        CodexSortMode.NEWEST_SAVED, CodexSortMode.OLDEST_SAVED -> null
        CodexSortMode.MOST_WATCHED -> compareBy(CodexUsage.MostWatched, usageOf)
        CodexSortMode.LEAST_WATCHED -> compareBy(CodexUsage.MostWatched.reversed(), usageOf)
        CodexSortMode.MOST_OPENED -> compareBy(CodexUsage.MostOpened, usageOf)
        CodexSortMode.LEAST_OPENED -> compareBy(CodexUsage.MostOpened.reversed(), usageOf)
        CodexSortMode.MOST_PAGES -> compareByDescending(::postMediaCount)
        CodexSortMode.FEWEST_PAGES -> compareBy(::postMediaCount)
        CodexSortMode.LONGEST -> lengthGroups(durations).thenByDescending { durations[it.id] }
        CodexSortMode.SHORTEST -> lengthGroups(durations).thenBy { durations[it.id] }
        CodexSortMode.CREATOR_ASCENDING -> compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER), ::creatorName)
        CodexSortMode.CREATOR_DESCENDING -> compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER.reversed()), ::creatorName)
        CodexSortMode.BY_SOURCE -> compareBy { it.id.source }
    }
}

private fun creatorName(post: Post): String? =
    (post.creatorProfile?.displayName ?: post.authorName)?.takeIf(String::isNotBlank)

private fun lengthGroups(durations: Map<PostId, Long>): Comparator<Post> {
    val groups = IdentityHashMap<Post, Int>()
    return compareBy { post ->
        groups.getOrPut(post) {
            when {
                post.id in durations -> 0
                isAnimatedPost(post) -> 1
                else -> 2
            }
        }
    }
}

internal fun sortCodexPosts(
    posts: List<Post>,
    sort: CodexSortMode,
    usage: Map<String, CodexUsage>,
    durations: Map<PostId, Long> = emptyMap(),
): List<Post> = codexPostRefinement(sort, usage, durations)?.let(posts::sortedWith) ?: posts

private val CodexDurationFrames = RetainedFrames<Post, Map<PostId, Long>>()
private val CodexSortFrames = RetainedFrames<Post, List<Post>?>()

@Composable
fun rememberCodexPostDurations(
    posts: List<Post>?,
    sort: CodexSortMode,
    states: MediaDurationStates,
    codexId: String,
): Map<PostId, Long> {
    if (!sort.usesDuration || posts == null) return emptyMap()
    return rememberComputedOffMain(
        posts, states, section = FeedTraceSections.KNOWN_DURATIONS, retainKey = CodexDurationFrames.at("codex-durations:$codexId"),
        extend = { previous -> posts.appendedTo(previous.list)?.let { previous.value + knownMediaDurations(it, states) } },
    ) {
        knownMediaDurations(posts, states)
    }
}

@Composable
fun rememberSortedCodexPosts(
    codexId: String,
    posts: List<Post>?,
    sort: CodexSortMode,
    usage: Map<String, CodexUsage>?,
    durationStates: MediaDurationStates,
): List<Post>? {
    val durations = rememberCodexPostDurations(posts, sort, durationStates, codexId)
    return rememberSortedCodexPosts(codexId, posts, sort, usage, durations)
}

@Composable
fun rememberSortedCodexPosts(
    codexId: String,
    posts: List<Post>?,
    sort: CodexSortMode,
    usage: Map<String, CodexUsage>?,
    durations: Map<PostId, Long>,
): List<Post>? = posts?.let {
    rememberComputedOffMain(posts, sort, usage, durations, section = FeedTraceSections.SORT, retainKey = CodexSortFrames.at("codex-sort:$codexId")) {
        usage?.let { sortCodexPosts(posts, sort, it, durations) }
    }
}

internal suspend fun StatisticsRepository.codexPostUsage(codexId: String): Map<String, CodexUsage> =
    runCatchingPreservingCancellation { observeStatistics().first() }.getOrNull()
        ?.codexStatistics?.get(codexId)?.posts.orEmpty()
        .mapValues { (_, post) -> CodexUsage(watchingMs = post.videoMs + post.imageMs, opens = post.opens) }

internal class CodexUsageSnapshots : ViewModel() {
    val taken = mutableMapOf<String, Map<String, CodexUsage>>()
}

@Composable
fun rememberCodexPostUsage(
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
