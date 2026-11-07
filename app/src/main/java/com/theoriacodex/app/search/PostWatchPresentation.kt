package com.theoriacodex.app.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.theoriacodex.data.repository.ReadingPositionRepository
import com.theoriacodex.data.repository.RecentsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.data.repository.ReadingPosition
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.app.media.postMediaItems
import java.util.Locale

internal data class PostWatchPresentation(
    val positions: Map<PostId, ReadingPosition> = emptyMap(),
    val watched: Map<PostId, RecentPostEntry> = emptyMap(),
)

/**
 * A stable holder rather than the value: watch history changes every time a post opens, and a static
 * local whose value changes recomposes everything beneath it, which here is the whole app.
 */
internal val LocalPostWatchPresentation = staticCompositionLocalOf<State<PostWatchPresentation>> {
    mutableStateOf(PostWatchPresentation())
}

/** One post's slice of [PostWatchPresentation]. */
internal data class PostWatchEntry(val position: ReadingPosition?, val watched: RecentPostEntry?)

/**
 * This post's watch history, read through a derived state: every open and reading-position write replaces
 * the whole history, and a card reading the full map would recompose for every other post's change.
 */
@Composable
internal fun rememberPostWatchEntry(postId: PostId): State<PostWatchEntry> {
    val presentation = LocalPostWatchPresentation.current
    return remember(presentation, postId) {
        derivedStateOf { presentation.value.let { PostWatchEntry(it.positions[postId], it.watched[postId]) } }
    }
}

/** Watch history for every card, built off the main thread so opening a post cannot stall on it. */
@Composable
internal fun rememberPostWatchPresentation(
    positions: ReadingPositionRepository,
    recents: RecentsRepository,
): State<PostWatchPresentation> {
    val state = remember { mutableStateOf(PostWatchPresentation(), referentialEqualityPolicy()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(positions, recents, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(positions.observe(), recents.observeWatchedPosts(), ::postWatchPresentation)
                .flowOn(Dispatchers.Default)
                .collect { state.value = it }
        }
    }
    return state
}

internal fun postWatchPresentation(
    positions: List<ReadingPosition>,
    watched: List<RecentPostEntry>,
): PostWatchPresentation = PostWatchPresentation(
    positions = positions.sortedByDescending { it.lastViewedAt }.distinctBy { it.postId }.associateBy { it.postId },
    watched = watched.filter { it.section != RecentPostSection.FYP }
        .sortedByDescending { it.viewedAtEpochMs }.distinctBy { it.post.id }.associateBy { it.post.id },
)

/** Select only this page's locations; fallback must never silently display a different page. */
internal fun postForCardPreview(post: Post, mediaNumber: Int?): Post {
    if (mediaNumber == null) return post
    val media = postMediaItems(post)
    if (media.size <= 1) return post
    val selected = media[(mediaNumber - 1).coerceIn(0, media.lastIndex)]
    return post.copy(preview = selected, full = selected, media = listOf(selected))
}

internal fun relativeTimeLabel(now: Long, eventAt: Long): String {
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
