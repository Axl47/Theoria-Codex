package com.theoriacodex.app.codex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.creator.CreatorProfileActionButton
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexDetailScreen(
    codexName: String?,
    posts: List<Post>,
    sortMode: CodexSortMode,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    onSortChange: (CodexSortMode) -> Unit,
    onOpenViewer: (Int) -> Unit,
    onRemovePost: (Post) -> Unit,
    onSavePostToDevice: (Post) -> Unit,
    onOpenCreatorProfile: (Post) -> Unit,
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    onAddIncludeTag: (String) -> Unit = {},
    onAddExcludeTag: (String) -> Unit = {},
    onRemoveIncludeTag: (String) -> Unit = {},
    onRemoveExcludeTag: (String) -> Unit = {},
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)? = null,
    onGoToSearch: (() -> Unit)? = null,
    onBack: () -> Unit,
    onDeleteCodex: () -> Unit,
) {
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
                TextButton(onClick = { showDeleteConfirm = true }) { Text("Delete") }
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
                    SearchResultCard(
                        post = post,
                        pixivUgoiraClient = pixivUgoiraClient,
                        showSourceBadge = true,
                        onClick = { onOpenViewer(index) },
                        onLongPress = { selectedActionPost = post },
                    )
                }
            }
        }
    }

    if (selectedActionPost != null) {
        val post = requireNotNull(selectedActionPost)
        val context = LocalContext.current
        val actionSheetHorizontalPadding = 16.dp
        ModalBottomSheet(
            onDismissRequest = { selectedActionPost = null },
            dragHandle = null,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = actionSheetHorizontalPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = {
                            selectedActionPost = null
                            onSavePostToDevice(post)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Save to device",
                        )
                    }
                    IconButton(
                        onClick = {
                            selectedActionPost = null
                            onRemovePost(post)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove from Codex",
                        )
                    }
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val formatted = formatPostTagsForClipboard(post)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("tags", formatted))
                            Toast.makeText(context, "Tags copied", Toast.LENGTH_SHORT).show()
                            selectedActionPost = null
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy tags",
                        )
                    }
                    IconButton(
                        onClick = {
                            val copied = copyPostUrlToClipboard(context, post)
                            val message = if (copied) {
                                "Post URL copied"
                            } else {
                                "No post URL available"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            selectedActionPost = null
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                        )
                    }
                }
                Text(
                    text = post.title?.takeIf { it.isNotBlank() } ?: post.id.sourcePostId,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CreatorProfileActionButton(
                    post = post,
                    onClick = {
                        selectedActionPost = null
                        onOpenCreatorProfile(post)
                    },
                )
                HorizontalDivider()
                PostTagActionSection(
                    post = post,
                    tagVideoCountProvider = tagVideoCountProvider,
                    fetchTagVideoCounts = fetchTagVideoCounts,
                    onAddIncludeTag = onAddIncludeTag,
                    onAddExcludeTag = onAddExcludeTag,
                    onRemoveIncludeTag = onRemoveIncludeTag,
                    onRemoveExcludeTag = onRemoveExcludeTag,
                    onFavoriteTagLongPress = onFavoriteTagLongPress,
                )
                onGoToSearch?.let { goToSearch ->
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            selectedActionPost = null
                            goToSearch()
                        },
                    ) {
                        Text("Go to Search")
                    }
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { selectedActionPost = null },
                ) {
                    Text("Cancel")
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Codex?") },
            text = {
                Text("Delete \"$codexName\" and all saved items in it? This cannot be undone.")
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteCodex()
                    },
                ) {
                    Text("Delete")
                }
            },
        )
    }
}

private fun formatPostTagsForClipboard(post: Post): String {
    val canonicalPositives = post.canonicalTags.filterNot { it.startsWith("-") }
    val canonicalNegatives = post.canonicalTags
        .filter { it.startsWith("-") }
        .map { it.removePrefix("-") }

    val rawPositives = post.rawTags.filterNot { it.startsWith("-") }
    val rawNegatives = post.rawTags
        .filter { it.startsWith("-") }
        .map { it.removePrefix("-") }

    val positives = (canonicalPositives + rawPositives)
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("-") }
        .distinct()
    val negatives = (canonicalNegatives + rawNegatives)
        .map { it.trim().removePrefix("-") }
        .filter { it.isNotBlank() }
        .distinct()

    val positiveLine = positives.joinToString(", ")
    val negativeLine = negatives.joinToString(", ") { "-$it" }
    return "$positiveLine\n\n$negativeLine"
}

private fun copyPostUrlToClipboard(context: Context, post: Post): Boolean {
    val pageUrl = post.pageUrl?.trim().takeIf { !it.isNullOrBlank() } ?: return false
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("post_url", pageUrl))
    return true
}
