package com.theoriacodex.app.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.domain.adapter.QuickQueryKind
import kotlinx.coroutines.launch

@Composable
fun ExploreScreen(
    coordinator: SearchCoordinator,
    onApplyDraftAndNavigateToSearch: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(coordinator.draftQuery.mode) {
        coordinator.loadTrendingTags()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Quick Queries", style = MaterialTheme.typography.titleMedium)

        val quickQueries = listOf(
            QuickQueryKind.POPULAR_TODAY,
            QuickQueryKind.TOP_7D,
            QuickQueryKind.TOP_30D,
            QuickQueryKind.NEWEST,
            QuickQueryKind.RANDOM,
        )

        quickQueries.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { kind ->
                    Card(modifier = Modifier.weight(1f)) {
                        TextButton(onClick = {
                            coordinator.applyQuickQuery(kind)
                            onApplyDraftAndNavigateToSearch()
                        }) {
                            Text(kind.name)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Trending tags", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { scope.launch { coordinator.loadTrendingTags() } }) {
                Text("Refresh")
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(coordinator.trendingTags) { tag ->
                AssistChip(
                    onClick = {
                        if (coordinator.prepareExploreTagSearch(tag.text)) {
                            onApplyDraftAndNavigateToSearch()
                        }
                    },
                    label = { Text(tag.text) }
                )
            }
        }
    }
}
