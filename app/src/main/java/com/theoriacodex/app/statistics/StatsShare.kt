package com.theoriacodex.app.statistics

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.theoriacodex.app.viewer.decodeViewerPostId
import com.theoriacodex.data.repository.CodexPostStatistics
import com.theoriacodex.data.repository.checkDocumentDepth
import com.theoriacodex.data.repository.rankedStatisticsPosts

private const val STATS_SHARE_FORMAT = "theoria-codex-stats"
private const val STATS_SHARE_VERSION = 1
internal const val STATS_SHARE_MAX_BYTES = 4 * 1024 * 1024
private const val MAX_CODICES = 500
private const val MAX_POSTS_PER_CODEX = 64
private const val MAX_NAME_CHARS = 120
private const val MAX_LABEL_CHARS = 300
private const val MAX_POST_KEY_CHARS = 256
// Large enough for any real total. Totals across MAX_CODICES stay in range, and merged posts are capped again as they add up.
private const val MAX_STAT_VALUE = 1_000_000_000_000_000L
private const val MAX_COMPARED_POSTS = 20

/** The shared-file format. Holds only what the person left in; Codex ids and profile details never enter it. */
data class StatsShareFile(
    // No defaults: a file that omits these must fail validation, and Gson would otherwise fill them in.
    @field:SerializedName("format") val format: String? = null,
    @field:SerializedName("version") val version: Int? = null,
    @field:SerializedName("name") val name: String? = null,
    @field:SerializedName("codices") val codices: List<StatsShareCodex?>? = null,
    /** App-wide totals, absent when the person left them out or the file predates them. */
    @field:SerializedName("overall") val overall: StatsShareOverall? = null,
)

/** The Stats page's app-wide numbers, which count everything and not only time inside Codices. */
data class StatsShareOverall(
    @field:SerializedName("timeInAppMs") val timeInAppMs: Long = 0L,
    @field:SerializedName("browsingMs") val browsingMs: Long = 0L,
    @field:SerializedName("watchingMs") val watchingMs: Long = 0L,
    @field:SerializedName("codexMs") val codexMs: Long = 0L,
    @field:SerializedName("appOpens") val appOpens: Long = 0L,
    @field:SerializedName("postsWatched") val postsWatched: Long = 0L,
    @field:SerializedName("postsSaved") val postsSaved: Long = 0L,
    @field:SerializedName("searches") val searches: Long = 0L,
)

data class StatsShareCodex(
    @field:SerializedName("name") val name: String? = null,
    @field:SerializedName("entries") val entries: Long = 0L,
    @field:SerializedName("browsingMs") val browsingMs: Long = 0L,
    @field:SerializedName("watchingMs") val watchingMs: Long = 0L,
    @field:SerializedName("postOpens") val postOpens: Long = 0L,
    @field:SerializedName("posts") val posts: List<StatsSharePost?>? = null,
)

data class StatsSharePost(
    @field:SerializedName("key") val key: String? = null,
    @field:SerializedName("label") val label: String? = null,
    @field:SerializedName("opens") val opens: Long = 0L,
    @field:SerializedName("videoMs") val videoMs: Long = 0L,
    @field:SerializedName("imageMs") val imageMs: Long = 0L,
)

/** What the person chose to leave out. A post left out of one Codex is left out everywhere it appears. */
internal data class StatsShareSelection(
    val excludedCodexIds: Set<String> = emptySet(),
    val excludedPostKeys: Set<String> = emptySet(),
)

internal fun StatisticsSummary.shareOverall() = StatsShareOverall(
    timeInAppMs = totalForegroundMs,
    browsingMs = browsingMs,
    watchingMs = watchingMs,
    codexMs = codexMs,
    appOpens = appOpenCount,
    postsWatched = watchedPostCount,
    postsSaved = savedPostCount,
    searches = searchCount,
)

/** Every profile's Codices once each; the app-wide Codices appear under all of them. */
internal fun allProfileCodices(profiles: List<ProfileStatistics>): List<CodexStatisticsSummary> =
    profiles.flatMap { it.codices }.distinctBy { it.codexId }

/** Codices that only unchecked profiles hold. One shown under several profiles stays in while any is included. */
internal fun codexIdsOutsideProfiles(profiles: List<ProfileStatistics>, included: Set<String>): Set<String> {
    val reachable = profiles.filter { it.profileId in included }.flatMapTo(hashSetOf()) { profile -> profile.codices.map { it.codexId } }
    return allProfileCodices(profiles).mapTo(hashSetOf()) { it.codexId } - reachable
}

/** Codices with nothing to compare stay out of the file and the preview. */
internal val CodexStatisticsSummary.hasShareableActivity: Boolean
    get() = entries > 0L || browsingMs > 0L || watchingMs > 0L || posts.isNotEmpty()

/**
 * One Codex as it would be shared. Removing a post also removes its opens and viewing time, and the next-ranked
 * posts move up, so nothing in the file reveals that anything was left out.
 */
internal fun shareCodex(codex: CodexStatisticsSummary, excludedPostKeys: Set<String>): StatsShareCodex {
    val (removed, kept) = codex.posts.entries.partition { it.key in excludedPostKeys }
    val keptPosts = kept.associate { it.toPair() }
    val top = listOf<(CodexPostStatistics) -> Long>({ it.opens }, { it.videoMs }, { it.imageMs })
        .flatMap { value -> rankedStatisticsPosts(keptPosts, value) }
        .distinctBy { it.key }
    return StatsShareCodex(
        name = codex.name,
        entries = codex.entries,
        browsingMs = codex.browsingMs,
        watchingMs = (codex.watchingMs - removed.sumOf { it.value.videoMs + it.value.imageMs }).coerceAtLeast(0L),
        postOpens = keptPosts.values.sumOf { it.opens },
        posts = top.map { (key, post) -> StatsSharePost(key, post.label.ifBlank { key }, post.opens, post.videoMs, post.imageMs) },
    )
}

internal fun buildStatsShare(
    codices: List<CodexStatisticsSummary>,
    selection: StatsShareSelection = StatsShareSelection(),
    name: String? = null,
    overall: StatsShareOverall? = null,
): StatsShareFile = StatsShareFile(
    format = STATS_SHARE_FORMAT,
    version = STATS_SHARE_VERSION,
    name = name?.trim()?.takeIf(String::isNotEmpty),
    codices = codices.filter { it.hasShareableActivity && it.codexId !in selection.excludedCodexIds }
        .map { shareCodex(it, selection.excludedPostKeys) },
    overall = overall,
)

internal fun encodeStatsShare(file: StatsShareFile): String = Gson().toJson(file)

/** Reads a file from someone else: anything malformed is rejected or dropped, and what remains is safe to display. */
internal fun parseStatsShare(raw: String): StatsShareFile? {
    val file = runCatching {
        require(raw.toByteArray(Charsets.UTF_8).size <= STATS_SHARE_MAX_BYTES)
        checkDocumentDepth(raw)
        Gson().fromJson(raw, StatsShareFile::class.java)
    }.getOrNull() ?: return null
    if (file.format != STATS_SHARE_FORMAT || file.version != STATS_SHARE_VERSION) return null
    return file.copy(
        name = file.name?.cleanText(MAX_NAME_CHARS)?.takeIf(String::isNotEmpty),
        codices = file.codices.orEmpty().take(MAX_CODICES).mapNotNull(::sanitizedCodex),
        overall = file.overall?.let(::sanitizedOverall),
    )
}

private fun sanitizedOverall(overall: StatsShareOverall) = StatsShareOverall(
    timeInAppMs = overall.timeInAppMs.boundedStat(),
    browsingMs = overall.browsingMs.boundedStat(),
    watchingMs = overall.watchingMs.boundedStat(),
    codexMs = overall.codexMs.boundedStat(),
    appOpens = overall.appOpens.boundedStat(),
    postsWatched = overall.postsWatched.boundedStat(),
    postsSaved = overall.postsSaved.boundedStat(),
    searches = overall.searches.boundedStat(),
)

private fun sanitizedCodex(codex: StatsShareCodex?): StatsShareCodex? {
    val name = codex?.name?.cleanText(MAX_NAME_CHARS)?.takeIf(String::isNotEmpty) ?: return null
    return StatsShareCodex(
        name = name,
        entries = codex.entries.boundedStat(),
        browsingMs = codex.browsingMs.boundedStat(),
        watchingMs = codex.watchingMs.boundedStat(),
        postOpens = codex.postOpens.boundedStat(),
        posts = codex.posts.orEmpty().take(MAX_POSTS_PER_CODEX).mapNotNull(::sanitizedPost),
    )
}

private fun sanitizedPost(post: StatsSharePost?): StatsSharePost? {
    val key = post?.key?.takeIf { it.length <= MAX_POST_KEY_CHARS && decodeViewerPostId(it) != null } ?: return null
    return StatsSharePost(
        key = key,
        label = post.label?.cleanText(MAX_LABEL_CHARS)?.takeIf(String::isNotEmpty) ?: key,
        opens = post.opens.boundedStat(),
        videoMs = post.videoMs.boundedStat(),
        imageMs = post.imageMs.boundedStat(),
    )
}

private fun String.cleanText(maxChars: Int): String = filterNot(Char::isISOControl).trim().take(maxChars)

private fun Long.boundedStat(): Long = coerceIn(0L, MAX_STAT_VALUE)

internal data class StatsShareTotals(val watchingMs: Long, val browsingMs: Long, val entries: Long, val postOpens: Long)

internal data class ComparedCodex(val name: String, val yourWatchingMs: Long, val theirWatchingMs: Long)

internal data class ComparedPost(
    val key: String,
    val label: String,
    val yourOpens: Long,
    val theirOpens: Long,
    val yourViewingMs: Long,
    val theirViewingMs: Long,
)

internal data class StatsComparison(
    val yours: StatsShareTotals,
    val theirs: StatsShareTotals,
    val codices: List<ComparedCodex>,
    val postsInCommon: List<ComparedPost>,
    val yourTopPosts: List<ComparedPost>,
    val theirTopPosts: List<ComparedPost>,
)

/** Codices pair up by name because their ids are private to each person's library. */
internal fun compareStats(yours: StatsShareFile, theirs: StatsShareFile): StatsComparison {
    val yourCodices = yours.codices.orEmpty().filterNotNull()
    val theirCodices = theirs.codices.orEmpty().filterNotNull()
    val yourPosts = mergedPosts(yourCodices)
    val theirPosts = mergedPosts(theirCodices)
    val compared = (yourPosts.keys + theirPosts.keys).associateWith { key ->
        val mine = yourPosts[key]
        val other = theirPosts[key]
        ComparedPost(
            key = key,
            label = (mine ?: other)?.label ?: key,
            yourOpens = mine?.opens ?: 0L,
            theirOpens = other?.opens ?: 0L,
            yourViewingMs = mine?.viewingMs ?: 0L,
            theirViewingMs = other?.viewingMs ?: 0L,
        )
    }
    fun ranked(keys: Set<String>, score: (ComparedPost) -> Long) = keys.mapNotNull(compared::get)
        .sortedWith(compareByDescending(score).thenBy(ComparedPost::key)).take(MAX_COMPARED_POSTS)
    return StatsComparison(
        yours = totals(yourCodices),
        theirs = totals(theirCodices),
        codices = comparedCodices(yourCodices, theirCodices),
        postsInCommon = ranked(yourPosts.keys intersect theirPosts.keys) { minOf(it.yourOpens, it.theirOpens) },
        yourTopPosts = ranked(yourPosts.keys) { it.yourOpens },
        theirTopPosts = ranked(theirPosts.keys) { it.theirOpens },
    )
}

private data class MergedPost(val label: String, val opens: Long, val viewingMs: Long)

/** A post saved in several Codices counts once, with its activity added together. */
private fun mergedPosts(codices: List<StatsShareCodex>): Map<String, MergedPost> {
    val merged = linkedMapOf<String, MergedPost>()
    codices.flatMap { it.posts.orEmpty().filterNotNull() }.forEach { post ->
        val key = post.key ?: return@forEach
        val previous = merged[key]
        merged[key] = MergedPost(
            label = previous?.label ?: post.label ?: key,
            opens = ((previous?.opens ?: 0L) + post.opens).boundedStat(),
            viewingMs = ((previous?.viewingMs ?: 0L) + post.videoMs + post.imageMs).boundedStat(),
        )
    }
    return merged
}

private fun totals(codices: List<StatsShareCodex>) = StatsShareTotals(
    watchingMs = codices.sumOf { it.watchingMs },
    browsingMs = codices.sumOf { it.browsingMs },
    entries = codices.sumOf { it.entries },
    postOpens = codices.sumOf { it.postOpens },
)

private fun comparedCodices(yours: List<StatsShareCodex>, theirs: List<StatsShareCodex>): List<ComparedCodex> {
    fun byName(codices: List<StatsShareCodex>) = codices.groupBy { it.name.orEmpty().trim().lowercase() }
    val yourNames = byName(yours)
    val theirNames = byName(theirs)
    return (yourNames.keys + theirNames.keys).map { key ->
        ComparedCodex(
            name = (yourNames[key] ?: theirNames[key]).orEmpty().first().name.orEmpty(),
            yourWatchingMs = yourNames[key].orEmpty().sumOf { it.watchingMs },
            theirWatchingMs = theirNames[key].orEmpty().sumOf { it.watchingMs },
        )
    }.sortedWith(compareByDescending<ComparedCodex> { maxOf(it.yourWatchingMs, it.theirWatchingMs) }.thenBy { it.name })
}
