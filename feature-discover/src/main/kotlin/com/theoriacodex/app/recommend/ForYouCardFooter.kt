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
import com.theoriacodex.app.ui.navigation.TrailFork
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.app.ui.navigation.request
import com.theoriacodex.app.ui.navigation.trailForks
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey

internal fun DeckOrigin.label(): String = when (this) {
    is DeckOrigin.Lane -> label
    is DeckOrigin.Similar -> if (saved) "Like one you saved" else "Like one you liked"
    is DeckOrigin.Creator -> "More by $name"
}

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
        val creator = creatorProfileActions(post, creatorBrowsingSources).firstNotNullOfOrNull { it.profile }
            ?.takeUnless { (level.fork as? TrailRequest.Creator)?.creator == it || origin is DeckOrigin.Creator }
        val covered = level.query?.includeTags.orEmpty() + owner.laneTags(post.id)
        trailForks(creator, similarSupported, tagAffinities(post, affinity, feedShare), covered)
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
        ForYouForkRow(forks) { owner.fork(it.request(post, level.query)) }
    }
}

@Composable
internal fun ForYouForkRow(forks: List<TrailFork>, onFork: (TrailFork) -> Unit) {
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
                fork.icon,
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = MaterialTheme.colorScheme.onSurface,
                onClickLabel = "Open ${fork.label}",
                onClick = { onFork(fork) },
                modifier = Modifier.testTag("$FOR_YOU_FORK_TAG_PREFIX${fork.key}"),
            )
        }
    }
}

const val FOR_YOU_ORIGIN_TAG = "for-you-origin"
const val FOR_YOU_FORK_ROW_TAG = "for-you-forks"
const val FOR_YOU_FORK_TAG_PREFIX = "for-you-fork-"
