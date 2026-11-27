package com.theoriacodex.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

data class TriageRequest(
    val source: ViewerStreamSource,
    val feedKey: String,
    val mode: TriageMode = TriageMode.Browse,
) {
    val codexId: String?
        get() = feedKey.takeIf { source == ViewerStreamSource.CODEX && it.startsWith(CODEX_FEED_PREFIX) }
            ?.removePrefix(CODEX_FEED_PREFIX)

    companion object {
        fun codex(codexId: String, mode: TriageMode = TriageMode.Browse) =
            TriageRequest(ViewerStreamSource.CODEX, CODEX_FEED_PREFIX + codexId, mode)
    }
}

sealed interface TriageMode {
    data object Browse : TriageMode

    data class RemoveFromCodex(val alsoIn: Set<String> = emptySet()) : TriageMode
}

data class TriageSeed(
    val posts: List<Post>,
    val filters: SearchVisibilityFilters,
    val lengthFilterActive: Boolean,
    private val filterInRange: (posts: List<Post>, dateRange: DateRange.Between?, knownDurationMsByPostId: Map<PostId, Long>) -> List<Post>,
) {
    fun filter(posts: List<Post>, knownDurationMsByPostId: Map<PostId, Long>): List<Post> =
        filterInRange(posts, filters.dateRange, knownDurationMsByPostId)

    companion object {
        val Unfiltered = TriageSeed(emptyList(), SearchVisibilityFilters(), lengthFilterActive = false) { posts, _, _ -> posts }
    }
}

fun interface TriageOpener {
    fun openTriage(request: TriageRequest, seed: TriageSeed)

    companion object {
        val None = TriageOpener { _, _ -> }
    }
}

val LocalTriageOpener = staticCompositionLocalOf { TriageOpener.None }

@Composable
fun rememberTriageAction(
    source: ViewerStreamSource,
    feedKey: String?,
    seed: TriageSeed,
    mode: TriageMode = TriageMode.Browse,
): (() -> Unit)? {
    val opener = LocalTriageOpener.current
    val latest = rememberUpdatedState(seed)
    val hasPosts = seed.posts.isNotEmpty()
    return remember(opener, source, feedKey, hasPosts, mode) {
        if (opener === TriageOpener.None || feedKey.isNullOrBlank() || !hasPosts) null
        else ({ opener.openTriage(TriageRequest(source, feedKey, mode), latest.value) })
    }
}

private const val CODEX_FEED_PREFIX = "codex:"
