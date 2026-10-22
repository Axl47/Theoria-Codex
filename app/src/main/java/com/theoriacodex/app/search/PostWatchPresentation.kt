package com.theoriacodex.app.search

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

internal val LocalPostWatchPresentation = staticCompositionLocalOf { PostWatchPresentation() }

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
