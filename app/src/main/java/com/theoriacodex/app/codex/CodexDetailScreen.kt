package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.domain.model.Post

@Composable
fun CodexDetailScreen(
    codexName: String?,
    posts: List<Post>,
    sortMode: CodexSortMode,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    onSortChange: (CodexSortMode) -> Unit,
    onOpenViewer: (Int) -> Unit,
    onRemovePost: (Post) -> Unit,
    onBack: () -> Unit,
    onDeleteCodex: () -> Unit,
) {
    if (codexName == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("This codex no longer exists.", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onBack) { Text("Back to codex list") }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(codexName, style = MaterialTheme.typography.titleLarge)
                Text("${posts.size} items", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onBack) { Text("Back") }
                TextButton(onClick = onDeleteCodex) { Text("Delete") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CodexSortMode.entries.forEach { mode ->
                val label = when (mode) {
                    CodexSortMode.NEWEST_SAVED -> "Newest"
                    CodexSortMode.OLDEST_SAVED -> "Oldest"
                    CodexSortMode.BY_SOURCE -> "By source"
                }
                FilterChip(
                    selected = mode == sortMode,
                    onClick = { onSortChange(mode) },
                    label = { Text(label) },
                )
            }
        }

        if (posts.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "This codex is empty",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Browse and save posts",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(posts) { index, post ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SearchResultCard(
                            post = post,
                            pixivUgoiraClient = pixivUgoiraClient,
                            onClick = { onOpenViewer(index) },
                        )
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onRemovePost(post) },
                        ) { Text("Remove") }
                    }
                }
            }
        }
    }
}
