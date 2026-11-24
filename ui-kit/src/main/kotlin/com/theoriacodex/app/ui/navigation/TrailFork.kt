package com.theoriacodex.app.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.theoriacodex.app.ui.components.TrailChipIcons
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.tags.normalizeMatchToken

sealed interface TrailFork {
    val key: String
    val label: String
    val icon: ImageVector?

    data class Creator(val creator: CreatorProfile) : TrailFork {
        override val key get() = "creator"
        override val label get() = creator.displayName
        override val icon get() = TrailChipIcons.Creator
    }

    data object Similar : TrailFork {
        override val key get() = "similar"
        override val label get() = "Similar"
        override val icon get() = TrailChipIcons.Similar
    }

    data class Tag(val tag: String) : TrailFork {
        override val key get() = "tag:$tag"
        override val label get() = tag.replace('_', ' ')
        override val icon get() = null
    }
}

fun trailForks(
    creator: CreatorProfile?,
    similarSupported: Boolean,
    tags: List<String>,
    covered: Collection<String>,
): List<TrailFork> {
    val searched = covered.mapTo(mutableSetOf(), ::normalizeMatchToken)
    val fresh = tags.asSequence().filter { normalizeMatchToken(it) !in searched }.distinctBy(::normalizeMatchToken).take(MAX_TAG_FORKS)
    return listOfNotNull(creator?.let(TrailFork::Creator), TrailFork.Similar.takeIf { similarSupported }) + fresh.map(TrailFork::Tag)
}

fun TrailFork.request(post: Post, levelQuery: Query?): TrailRequest = when (this) {
    is TrailFork.Creator -> TrailRequest.Creator(creator, post)
    TrailFork.Similar -> TrailRequest.Similar(post)
    is TrailFork.Tag -> TrailRequest.Tag(tag, post.id.source, levelQuery, post)
}

private const val MAX_TAG_FORKS = 5
