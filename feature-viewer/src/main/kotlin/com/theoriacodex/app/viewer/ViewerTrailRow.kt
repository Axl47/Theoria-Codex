package com.theoriacodex.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.theoriacodex.app.tags.TagActionMenu
import com.theoriacodex.app.tags.TagChipAction
import com.theoriacodex.app.tags.rememberSavedTagAction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.creator.creatorProfileActions
import com.theoriacodex.app.recommend.LocalTagAffinityInputs
import com.theoriacodex.app.recommend.feedTagShare
import com.theoriacodex.app.recommend.tagAffinities
import com.theoriacodex.app.related.LocalRelatedPostsSupport
import com.theoriacodex.app.ui.components.TrailChip
import com.theoriacodex.app.ui.components.TrailChipGap
import com.theoriacodex.app.ui.components.TrailCrumb
import com.theoriacodex.app.ui.components.TrailCrumbArrow
import com.theoriacodex.app.ui.navigation.LocalTrailOpener
import com.theoriacodex.app.ui.navigation.TrailFork
import com.theoriacodex.app.ui.navigation.TrailOpener
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.app.ui.navigation.request
import com.theoriacodex.app.ui.navigation.trailForks
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
        if (opener == TrailOpener.None) emptyList() else trailForks(
            creatorProfileActions(post, creatorBrowsingSources).firstNotNullOfOrNull { it.profile },
            similarSupported,
            tagAffinities(post, affinity, feedShare),
            trail.levelQuery?.includeTags.orEmpty(),
        ).filterNot { it.request(post, trail.levelQuery).trailKey() == trail.levelTrailKey }
    }
    val active = forks.busy ?: forks.failed
    val shown = if (active == null || chips.any { it.key == active.key }) chips else listOf(active) + chips
    val crumbs = trail.crumbs.takeIf { it.size > 1 }.orEmpty()
    val canAdd = opener != TrailOpener.None
    if (crumbs.isEmpty() && shown.isEmpty() && !canAdd) return
    var adding by remember { mutableStateOf(false) }
    val start: (TrailFork) -> Boolean = { chip ->
        onInteraction()
        forks.open(chip, post.id) { opener.openTrail(chip.request(post, trail.levelQuery)) }
    }
    LazyRow(
        modifier.fillMaxWidth().testTag("viewer-trail-row"),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(TrailChipGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(crumbs) { index, crumb -> ViewerTrailCrumb(crumb, index, crumbs, trail, forks) }
        items(shown, key = { it.key }) { chip ->
            ViewerForkChip(chip, post.id.source, forks) { start(chip) }
        }
        if (canAdd) item(key = "add") { ViewerAddTagChip(enabled = forks.busy == null) { onInteraction(); adding = true } }
    }
    if (adding) {
        HoldChrome(forks)
        TrailTagSheet(post.id.source, onDismiss = { adding = false }) { tag ->
            if (start(TrailFork.Tag(tag))) adding = false
        }
    }
}

@Composable
private fun ViewerTrailCrumb(crumb: ViewerCrumb, index: Int, crumbs: List<ViewerCrumb>, trail: ViewerTrail, forks: ViewerTrailForks) {
    val remove = TagChipAction("Remove from trail") { trail.onRemove(index) }.takeIf { crumbs.removal(index) != null }
    val save = crumb.tag?.let { rememberSavedTagAction(it.source, it.value) }
    val actions = listOfNotNull(remove, save)
    var menuOpen by remember { mutableStateOf(false) }
    if (menuOpen) HoldChrome(forks)
    Box {
        TrailCrumb(
            crumb.label,
            current = index == crumbs.lastIndex,
            color = Color.White,
            dimColor = Color.White.copy(alpha = ViewerVeil.SECONDARY_TEXT_ALPHA),
            arrow = TrailCrumbArrow.After,
            maxWidth = 140.dp,
            verticalPadding = 6.dp,
            testTag = "viewer-trail-crumb-$index",
            onLongClick = { menuOpen = true }.takeIf { actions.isNotEmpty() },
            onLongClickLabel = "Manage ${crumb.label}",
        ) { trail.onCrumb(index) }
        TagActionMenu(menuOpen, actions) { menuOpen = false }
    }
}

@Composable
private fun ViewerForkChip(chip: TrailFork, source: SourceKey, forks: ViewerTrailForks, onClick: () -> Unit) {
    val save = (chip as? TrailFork.Tag)?.let { rememberSavedTagAction(source, it.tag) }
    val busy = forks.busy?.key == chip.key
    val failed = forks.failed?.key == chip.key
    var menuOpen by remember { mutableStateOf(false) }
    if (menuOpen) HoldChrome(forks)
    Box {
        TrailChip(
            if (failed) "No posts" else chip.label,
            chip.icon,
            container = ViewerVeil.Capsule,
            content = Color.White,
            onClickLabel = "Start a trail",
            onClick = onClick,
            modifier = Modifier.testTag("viewer-trail-chip-${chip.key}"),
            busy = busy,
            onLongClick = { menuOpen = true }.takeIf { save != null },
            onLongClickLabel = "Manage ${chip.label}",
        )
        TagActionMenu(menuOpen, listOfNotNull(save)) { menuOpen = false }
    }
}

@Composable
private fun HoldChrome(forks: ViewerTrailForks) {
    DisposableEffect(forks) {
        val release = forks.holdChrome()
        onDispose { release() }
    }
}

@Composable
private fun ViewerAddTagChip(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(ViewerVeil.Capsule)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag("viewer-trail-add"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Add, contentDescription = "Add tag", tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun ViewerTrailReplay(trail: ViewerTrail, post: Post, forks: ViewerTrailForks) {
    val opener = LocalTrailOpener.current
    val tag = trail.replay ?: return
    val latestPost by rememberUpdatedState(post)
    LaunchedEffect(tag) {
        val from = latestPost
        forks.open(TrailFork.Tag(tag.value), from.id) { opener.openTrail(TrailRequest.Tag(tag.value, tag.source, trail.levelQuery, from)) }
    }
}

@Composable
internal fun rememberViewerTagFork(forks: ViewerTrailForks, post: Post, levelQuery: Query?, onStarted: () -> Unit): ((SearchTerm) -> Unit)? {
    val opener = LocalTrailOpener.current
    val latestOnStarted by rememberUpdatedState(onStarted)
    return remember(forks, opener, post, levelQuery) {
        if (opener == TrailOpener.None) return@remember null
        { term ->
            val chip = TrailFork.Tag(term.value)
            if (forks.open(chip, post.id) { opener.openTrail(chip.request(post, levelQuery)) }) latestOnStarted()
        }
    }
}
