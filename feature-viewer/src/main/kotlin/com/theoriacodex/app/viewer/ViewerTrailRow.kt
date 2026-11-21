package com.theoriacodex.app.viewer

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.creator.creatorProfileActions
import com.theoriacodex.app.recommend.LocalLikedTagCounts
import com.theoriacodex.app.recommend.postTagSignals
import com.theoriacodex.app.related.LocalRelatedPostsSupport
import com.theoriacodex.app.ui.navigation.LocalTrailOpener
import com.theoriacodex.app.ui.navigation.TrailOpener
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The row under the Viewer's top bar: the trail's crumbs (when this level is a fork), then chips that fork a new trail
 * from [post]: its creator, Similar, and its most characteristic tags. A fork opens only once its first page is in; until
 * then its chip spins, and an empty or failed fork says so on the chip.
 */
@Composable
internal fun ViewerTrailRow(post: Post, trail: ViewerTrail, creatorBrowsingSources: Set<SourceKey>, modifier: Modifier = Modifier) {
    val opener = LocalTrailOpener.current
    val similarSupported = LocalRelatedPostsSupport.current(post.id.source)
    val liked by LocalLikedTagCounts.current.collectAsStateWithLifecycle()
    val chips = remember(post, liked, trail.levelQuery, similarSupported, creatorBrowsingSources, opener) {
        if (opener == TrailOpener.None) emptyList() else viewerTrailChips(
            creatorProfileActions(post, creatorBrowsingSources).firstNotNullOfOrNull { it.profile },
            similarSupported, postTagSignals(post, liked), trail.levelQuery,
        )
    }
    val forks = remember(post.id) { ViewerTrailForks() }
    val scope = rememberCoroutineScope()
    val crumbs = trail.crumbs.takeIf { it.size > 1 }.orEmpty()
    if (crumbs.isEmpty() && chips.isEmpty()) return
    LazyRow(
        modifier.fillMaxWidth().testTag("viewer-trail-row"),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(crumbs) { index, crumb ->
            ViewerCrumbChip(crumb, index, current = index == crumbs.lastIndex) { trail.onCrumb(index) }
        }
        items(chips, key = { it.key }) { chip ->
            ViewerForkChip(chip, busy = forks.busy == chip.key, failed = forks.failed == chip.key) {
                scope.launch { forks.open(chip.key) { opener.openTrail(chip.request(post, trail.levelQuery)) } }
            }
        }
    }
}

/** Which fork is loading, and which just came back empty, for one post's chips. */
@Stable
private class ViewerTrailForks {
    var busy by mutableStateOf<String?>(null)
        private set
    var failed by mutableStateOf<String?>(null)
        private set

    suspend fun open(key: String, start: suspend () -> Boolean) {
        if (busy != null) return
        busy = key
        failed = null
        val opened = try {
            start()
        } finally {
            busy = null
        }
        if (opened) return
        failed = key
        delay(FAILED_FORK_MS)
        if (failed == key) failed = null
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
    Row(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .background(ViewerVeil.Capsule)
            .clickable(enabled = !busy, role = Role.Button, onClickLabel = "Start a trail", onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag("viewer-trail-chip-${chip.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            else -> chip.icon()?.let { Icon(it, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
        Text(
            if (failed) "No posts" else chip.label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
    }
}

private fun ViewerTrailChip.icon(): ImageVector? = when (this) {
    is ViewerTrailChip.Creator -> Icons.Outlined.Person
    ViewerTrailChip.Similar -> Icons.Outlined.GridView
    is ViewerTrailChip.Tag -> null
}

private const val FAILED_FORK_MS = 2_500L

/**
 * Forks a trail from a tag tapped in [post]'s info sheet, searched like [levelQuery], and calls [onOpened] once the fork
 * is showing. The sheet has no chip to spin, so an empty fork says so in a toast. Null where no trail can open.
 */
@Composable
internal fun rememberViewerTagFork(post: Post, levelQuery: Query?, onOpened: () -> Unit): ((SearchTerm) -> Unit)? {
    val opener = LocalTrailOpener.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val latestOnOpened by rememberUpdatedState(onOpened)
    return remember(opener, post, levelQuery) {
        if (opener == TrailOpener.None) return@remember null
        var opening = false
        { term ->
            if (!opening) scope.launch {
                opening = true
                val opened = try {
                    opener.openTrail(TrailRequest.Tag(term.value, post.id.source, levelQuery, post))
                } finally {
                    opening = false
                }
                if (opened) latestOnOpened() else Toast.makeText(context, "No posts for ${term.value.replace('_', ' ')}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
