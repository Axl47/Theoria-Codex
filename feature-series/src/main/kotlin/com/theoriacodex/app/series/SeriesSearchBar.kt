package com.theoriacodex.app.series

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.SearchBarMinHeight
import com.theoriacodex.app.ui.components.SearchBarShape
import com.theoriacodex.app.ui.motion.TheoriaMotion
import kotlinx.coroutines.flow.collectLatest

/**
 * Pill search bar matching Search tab's surface, shape, and height. Incrementing [focusRequest] focuses the field
 * on the next frame, used when expanding the bar from the collapsed corner strip.
 */
@Composable
internal fun SeriesSearchBar(
    query: String,
    placeholder: String,
    searching: Boolean,
    focusRequest: Int,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            withFrameNanos { }
            requester.requestFocus()
        }
    }
    Surface(
        onClick = { requester.requestFocus() },
        shape = SearchBarShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().heightIn(min = SearchBarMinHeight).testTag(SERIES_SEARCH_BAR_TAG),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            SeriesSearchInput(
                query = query,
                placeholder = placeholder,
                requester = requester,
                onQueryChange = onQueryChange,
                onSubmit = {
                    focus.clearFocus()
                    onSearch(query)
                },
                modifier = Modifier.weight(1f),
            )
            SeriesSearchClearSlot(showClear = query.isNotEmpty() || searching) {
                focus.clearFocus()
                onClear()
            }
            trailing()
        }
    }
}

@Composable
private fun SeriesSearchInput(
    query: String,
    placeholder: String,
    requester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
        if (query.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(requester).testTag(SERIES_SEARCH_INPUT_TAG),
        )
    }
}

@Composable
private fun SeriesSearchClearSlot(showClear: Boolean, onClear: () -> Unit) {
    if (showClear) {
        IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "Clear") }
    } else {
        Spacer(Modifier.width(8.dp))
    }
}

/** Collapses the search bar when [scrollable] starts moving and restores floating controls at the top. */
@Composable
internal fun SeriesScrollChromeEffect(
    scrollable: ScrollableState,
    onScrollStarted: () -> Unit,
    onAtTop: () -> Unit,
) {
    val focus = LocalFocusManager.current
    LaunchedEffect(scrollable) {
        snapshotFlow { scrollable.isScrollInProgress }.collectLatest { active ->
            if (active) {
                onScrollStarted()
                focus.clearFocus()
            }
        }
    }
    LaunchedEffect(scrollable) {
        snapshotFlow { !scrollable.canScrollBackward }.collectLatest { atTop ->
            if (atTop) onAtTop()
        }
    }
}

/**
 * Floating bottom pill bar for a source's listings (Popular, optional Latest, and Filter with active count).
 * Slides down out of the way when [visible] turns false on downward scroll and returns on upward scroll.
 */
@Composable
internal fun BrowseControlsBar(
    kind: BrowseKind,
    latest: Boolean,
    visible: Boolean,
    onChoose: (BrowseKind) -> Unit,
    onFilter: () -> Unit,
    modifier: Modifier = Modifier,
    filterCount: Int = (kind as? BrowseKind.Filtered)?.active ?: 0,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(tween(TheoriaMotion.MEDIUM_MS)) { it } + fadeIn(tween(TheoriaMotion.SHORT_MS)),
        exit = slideOutVertically(tween(TheoriaMotion.MEDIUM_MS)) { it } + fadeOut(tween(TheoriaMotion.SHORT_MS)),
    ) {
        Surface(
            shape = BROWSE_BAR_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
            tonalElevation = 3.dp,
            modifier = Modifier.testTag(BROWSE_CONTROLS_BAR_TAG),
        ) {
            BrowseControlsRow(kind, latest, filterCount, onChoose, onFilter)
        }
    }
}

@Composable
private fun BrowseControlsRow(
    kind: BrowseKind,
    latest: Boolean,
    filterCount: Int,
    onChoose: (BrowseKind) -> Unit,
    onFilter: () -> Unit,
) {
    val popular = kind == BrowseKind.Popular
    val latestSelected = kind == BrowseKind.Latest
    val filterLabel = if (filterCount > 0) "$FILTER · $filterCount" else FILTER
    Row(
        modifier = Modifier.padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrowseControlItem(
            label = "Popular",
            icon = if (popular) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            selected = popular,
            onClick = { onChoose(BrowseKind.Popular) },
        )
        if (latest) {
            BrowseControlItem(
                label = "Latest",
                icon = if (latestSelected) Icons.Filled.NewReleases else Icons.Outlined.NewReleases,
                selected = latestSelected,
                onClick = { onChoose(BrowseKind.Latest) },
            )
        }
        BrowseControlItem(
            label = filterLabel,
            icon = Icons.Default.FilterList,
            selected = kind is BrowseKind.Filtered,
            onClick = onFilter,
        )
    }
}

@Composable
private fun BrowseControlItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = BROWSE_ITEM_SHAPE,
        color = if (selected) colors.secondaryContainer else Color.Transparent,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        modifier = Modifier.semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier.heightIn(min = 42.dp).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
    }
}

private val BROWSE_BAR_SHAPE = RoundedCornerShape(28.dp)
private val BROWSE_ITEM_SHAPE = RoundedCornerShape(24.dp)

internal const val SERIES_SEARCH_BAR_TAG = "Series search bar"
internal const val SERIES_SEARCH_INPUT_TAG = "Series search input"
internal const val BROWSE_CONTROLS_BAR_TAG = "Series browse controls"
