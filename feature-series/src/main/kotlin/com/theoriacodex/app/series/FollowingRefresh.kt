package com.theoriacodex.app.series

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theoriacodex.data.repository.CheckProgress
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.domain.series.seriesKind

internal sealed interface RefreshPill {
    data class Pulling(val label: String, val pull: Float) : RefreshPill {
        val armed: Boolean get() = pull > 1f
    }

    data class Checking(val progress: CheckProgress) : RefreshPill {
        val label: String get() = if (progress.total == 0) "Checking" else "${progress.done} of ${progress.total} series checked"
    }

    data class Found(val label: String) : RefreshPill
}

internal fun refreshPill(checking: CheckProgress?, found: List<SeriesUpdate>, pull: Float, words: EntryWords?): RefreshPill? = when {
    checking != null -> RefreshPill.Checking(checking)
    pull > 0f -> RefreshPill.Pulling(words?.let { "Check for new ${it.many}" } ?: "Check for updates", pull)
    found.isNotEmpty() -> RefreshPill.Found(foundLabel(found))
    else -> null
}

private fun foundLabel(found: List<SeriesUpdate>): String =
    found.groupBy { it.series.id.source.seriesKind.words }
        .entries.sortedBy { it.key }
        .joinToString(" · ") { (words, updates) -> words.new(updates.sumOf { it.newEntries.size }) }

@Composable
internal fun FollowingRefreshBox(
    checking: CheckProgress?,
    found: List<SeriesUpdate>,
    words: EntryWords?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    var released by remember { mutableStateOf(false) }
    if (checking != null) released = true
    PullToRefreshBox(
        isRefreshing = checking != null,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
        indicator = {
            val fraction = pullState.distanceFraction
            if (released && checking == null && fraction == 0f) released = false
            val pull = if (released || pullState.isAnimating) 0f else fraction
            RefreshPillView(refreshPill(checking, found, pull, words), Modifier.align(Alignment.TopCenter))
        },
    ) { content() }
}

@Composable
internal fun RefreshPillView(pill: RefreshPill?, modifier: Modifier = Modifier) {
    val pinned = pill is RefreshPill.Checking || pill is RefreshPill.Found
    var lastPinned by remember { mutableStateOf(pill) }
    if (pinned) lastPinned = pill
    Box(modifier.padding(top = 8.dp)) {
        AnimatedVisibility(visible = pinned, enter = fadeIn(), exit = fadeOut()) {
            when (val shown = lastPinned) {
                is RefreshPill.Checking -> CheckingPill(shown)
                is RefreshPill.Found -> FoundPill(shown)
                else -> Unit
            }
        }
        if (pill is RefreshPill.Pulling) PullingPill(pill)
    }
}

@Composable
private fun PullingPill(pill: RefreshPill.Pulling) {
    val reach = pill.pull.coerceIn(0f, 1f)
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(if (pill.armed) colors.primary else colors.surfaceVariant, label = "pull-pill")
    Surface(
        shape = PILL,
        color = background,
        contentColor = if (pill.armed) colors.onPrimary else colors.onSurface,
        shadowElevation = 4.dp,
        modifier = Modifier.graphicsLayer {
            translationY = (pill.pull.coerceAtMost(PULL_TRAVEL_CAP) - 1f) * PULL_TRAVEL.toPx()
            alpha = reach
            val scale = PULL_MIN_SCALE + (1f - PULL_MIN_SCALE) * reach
            scaleX = scale
            scaleY = scale
        },
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(pill.label, style = MaterialTheme.typography.labelLarge)
            if (pill.armed) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(GLYPH))
            } else {
                CircularProgressIndicator(progress = { reach }, modifier = Modifier.size(GLYPH), strokeWidth = 2.5.dp)
            }
        }
    }
}

@Composable
private fun CheckingPill(pill: RefreshPill.Checking) {
    Surface(shape = PROGRESS_SHAPE, color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 4.dp, modifier = Modifier.animateContentSize()) {
        Column(Modifier.width(PROGRESS_WIDTH).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(pill.label, style = MaterialTheme.typography.labelLarge)
            val progress = pill.progress
            if (progress.total == 0) {
                LinearProgressIndicator()
            } else {
                LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total })
            }
        }
    }
}

@Composable
private fun FoundPill(pill: RefreshPill.Found) {
    Surface(shape = PILL, color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 4.dp) {
        Text(
            pill.label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private val PILL = RoundedCornerShape(percent = 50)
private val PROGRESS_SHAPE = RoundedCornerShape(18.dp)
private val PROGRESS_WIDTH = 220.dp
private val GLYPH = 20.dp
private val PULL_TRAVEL = 56.dp
private const val PULL_TRAVEL_CAP = 1.5f
private const val PULL_MIN_SCALE = 0.85f
