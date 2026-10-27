package com.theoriacodex.app.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Collapsed controls share an edge-attached strip without reserving feed space. */
@Composable
internal fun CollapsingSearchChrome(
    expanded: Boolean,
    legacy: Boolean,
    onExpand: () -> Unit,
    sourcePicker: @Composable () -> Unit,
    inputField: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    var toolbarHeight by remember { mutableIntStateOf(0) }
    val height = with(LocalDensity.current) { toolbarHeight.toDp() }
    val reservedHeight by animateDpAsState(if (legacy || expanded) height else 0.dp, label = "Search toolbar space")
    val collapsed = !legacy && !expanded
    Box(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = horizontalPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (reservedHeight > 0.dp) Spacer(Modifier.height(reservedHeight))
            content()
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopEnd)
                .padding(start = horizontalPadding, end = if (collapsed) 0.dp else horizontalPadding)
                .onSizeChanged { toolbarHeight = it.height },
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (legacy) inputField() else {
                AnimatedVisibility(
                    visible = expanded,
                    modifier = Modifier.weight(1f),
                    enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
                ) { inputField() }
                SearchCornerControls(collapsed, horizontalPadding, onExpand, sourcePicker)
            }
        }
    }
}


@Composable
private fun SearchCornerControls(
    collapsed: Boolean,
    horizontalPadding: Dp,
    onExpand: () -> Unit,
    sourcePicker: @Composable () -> Unit,
) {
    Row(
        modifier = if (collapsed) Modifier
            .testTag("collapsed-search-strip")
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50))
            .padding(start = 4.dp, end = horizontalPadding, top = 2.dp, bottom = 2.dp)
            else Modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (collapsed) {
            IconButton(onClick = onExpand) {
                Icon(Icons.Default.Search, "Expand Search", tint = MaterialTheme.colorScheme.primary)
            }
        }
        sourcePicker()
    }
}
