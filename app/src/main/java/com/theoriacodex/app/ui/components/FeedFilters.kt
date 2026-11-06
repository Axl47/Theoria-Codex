package com.theoriacodex.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.SourceIcon
import com.theoriacodex.app.source.displayName
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reports whether the feed under a [FeedFilterFab] is scrolling. Install [connection] with
 * `nestedScroll` on the screen; only the button reads the state, so scrolling does not recompose
 * the feed.
 */
internal class FeedScrollSignal(private val scope: CoroutineScope) {
    var scrolling by mutableStateOf(false)
        private set
    private var settle: Job? = null
    private var scrollEvents = 0

    // Scroll events arrive every frame, so each only bumps a counter; one job watches for a quiet window.
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y != 0f) {
                scrolling = true
                scrollEvents++
                if (settle?.isActive != true) settle = scope.launch { awaitSettle() }
            }
            return Offset.Zero
        }
    }

    /** Clears [scrolling] once no scroll has arrived for [SCROLL_SETTLE_MS], give or take one tick. */
    private suspend fun awaitSettle() {
        var quietMs = 0L
        while (quietMs < SCROLL_SETTLE_MS) {
            val seen = scrollEvents
            delay(SCROLL_SETTLE_TICK_MS)
            quietMs = if (seen == scrollEvents) quietMs + SCROLL_SETTLE_TICK_MS else 0L
        }
        scrolling = false
    }
}

@Composable
internal fun rememberFeedScrollSignal(): FeedScrollSignal {
    val scope = rememberCoroutineScope()
    return remember(scope) { FeedScrollSignal(scope) }
}

/**
 * The feeds' filter button. It names up to [MAX_NAMED_FILTERS] active filters; beyond that, and
 * while the feed scrolls, it animates down to just their number so it covers less of the feed.
 * [onClear] clears every filter and returns how to restore them, which the button offers briefly.
 */
@Composable
internal fun FeedFilterFab(
    labels: List<String>,
    contentDescription: String,
    onClick: () -> Unit,
    onClear: () -> () -> Unit,
    modifier: Modifier = Modifier,
    scrollSignal: FeedScrollSignal? = null,
) {
    var undo by remember { mutableStateOf<(() -> Unit)?>(null) }
    val pendingUndo = undo?.takeIf { labels.isEmpty() }
    LaunchedEffect(undo) {
        if (undo != null) {
            delay(UNDO_WINDOW_MS)
            undo = null
        }
    }
    val active = labels.isNotEmpty()
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = {
            if (pendingUndo != null) {
                pendingUndo()
                undo = null
            } else {
                onClick()
            }
        },
        shape = RoundedCornerShape(24.dp),
        color = if (active) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (active) colors.onPrimaryContainer else colors.onSurface,
        shadowElevation = 6.dp,
        modifier = modifier.semantics {
            stateDescription = if (active) "Filters active" else "No active filters"
        },
    ) {
        FeedFilterContent(labels, contentDescription, scrollSignal, pendingUndo != null) { undo = onClear() }
    }
}

@Composable
private fun FeedFilterContent(
    labels: List<String>,
    contentDescription: String,
    scrollSignal: FeedScrollSignal?,
    canUndo: Boolean,
    onClear: () -> Unit,
) {
    Box(Modifier.animateContentSize()) {
        if (labels.isNotEmpty()) {
            ActiveFilterContent(labels, contentDescription, scrollSignal?.scrolling == true, onClear)
        } else {
            Row(
                modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.FilterList, contentDescription)
                Text(if (canUndo) "Undo" else "Filter", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ActiveFilterContent(
    labels: List<String>,
    contentDescription: String,
    scrolling: Boolean,
    onClear: () -> Unit,
) {
    val compact = scrolling || labels.size > MAX_NAMED_FILTERS
    Row(
        modifier = Modifier.heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.FilterList, contentDescription)
        Text(
            text = if (compact) labels.size.toString() else labels.joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
        IconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, "Clear filters", Modifier.size(18.dp))
        }
    }
}

/** The shared filter sheet: every feed shows the same title, Reset placement, and scrolling. */
@Composable
internal fun FeedFilterSheet(
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Filters",
    content: @Composable ColumnScope.() -> Unit,
) {
    ExpandedBottomSheet(onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onReset) { Text("Reset") }
            }
            content()
        }
    }
}

/** A titled group of filter choices; they wrap so no option is hidden off to the side. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterSection(title: String, content: @Composable FlowRowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun FilterChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/** A source choice shown by its icon, named for accessibility. */
@Composable
internal fun SourceFilterChip(source: SourceKey, selected: Boolean, onClick: () -> Unit) {
    val name = source.displayName()
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { SourceIcon(source) },
        modifier = Modifier.semantics { contentDescription = name },
    )
}

private const val MAX_NAMED_FILTERS = 2
private const val SCROLL_SETTLE_MS = 350L
private const val SCROLL_SETTLE_TICK_MS = 50L
private const val UNDO_WINDOW_MS = 5_000L
