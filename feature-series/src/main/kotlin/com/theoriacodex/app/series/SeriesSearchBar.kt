package com.theoriacodex.app.series

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.SearchBarMinHeight
import com.theoriacodex.app.ui.components.SearchBarShape
import kotlinx.coroutines.flow.collectLatest

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

@Composable
internal fun SeriesScrollChromeEffect(
    scrollable: ScrollableState,
    onScrollStarted: () -> Unit,
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
}

internal const val SERIES_SEARCH_BAR_TAG = "Series search bar"
internal const val SERIES_SEARCH_INPUT_TAG = "Series search input"
