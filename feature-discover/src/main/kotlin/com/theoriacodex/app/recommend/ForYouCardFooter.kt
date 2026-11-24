package com.theoriacodex.app.recommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.creator.creatorProfileActions
import com.theoriacodex.app.related.LocalRelatedPostsSupport
import com.theoriacodex.app.ui.components.TrailChip
import com.theoriacodex.app.ui.components.TrailChipGap
import com.theoriacodex.app.ui.components.TrailChipIcons
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken

/** A trail a card offers to fork into. */
internal class ForYouFork(val key: String, val label: String, val request: TrailRequest)

/**
 * The forks [post]'s card offers, in the Viewer's trail-row order: its [creator] when browsable, Similar when its source
 * answers it, then up to [maxTags] of its most characteristic [tags] (most first) that neither the level's search nor the
 * card's own lane already covers ([covered], as [normalizeMatchToken] spells them).
 */
internal fun forYouForks(
    post: Post,
    tags: List<String>,
    covered: Set<String>,
    creator: CreatorProfile?,
    similarSupported: Boolean,
    levelQuery: Query?,
    maxTags: Int = MAX_TAG_FORKS,
): List<ForYouFork> {
    val fresh = tags.asSequence().filter { normalizeMatchToken(it) !in covered }.distinctBy(::normalizeMatchToken).take(maxTags)
    return listOfNotNull(
        creator?.let { ForYouFork("creator", it.displayName, TrailRequest.Creator(it, post)) },
        ForYouFork(SIMILAR_FORK, "Similar", TrailRequest.Similar(post)).takeIf { similarSupported },
    ) + fresh.map { ForYouFork("tag:$it", it.replace('_', ' '), TrailRequest.Tag(it, post.id.source, levelQuery, post)) }
}

/** What a card says about why it is here: its lane, or the like or save that asked for it. */
internal fun DeckOrigin.label(): String = when (this) {
    is DeckOrigin.Lane -> label
    is DeckOrigin.Similar -> if (saved) "Like one you saved" else "Like one you liked"
    is DeckOrigin.Creator -> "More by $name"
}

/**
 * The band under a card's media: why the card is in the Mix on one line, then its forks on the next. [feedShare] is how common each tag is on
 * this level, so a tag on nearly every post here, such as a trail's own, is not offered.
 */
@Composable
internal fun ForYouCardFooter(
    owner: ForYouDeckViewModel,
    level: ForYouLevel,
    post: Post,
    feedShare: Map<String, Double>,
    creatorBrowsingSources: Set<SourceKey>,
) {
    val similarSupported = LocalRelatedPostsSupport.current(post.id.source)
    val affinity by LocalTagAffinityInputs.current.collectAsStateWithLifecycle()
    val origin = if (level.fork == null) owner.originOf(post.id) else null
    val forks = remember(post, affinity, feedShare, similarSupported, creatorBrowsingSources, level, origin) {
        // Not on that creator's own trail, nor on a card that is here because of its creator, whose label names them.
        val creator = creatorProfileActions(post, creatorBrowsingSources).firstNotNullOfOrNull { it.profile }
            ?.takeUnless { (level.fork as? TrailRequest.Creator)?.creator == it || origin is DeckOrigin.Creator }
        val covered = (level.query?.includeTags.orEmpty() + owner.laneTags(post.id)).mapTo(mutableSetOf(), ::normalizeMatchToken)
        val tags = tagAffinities(post, affinity, feedShare).filter { it.informative }.map { it.tag }
        forYouForks(post, tags, covered, creator, similarSupported, level.query)
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        if (origin != null) {
            Text(
                origin.label(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 12.dp, bottom = 4.dp).testTag(FOR_YOU_ORIGIN_TAG),
            )
        }
        ForYouForkRow(forks, owner::fork)
    }
}

/**
 * A card's forks on one line, scrolling like the Viewer's trail row. The row sits in the dragged card, so it takes a
 * sideways drag only while its forks overflow it; when they fit, the drag swipes the card.
 */
@Composable
internal fun ForYouForkRow(forks: List<ForYouFork>, onFork: (TrailRequest) -> Unit) {
    val state = rememberLazyListState()
    LazyRow(
        Modifier.fillMaxWidth().testTag(FOR_YOU_FORK_ROW_TAG),
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(TrailChipGap),
        verticalAlignment = Alignment.CenterVertically,
        userScrollEnabled = state.canScrollForward || state.canScrollBackward,
    ) {
        items(forks, key = { it.key }) { fork ->
            TrailChip(
                fork.label,
                when (fork.request) {
                    is TrailRequest.Creator -> TrailChipIcons.Creator
                    is TrailRequest.Similar -> TrailChipIcons.Similar
                    is TrailRequest.Tag -> null
                },
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = MaterialTheme.colorScheme.onSurface,
                onClickLabel = "Open ${fork.label}",
                onClick = { onFork(fork.request) },
                modifier = Modifier.testTag("$FOR_YOU_FORK_TAG_PREFIX${fork.key}"),
            )
        }
    }
}

private const val SIMILAR_FORK = "similar"

/** As many tags as the Viewer's trail row offers. */
private const val MAX_TAG_FORKS = 5

const val FOR_YOU_ORIGIN_TAG = "for-you-origin"
const val FOR_YOU_FORK_ROW_TAG = "for-you-forks"
const val FOR_YOU_FORK_TAG_PREFIX = "for-you-fork-"
