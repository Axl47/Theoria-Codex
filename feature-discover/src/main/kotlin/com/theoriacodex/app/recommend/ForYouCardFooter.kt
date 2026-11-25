package com.theoriacodex.app.recommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.creator.creatorProfileActions
import com.theoriacodex.app.related.LocalRelatedPostsSupport
import com.theoriacodex.app.ui.components.TrailChip
import com.theoriacodex.app.ui.components.TrailChipGap
import com.theoriacodex.app.ui.navigation.TrailFork
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.app.ui.navigation.request
import com.theoriacodex.app.ui.navigation.trailForks
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey

internal class ForYouPill(val key: String, val label: String, val icon: ImageVector?, val origin: Boolean, val open: () -> Unit)

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
    val pills = remember(post, affinity, feedShare, similarSupported, creatorBrowsingSources, level, origin) {
        val creator = creatorProfileActions(post, creatorBrowsingSources).firstNotNullOfOrNull { it.profile }
            ?.takeUnless { (level.fork as? TrailRequest.Creator)?.creator == it }
        val similar = similarSupported && (level.fork as? TrailRequest.Similar)?.from?.id != post.id
        val covered = level.query?.includeTags.orEmpty() + if (origin is DeckOrigin.Lane) owner.laneTags(post.id) else emptyList()
        val forks = trailForks(creator, similar, tagAffinities(post, affinity, feedShare), covered)
        val leading = when (origin) {
            is DeckOrigin.Similar -> TrailFork.Similar.takeIf { similar }
            is DeckOrigin.Creator -> TrailFork.Creator(origin.creator)
            is DeckOrigin.Lane, null -> null
        }
        val open = { fork: TrailFork -> { owner.fork(fork.request(post, level.query)) } }
        val lead = when (origin) {
            is DeckOrigin.Lane -> ForYouPill(ORIGIN_KEY, origin.label, Icons.Outlined.AutoAwesome, origin = true) { owner.focusLane(origin.laneId, post) }
            else -> leading?.let { ForYouPill(ORIGIN_KEY, it.label, it.icon, origin = true, open(it)) }
        }
        listOfNotNull(lead) + forks.filterNot { it == leading }.map { ForYouPill(it.key, it.label, it.icon, origin = false, open(it)) }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        key(level.id) { ForYouPillRow(pills) }
    }
}

@Composable
internal fun ForYouPillRow(pills: List<ForYouPill>) {
    val state = rememberLazyListState()
    LazyRow(
        Modifier.fillMaxWidth().testTag(FOR_YOU_FORK_ROW_TAG),
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(TrailChipGap),
        verticalAlignment = Alignment.CenterVertically,
        userScrollEnabled = state.canScrollForward || state.canScrollBackward,
    ) {
        items(pills, key = { it.key }) { pill ->
            val colors = MaterialTheme.colorScheme
            TrailChip(
                pill.label,
                pill.icon,
                container = if (pill.origin) colors.primaryContainer else colors.surfaceContainerHigh,
                content = if (pill.origin) colors.onPrimaryContainer else colors.onSurface,
                onClickLabel = "Open ${pill.label}",
                onClick = pill.open,
                modifier = Modifier.testTag(if (pill.origin) FOR_YOU_ORIGIN_TAG else "$FOR_YOU_FORK_TAG_PREFIX${pill.key}"),
            )
        }
    }
}

private const val ORIGIN_KEY = "origin"

const val FOR_YOU_ORIGIN_TAG = "for-you-origin"
const val FOR_YOU_FORK_ROW_TAG = "for-you-forks"
const val FOR_YOU_FORK_TAG_PREFIX = "for-you-fork-"
