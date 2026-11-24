package com.theoriacodex.app.viewer

import androidx.compose.runtime.Immutable
import com.theoriacodex.app.recommend.TagAffinity
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query

data class ViewerCrumb(val label: String, val trailKey: String? = null)

@Immutable
class ViewerTrail(val crumbs: List<ViewerCrumb>, val levelQuery: Query?, val onCrumb: (Int) -> Unit) {
    val levelTrailKey: String? get() = crumbs.lastOrNull()?.trailKey

    companion object {
        val None = ViewerTrail(emptyList(), null) {}
    }
}

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

internal fun viewerTrailChips(
    creator: CreatorProfile?,
    similarSupported: Boolean,
    affinities: List<TagAffinity>,
    levelQuery: Query?,
    maxTags: Int = VIEWER_TRAIL_TAG_CHIPS,
): List<ViewerTrailChip> {
    val searched = levelQuery?.includeTags.orEmpty().mapTo(mutableSetOf()) { it.lowercase() }
    val tags = affinities.asSequence().filter { it.informative }.map { it.tag }
        .filter { it.lowercase() !in searched }.distinct().take(maxTags)
    return listOfNotNull(creator?.let(ViewerTrailChip::Creator), ViewerTrailChip.Similar.takeIf { similarSupported }) +
        tags.map(ViewerTrailChip::Tag).toList()
}

fun TrailRequest.trailKey(): String = when (this) {
    is TrailRequest.Tag -> "tag:${source.id}:$tag"
    is TrailRequest.Creator -> "creator:${creator.source.id}:${creator.profileId ?: creator.displayName}"
    is TrailRequest.Similar -> "similar:${from.id.source.id}:${from.id.sourcePostId}"
}

internal fun ViewerTrailChip.request(post: Post, levelQuery: Query?): TrailRequest = when (this) {
    is ViewerTrailChip.Creator -> TrailRequest.Creator(creator, post)
    ViewerTrailChip.Similar -> TrailRequest.Similar(post)
    is ViewerTrailChip.Tag -> TrailRequest.Tag(tag, post.id.source, levelQuery, post)
}

private const val VIEWER_TRAIL_TAG_CHIPS = 5
