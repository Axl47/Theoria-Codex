package com.theoriacodex.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theoriacodex.app.codex.PreloadCodexSaveRelevance
import com.theoriacodex.app.creator.creatorProfileActions
import com.theoriacodex.app.media.copyPostTagsToClipboard
import com.theoriacodex.app.media.copyPostUrlToClipboard
import com.theoriacodex.app.post.displayTitleOrNull
import com.theoriacodex.app.source.SourceIcon
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.viewer.buildViewerImageRequest
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey

/**
 * The product's post long-press menu: labeled actions, with search-term selection in its own sheet.
 *
 * Callers supply the actions available on their route and the route-specific [tagContent], which
 * stays open while terms are toggled. Every other action closes the menu before it runs.
 */
@Composable
fun PostActionSheet(
    post: Post,
    creatorBrowsingSources: Set<SourceKey> = emptySet(),
    onDismiss: () -> Unit,
    onSaveToDevice: () -> Unit,
    onSaveToCodex: (() -> Unit)? = null,
    saveToCodexQuery: Query? = null,
    saveToCodexLabel: String = "Save to Codex",
    onRemoveFromCodex: (() -> Unit)? = null,
    onOpenCreatorProfile: ((CreatorProfile) -> Unit)? = null,
    onOpenLegacyCreatorProfile: (() -> Unit)? = null,
    onGoToSearch: (() -> Unit)? = null,
    onStartOver: (() -> Unit)? = null,
    onPostUrlCopied: (Post) -> Unit = {},
    tagContent: @Composable () -> Unit,
) {
    require((onOpenCreatorProfile == null) == (onOpenLegacyCreatorProfile == null)) {
        "Creator profile callbacks must be supplied together"
    }

    if (onSaveToCodex != null) PreloadCodexSaveRelevance(post, saveToCodexQuery)

    var showingTerms by rememberSaveable { mutableStateOf(false) }
    if (showingTerms) {
        ExpandedBottomSheet(onDismiss) { tagContent() }
        return
    }

    val context = LocalContext.current
    fun dismissThen(action: () -> Unit) {
        onDismiss()
        action()
    }
    val creatorActions = if (onOpenCreatorProfile != null && onOpenLegacyCreatorProfile != null) {
        creatorProfileActions(post, creatorBrowsingSources)
    } else {
        emptyList()
    }

    ActionMenuSheet(onDismiss) {
        PostActionHeader(post)
        ActionMenuFollowUpRow(Icons.Default.Sell, "Search terms") { showingTerms = true }

        onSaveToCodex?.let { save ->
            ActionMenuRow(Icons.Default.BookmarkAdd, saveToCodexLabel) { dismissThen(save) }
        }
        ActionMenuRow(Icons.Default.Download, "Save to device") { dismissThen(onSaveToDevice) }

        if (creatorActions.isNotEmpty() || onStartOver != null || onGoToSearch != null) {
            ActionMenuDivider()
        }
        PostCreatorRows(post, creatorBrowsingSources, onOpenProfile = { profile ->
            dismissThen { onOpenCreatorProfile?.invoke(profile) }
        }, onOpenLegacyPost = {
            dismissThen { onOpenLegacyCreatorProfile?.invoke() }
        }, enabled = onOpenCreatorProfile != null)
        onStartOver?.let { startOver ->
            ActionMenuRow(Icons.Default.Replay, "Start over") { dismissThen(startOver) }
        }
        onGoToSearch?.let { goToSearch ->
            ActionMenuRow(Icons.Default.Search, "Go to Search") { dismissThen(goToSearch) }
        }

        ActionMenuDivider()
        ActionMenuRow(Icons.Default.ContentCopy, "Copy tags") {
            copyPostTagsWithFeedback(context, post)
            onDismiss()
        }
        ActionMenuRow(Icons.Default.Link, "Copy link") {
            copyPostUrlWithFeedback(context, post, onPostUrlCopied)
            onDismiss()
        }

        onRemoveFromCodex?.let { remove ->
            ActionMenuDivider()
            ActionMenuRow(
                icon = Icons.Default.Delete,
                label = "Remove from Codex",
                tint = MaterialTheme.colorScheme.error,
            ) { dismissThen(remove) }
        }
    }
}

/** One labeled row per browsable creator of [post]. */
@Composable
internal fun PostCreatorRows(
    post: Post,
    creatorBrowsingSources: Set<SourceKey>,
    onOpenProfile: (CreatorProfile) -> Unit,
    onOpenLegacyPost: () -> Unit,
    enabled: Boolean = true,
) {
    if (!enabled) return
    creatorProfileActions(post, creatorBrowsingSources).forEach { action ->
        ActionMenuRow(Icons.Default.Person, action.label) {
            action.profile?.let(onOpenProfile) ?: onOpenLegacyPost()
        }
    }
}

/**
 * Identifies the post a menu acts on, since most posts have no provider title to show.
 * [showPostId] adds the source's post ID for surfaces that present post details.
 */
@Composable
internal fun PostActionHeader(post: Post, showPostId: Boolean = false) {
    val context = LocalContext.current
    val location = post.preview.localPath ?: post.preview.url
    val request = remember(context, location, post.id.source) {
        location?.let { buildViewerImageRequest(context, it, post.id.source, staticAnimatedWebPFrame = true) }
    }
    val pageCount = maxOf(post.mediaCount ?: 1, post.media.size)
    ActionMenuHeader(
        title = post.displayTitleOrNull()
            ?: post.authorName?.trim()?.takeIf(String::isNotBlank)
            ?: post.id.source.displayName(),
        image = { modifier ->
            AsyncImage(request, null, modifier, contentScale = ContentScale.Crop)
        },
        details = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceIcon(post.id.source)
                if (pageCount > 1) ActionMenuDetailText("$pageCount pages")
                if (showPostId) ActionMenuDetailText(post.id.sourcePostId)
            }
        },
    )
}

private fun copyPostTagsWithFeedback(context: Context, post: Post) {
    if (!copyPostTagsToClipboard(context, post)) {
        Toast.makeText(context, "Could not copy tags", Toast.LENGTH_SHORT).show()
    }
}

private fun copyPostUrlWithFeedback(
    context: Context,
    post: Post,
    onPostUrlCopied: (Post) -> Unit,
) {
    if (copyPostUrlToClipboard(context, post)) {
        onPostUrlCopied(post)
    } else {
        Toast.makeText(context, "No post URL available", Toast.LENGTH_SHORT).show()
    }
}
