package com.theoriacodex.app.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.creator.creatorProfileActions
import com.theoriacodex.app.recommend.LocalTagAffinityInputs
import com.theoriacodex.app.recommend.feedTagShare
import com.theoriacodex.app.recommend.tagAffinities
import com.theoriacodex.app.related.LocalRelatedPostsSupport
import com.theoriacodex.app.ui.components.TrailChip
import com.theoriacodex.app.ui.components.TrailChipGap
import com.theoriacodex.app.ui.components.TrailChipIcons
import com.theoriacodex.app.ui.navigation.LocalTrailOpener
import com.theoriacodex.app.ui.navigation.TrailOpener
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey

@Composable
internal fun ViewerTrailRow(
    post: Post,
    levelPosts: List<Post>,
    trail: ViewerTrail,
    creatorBrowsingSources: Set<SourceKey>,
    forks: ViewerTrailForks,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val opener = LocalTrailOpener.current
    val similarSupported = LocalRelatedPostsSupport.current(post.id.source)
    val affinity by LocalTagAffinityInputs.current.collectAsStateWithLifecycle()
    val feedShare = remember(levelPosts) { feedTagShare(levelPosts) }
    val chips = remember(post, affinity, feedShare, trail, similarSupported, creatorBrowsingSources, opener) {
        if (opener == TrailOpener.None) emptyList() else viewerTrailChips(
            creatorProfileActions(post, creatorBrowsingSources).firstNotNullOfOrNull { it.profile },
            similarSupported, tagAffinities(post, affinity, feedShare), trail.levelQuery,
        ).filterNot { it.request(post, trail.levelQuery).trailKey() == trail.levelTrailKey }
    }
    val active = forks.busy ?: forks.failed
    val shown = if (active == null || chips.any { it.key == active.key }) chips else listOf(active) + chips
    val crumbs = trail.crumbs.takeIf { it.size > 1 }.orEmpty()
    if (crumbs.isEmpty() && shown.isEmpty()) return
    LazyRow(
        modifier.fillMaxWidth().testTag("viewer-trail-row"),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(TrailChipGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(crumbs) { index, crumb ->
            ViewerCrumbChip(crumb, index, current = index == crumbs.lastIndex) { trail.onCrumb(index) }
        }
        items(shown, key = { it.key }) { chip ->
            ViewerForkChip(chip, busy = forks.busy?.key == chip.key, failed = forks.failed?.key == chip.key) {
                onInteraction()
                forks.open(chip, post.id) { opener.openTrail(chip.request(post, trail.levelQuery)) }
            }
        }
    }
}

@Composable
private fun ViewerCrumbChip(crumb: ViewerCrumb, index: Int, current: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            crumb.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) Color.White else Color.White.copy(alpha = ViewerVeil.SECONDARY_TEXT_ALPHA),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 140.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(if (current) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = "Back to ${crumb.label}", onClick = onClick))
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .testTag("viewer-trail-crumb-$index"),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = ViewerVeil.SECONDARY_TEXT_ALPHA),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ViewerForkChip(chip: ViewerTrailChip, busy: Boolean, failed: Boolean, onClick: () -> Unit) {
    TrailChip(
        if (failed) "No posts" else chip.label,
        chip.icon(),
        container = ViewerVeil.Capsule,
        content = Color.White,
        onClickLabel = "Start a trail",
        onClick = onClick,
        modifier = Modifier.testTag("viewer-trail-chip-${chip.key}"),
        busy = busy,
    )
}

private fun ViewerTrailChip.icon(): ImageVector? = when (this) {
    is ViewerTrailChip.Creator -> TrailChipIcons.Creator
    ViewerTrailChip.Similar -> TrailChipIcons.Similar
    is ViewerTrailChip.Tag -> null
}

@Composable
internal fun rememberViewerTagFork(forks: ViewerTrailForks, post: Post, levelQuery: Query?, onStarted: () -> Unit): ((SearchTerm) -> Unit)? {
    val opener = LocalTrailOpener.current
    val latestOnStarted by rememberUpdatedState(onStarted)
    return remember(forks, opener, post, levelQuery) {
        if (opener == TrailOpener.None) return@remember null
        { term ->
            val chip = ViewerTrailChip.Tag(term.value)
            if (forks.open(chip, post.id) { opener.openTrail(chip.request(post, levelQuery)) }) latestOnStarted()
        }
    }
}
