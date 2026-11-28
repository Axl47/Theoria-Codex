package com.theoriacodex.app.ui.components

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
import androidx.compose.material3.ExperimentalMaterial3Api
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

internal fun isPullArmed(pull: Float): Boolean = pull > 1f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullLabel: String,
    modifier: Modifier = Modifier,
    status: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    var released by remember { mutableStateOf(false) }
    if (refreshing) released = true
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
        indicator = {
            val fraction = pullState.distanceFraction
            if (released && !refreshing && fraction == 0f) released = false
            val pull = if (released || pullState.isAnimating) 0f else fraction
            RefreshPills(pullLabel, pull, status.takeIf { pull == 0f }, Modifier.align(Alignment.TopCenter))
        },
    ) { content() }
}

@Composable
private fun RefreshPills(pullLabel: String, pull: Float, status: (@Composable () -> Unit)?, modifier: Modifier) {
    var lastStatus by remember { mutableStateOf(status) }
    if (status != null) lastStatus = status
    Box(modifier.padding(top = 8.dp)) {
        AnimatedVisibility(visible = status != null, enter = fadeIn(), exit = fadeOut()) { lastStatus?.invoke() }
        if (pull > 0f) PullPill(pullLabel, pull)
    }
}

@Composable
private fun PullPill(label: String, pull: Float) {
    val reach = pull.coerceIn(0f, 1f)
    val armed = isPullArmed(pull)
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(if (armed) colors.primary else colors.surfaceVariant, label = "pull-pill")
    Surface(
        shape = PILL,
        color = background,
        contentColor = if (armed) colors.onPrimary else colors.onSurface,
        shadowElevation = 4.dp,
        modifier = Modifier.graphicsLayer {
            translationY = (pull.coerceAtMost(PULL_TRAVEL_CAP) - 1f) * PULL_TRAVEL.toPx()
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
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (armed) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(GLYPH))
            } else {
                CircularProgressIndicator(progress = { reach }, modifier = Modifier.size(GLYPH), strokeWidth = 2.5.dp)
            }
        }
    }
}

@Composable
fun RefreshProgressPill(label: String, progress: Float?) {
    Surface(shape = PROGRESS_SHAPE, color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 4.dp, modifier = Modifier.animateContentSize()) {
        Column(Modifier.width(PROGRESS_WIDTH).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (progress == null) LinearProgressIndicator() else LinearProgressIndicator(progress = { progress })
        }
    }
}

@Composable
fun RefreshMessagePill(label: String) {
    Surface(shape = PILL, color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 4.dp) {
        Text(
            label,
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
