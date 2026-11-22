package com.theoriacodex.app.recommend

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.creator.creatorProfileActions
import com.theoriacodex.app.related.LocalRelatedPostsSupport
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken

/** A trail a card offers to fork into. */
internal class ForYouFork(val key: String, val label: String, val request: TrailRequest)

/**
 * The forks [post]'s card offers: up to [maxTags] of its most characteristic [tags] (most first) that neither the
 * level's search nor the card's own lane already covers ([covered], as [normalizeMatchToken] spells them), then its
 * [creator] when browsable, then Similar when its source answers it.
 */
internal fun forYouForks(
    post: Post,
    tags: List<String>,
    covered: Set<String>,
    creator: CreatorProfile?,
    similarSupported: Boolean,
    levelQuery: Query?,
    maxTags: Int = 2,
): List<ForYouFork> {
    val fresh = tags.asSequence().filter { normalizeMatchToken(it) !in covered }.distinctBy(::normalizeMatchToken).take(maxTags)
    return fresh.map { ForYouFork("tag:$it", it.replace('_', ' '), TrailRequest.Tag(it, post.id.source, levelQuery, post)) }.toList() +
        listOfNotNull(
            creator?.let { ForYouFork("creator", it.displayName, TrailRequest.Creator(it, post)) },
            ForYouFork(SIMILAR_FORK, "Similar", TrailRequest.Similar(post)).takeIf { similarSupported },
        )
}

/** What a card says about why it is here: its lane, or the like or save that asked for it. */
internal fun DeckOrigin.label(): String = when (this) {
    is DeckOrigin.Lane -> label
    is DeckOrigin.Similar -> "Like one you liked"
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
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
        if (origin != null) {
            Text(
                origin.label(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp).testTag(FOR_YOU_ORIGIN_TAG),
            )
        }
        ForYouForkRow(forks, owner::fork)
    }
}

/**
 * A card's forks on one line. Similar keeps its word. The others keep theirs while the row has room; once it is full
 * the longest shorten first, so a long tag or name never pushes the forks after it out of reach. The row sits in the
 * dragged card, so it does not scroll.
 */
@Composable
internal fun ForYouForkRow(forks: List<ForYouFork>, onFork: (TrailRequest) -> Unit) {
    Layout(content = { forks.forEach { ForkButton(it, onFork) } }) { measurables, constraints ->
        val gap = FORK_GAP.roundToPx()
        val wanted = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val shrinks = forks.map { it.key != SIMILAR_FORK }
        val room = constraints.maxWidth - gap * (forks.size - 1) - wanted.filterIndexed { index, _ -> !shrinks[index] }.sum()
        val shared = forkWidths(wanted.filterIndexed { index, _ -> shrinks[index] }, room).iterator()
        val placeables = measurables.mapIndexed { index, measurable ->
            val width = if (shrinks[index]) shared.next() else wanted[index]
            measurable.measure(constraints.copy(minWidth = width, maxWidth = width, minHeight = 0))
        }
        val width = placeables.sumOf { it.width } + gap * (placeables.size - 1).coerceAtLeast(0)
        layout(constraints.constrainWidth(width), constraints.constrainHeight(placeables.maxOfOrNull { it.height } ?: 0)) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, 0)
                x += placeable.width + gap
            }
        }
    }
}

/**
 * The widths forks that want [wanted] get in [available]: the narrowest first, each what it wants up to an equal
 * share of what is left, so room a short fork does not use goes to the longer ones.
 */
internal fun forkWidths(wanted: List<Int>, available: Int): List<Int> {
    val widths = IntArray(wanted.size)
    var left = available.coerceAtLeast(0)
    wanted.indices.sortedBy { wanted[it] }.forEachIndexed { rank, index ->
        widths[index] = minOf(wanted[index], left / (wanted.size - rank))
        left -= widths[index]
    }
    return widths.asList()
}

@Composable
private fun ForkButton(fork: ForYouFork, onFork: (TrailRequest) -> Unit) {
    Text(
        fork.label,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClickLabel = "Open ${fork.label}") { onFork(fork.request) }
            .height(36.dp)
            .wrapContentHeight()
            .padding(horizontal = 12.dp)
            .testTag("$FOR_YOU_FORK_TAG_PREFIX${fork.key}"),
    )
}

private val FORK_GAP = 6.dp
private const val SIMILAR_FORK = "similar"

const val FOR_YOU_ORIGIN_TAG = "for-you-origin"
const val FOR_YOU_FORK_TAG_PREFIX = "for-you-fork-"
