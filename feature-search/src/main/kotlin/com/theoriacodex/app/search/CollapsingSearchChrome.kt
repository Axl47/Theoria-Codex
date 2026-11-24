package com.theoriacodex.app.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.SearchBarMinHeight
import com.theoriacodex.app.ui.components.SearchBarShape

@Composable
internal fun SearchSummaryBar(
    summary: String?,
    validationMessage: String?,
    onOpen: () -> Unit,
    trailing: @Composable () -> Unit,
    history: SearchBarHistory = SearchBarHistory.None,
) {
    val swipe = remember { SearchBarSwipe() }
    Surface(
        onClick = onOpen,
        shape = SearchBarShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().heightIn(min = SearchBarMinHeight).testTag("Search bar").searchBarHistorySwipe(history, swipe),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = validationMessage ?: summary ?: "Search tags",
                modifier = with(swipe) { Modifier.weight(1f).followingSwipe() },
                color = when {
                    validationMessage != null -> MaterialTheme.colorScheme.error
                    summary != null -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailing()
        }
    }
}
