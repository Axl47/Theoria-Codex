package com.theoriacodex.app.viewer

import androidx.compose.runtime.Immutable
import com.theoriacodex.app.recommend.TagAffinity
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query

/** A Viewer level in the current trail, oldest first; the last one is on screen. [trailKey] names the trail that opened it. */
data class ViewerCrumb(val label: String, val trailKey: String? = null)

/**
 * The trail around one Viewer level: the crumbs of the Viewer levels stacked on the back stack, the query this level
 * shows (its tags are not offered again as forks), and [onCrumb], which pops back to the crumb at an index.
 */
@Immutable
class ViewerTrail(val crumbs: List<ViewerCrumb>, val levelQuery: Query?, val onCrumb: (Int) -> Unit) {
    /** The trail this level is, which it does not offer as a fork again. */
    val levelTrailKey: String? get() = crumbs.lastOrNull()?.trailKey

    companion object {
        val None = ViewerTrail(emptyList(), null) {}
    }
}

/** A way to start a new trail from the current post: its creator, posts like it, or one of its tags. */
internal sealed interface ViewerTrailChip {
    val key: String
    val label: String

    data class Creator(val creator: CreatorProfile) : ViewerTrailChip {
        override val key get() = "creator"
        override val label get() = creator.displayName
    }

    data object Similar : ViewerTrailChip {
        override val key get() = "similar"
        override val label get() = "Similar"
    }

    data class Tag(val tag: String) : ViewerTrailChip {
        override val key get() = "tag:$tag"
        override val label get() = tag.replace('_', ' ')
    }
}

/**
 * The fork chips for [post]: its [creator] when browsable, Similar when its source answers it, then its most
 * characteristic tags ([affinities], most first) that are not universal and that [levelQuery] does not already
 * search, at most [maxTags].
 */
internal fun viewerTrailChips(
    creator: CreatorProfile?,
    similarSupported: Boolean,
    affinities: List<TagAffinity>,
    levelQuery: Query?,
    maxTags: Int = VIEWER_TRAIL_TAG_CHIPS,
): List<ViewerTrailChip> {
    val searched = levelQuery?.includeTags.orEmpty().mapTo(mutableSetOf()) { it.lowercase() }
    val tags = affinities.asSequence().filterNot { it.universal }.map { it.tag }
        .filter { it.lowercase() !in searched }.distinct().take(maxTags)
    return listOfNotNull(creator?.let(ViewerTrailChip::Creator), ViewerTrailChip.Similar.takeIf { similarSupported }) +
        tags.map(ViewerTrailChip::Tag).toList()
}

/** Names the trail [this] opens, the same for every request for that trail. */
fun TrailRequest.trailKey(): String = when (this) {
    is TrailRequest.Tag -> "tag:${source.id}:$tag"
    is TrailRequest.Creator -> "creator:${creator.source.id}:${creator.profileId ?: creator.displayName}"
    is TrailRequest.Similar -> "similar:${from.id.source.id}:${from.id.sourcePostId}"
}

/** The trail [chip] starts from [post] at a level showing [levelQuery]. */
internal fun ViewerTrailChip.request(post: Post, levelQuery: Query?): TrailRequest = when (this) {
    is ViewerTrailChip.Creator -> TrailRequest.Creator(creator, post)
    ViewerTrailChip.Similar -> TrailRequest.Similar(post)
    is ViewerTrailChip.Tag -> TrailRequest.Tag(tag, post.id.source, levelQuery, post)
}

private const val VIEWER_TRAIL_TAG_CHIPS = 5
