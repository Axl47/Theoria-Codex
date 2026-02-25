package com.theoriacodex.app.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.domain.adapter.QuickQueryKind
import kotlinx.coroutines.launch

@Composable
fun ExploreScreen(
    coordinator: SearchCoordinator,
    onApplyDraftAndNavigateToSearch: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tagSelections by remember(coordinator.trendingTags) {
        mutableStateOf<Map<String, ExploreTagSelection>>(emptyMap())
    }

    LaunchedEffect(coordinator.draftQuery.mode) {
        coordinator.loadTrendingTags()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Explore", style = MaterialTheme.typography.titleLarge)
        }

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
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                coordinator.applyQuickQuery(kind)
                                onApplyDraftAndNavigateToSearch()
                            },
                    ) {
                        Text(
                            text = kind.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Trending tags", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { scope.launch { coordinator.loadTrendingTags() } }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh trending tags",
                    )
                }
            }
            TextButton(
                enabled = tagSelections.isNotEmpty(),
                onClick = {
                    val includeTags = tagSelections
                        .filterValues { it == ExploreTagSelection.INCLUDE }
                        .keys
                        .toList()
                    val excludeTags = tagSelections
                        .filterValues { it == ExploreTagSelection.EXCLUDE }
                        .keys
                        .toList()
                    if (coordinator.prepareExploreTagSearch(includeTags, excludeTags)) {
                        onApplyDraftAndNavigateToSearch()
                    }
                },
            ) {
                Text("Apply")
            }
        }

        ExploreTagSelectionGrid(
            tags = coordinator.trendingTags.map { it.text }.distinct(),
            selections = tagSelections,
            onIncludeTag = { tag ->
                tagSelections = when (tagSelections[tag]) {
                    ExploreTagSelection.INCLUDE -> tagSelections - tag
                    ExploreTagSelection.EXCLUDE -> tagSelections + (tag to ExploreTagSelection.INCLUDE)
                    null -> tagSelections + (tag to ExploreTagSelection.INCLUDE)
                }
            },
            onExcludeTag = { tag ->
                tagSelections = when (tagSelections[tag]) {
                    ExploreTagSelection.EXCLUDE -> tagSelections - tag
                    ExploreTagSelection.INCLUDE -> tagSelections + (tag to ExploreTagSelection.EXCLUDE)
                    null -> tagSelections + (tag to ExploreTagSelection.EXCLUDE)
                }
            },
        )
    }
}

private enum class ExploreTagSelection {
    INCLUDE,
    EXCLUDE,
}

@Composable
private fun ExploreTagSelectionGrid(
    tags: List<String>,
    selections: Map<String, ExploreTagSelection>,
    onIncludeTag: (String) -> Unit,
    onExcludeTag: (String) -> Unit,
) {
    if (tags.isEmpty()) {
        Text(
            text = "No tags",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    tags.chunked(3).forEach { rowTags ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            rowTags.forEach { tag ->
                ExploreTagActionCell(
                    tag = tag,
                    selection = selections[tag],
                    onInclude = { onIncludeTag(tag) },
                    onExclude = { onExcludeTag(tag) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(3 - rowTags.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ExploreTagActionCell(
    tag: String,
    selection: ExploreTagSelection?,
    onInclude: () -> Unit,
    onExclude: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(999.dp),
            color = if (selection == null) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            } else {
                accent.copy(alpha = 0.16f)
            },
        ) {
            Text(
                text = tag,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ExploreTagActionPill(
                label = "+",
                selected = selection == ExploreTagSelection.INCLUDE,
                onClick = onInclude,
                modifier = Modifier.weight(1f),
            )
            ExploreTagActionPill(
                label = "-",
                selected = selection == ExploreTagSelection.EXCLUDE,
                onClick = onExclude,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ExploreTagActionPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            accent.copy(alpha = 0.24f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
