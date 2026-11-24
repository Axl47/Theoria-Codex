package com.theoriacodex.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theoriacodex.app.codex.LocalCodexPostIds
import com.theoriacodex.app.codex.PreloadCodexSaveRelevance
import com.theoriacodex.app.codex.codexSavedColor
import com.theoriacodex.app.creator.CreatorProfileAction
import com.theoriacodex.app.creator.creatorProfileActions
import com.theoriacodex.app.post.displayTitleOrNull
import com.theoriacodex.app.related.LocalRelatedPostsSupport
import com.theoriacodex.app.source.SourceIcon
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.motion.LikedHeartColor
import com.theoriacodex.app.viewer.buildViewerImageRequest
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query

@Composable
fun PostActionSheet(
    post: Post,
    onDismiss: () -> Unit,
    onToggleLike: ((Post) -> Unit)? = null,
    onSaveToDevice: ((Post) -> Unit)? = null,
    onSaveToCodex: ((Post) -> Unit)? = null,
    saveToCodexQuery: Query? = null,
    saveToCodexLabel: String = "Save to Codex",
    onRemoveFromCodex: (() -> Unit)? = null,
    removeLabel: String = "Remove from Codex",
    onGoToSearch: (() -> Unit)? = null,
    onStartOver: (() -> Unit)? = null,
    onShowRelated: (() -> Unit)? = null,
    tagContent: @Composable () -> Unit,
) {
    PreloadCodexSaveRelevance(post, saveToCodexQuery)

    var showingTerms by rememberSaveable { mutableStateOf(false) }
    if (showingTerms) {
        ExpandedBottomSheet(onDismiss) { tagContent() }
        return
    }

    val actions = LocalPostMenuActions.current
    fun dismissThen(action: () -> Unit) {
        onDismiss()
        action()
    }
    val creatorActions = creatorProfileActions(post, actions.creatorBrowsingSources.value)

    fun openCreator(action: CreatorProfileAction) = dismissThen {
        action.profile?.let(actions.openCreator) ?: actions.openPostCreator(post)
    }

    ActionMenuSheet(onDismiss) {
        PostActionHeader(
            post,
            creator = creatorActions.firstOrNull()?.let { action -> HeaderLink(action.label) { openCreator(action) } },
        )
        ActionMenuFollowUpRow(Icons.Default.Sell, "Search terms") { showingTerms = true }
        onShowRelated?.takeIf { LocalRelatedPostsSupport.current(post.id.source) }?.let { showRelated ->
            ActionMenuRow(Icons.Default.AutoAwesome, "More like this") { dismissThen(showRelated) }
        }

        val toggleLike = onToggleLike ?: actions.toggleLike
        PostLikeRow(post.id, actions.likedPostIds) { dismissThen { toggleLike(post) } }
        val saveToCodex = onSaveToCodex ?: actions.saveToCodex
        SaveToCodexRow(post, saveToCodexLabel) { dismissThen { saveToCodex(post) } }
        val saveToDevice = onSaveToDevice ?: actions.saveToDevice
        ActionMenuRow(Icons.Default.Download, "Save to device") { dismissThen { saveToDevice(post) } }

        val extraCreators = creatorActions.drop(1)
        if (extraCreators.isNotEmpty() || onStartOver != null || onGoToSearch != null) {
            ActionMenuDivider()
        }
        extraCreators.forEach { action -> ActionMenuRow(Icons.Default.Person, action.label) { openCreator(action) } }
        onStartOver?.let { startOver ->
            ActionMenuRow(Icons.Default.Replay, "Start over") { dismissThen(startOver) }
        }
        onGoToSearch?.let { goToSearch ->
            ActionMenuRow(Icons.Default.Search, "Go to Search") { dismissThen(goToSearch) }
        }

        ActionMenuDivider()
        PostMenuRows(post, actions, onDismiss)

        onRemoveFromCodex?.let { remove ->
            ActionMenuDivider()
            ActionMenuRow(
                icon = Icons.Default.Delete,
                label = removeLabel,
                tint = MaterialTheme.colorScheme.error,
            ) { dismissThen(remove) }
        }
    }
}

@Composable
private fun PostLikeRow(postId: PostId, likedPostIds: State<Set<PostId>>, onClick: () -> Unit) {
    if (postId in likedPostIds.value) {
        ActionMenuRow(Icons.Default.Favorite, "Unlike", tint = LikedHeartColor, onClick = onClick)
    } else {
        ActionMenuRow(Icons.Outlined.FavoriteBorder, "Like", onClick = onClick)
    }
}

@Composable
private fun PostMenuRows(post: Post, actions: PostMenuActions, onDismiss: () -> Unit) {
    actions.shareFor(post)?.let { share -> ActionMenuRow(Icons.Default.Share, "Share") { onDismiss(); share() } }
    actions.availableFor(post).forEach { action ->
        ActionMenuRow(action.icon, action.label) {
            onDismiss()
            action.run()
        }
    }
}

@Composable
private fun SaveToCodexRow(post: Post, label: String, onClick: () -> Unit) {
    if (post.id in LocalCodexPostIds.current.value) {
        ActionMenuRow(Icons.Default.BookmarkAdded, label, tint = codexSavedColor(), onClick = onClick)
    } else {
        ActionMenuRow(Icons.Default.BookmarkAdd, label, onClick = onClick)
    }
}

@Composable
fun PostActionHeader(post: Post, showPostId: Boolean = false, creator: HeaderLink? = null) {
    val pageCount = maxOf(post.mediaCount ?: 1, post.media.size)
    val hasOwnTitle = post.displayTitleOrNull() != null
    ActionMenuHeader(
        titleLink = creator?.takeUnless { hasOwnTitle },
        title = post.displayTitleOrNull()
            ?: post.authorName?.trim()?.takeIf(String::isNotBlank)
            ?: post.id.source.displayName(),
        image = { modifier ->
            PostThumbnail(post, modifier)
        },
        details = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceIcon(post.id.source)
                if (hasOwnTitle) creator?.let { HeaderLinkText(it, MaterialTheme.typography.bodySmall) }
                if (pageCount > 1) ActionMenuDetailText("$pageCount pages")
                if (showPostId) ActionMenuDetailText(post.id.sourcePostId)
            }
        },
    )
}

@Composable
fun PostThumbnail(post: Post, modifier: Modifier) {
    val context = LocalContext.current
    val location = post.preview.localPath ?: post.preview.url
    val request = remember(context, location, post.id.source) {
        location?.let { buildViewerImageRequest(context, it, post.id.source, staticAnimatedWebPFrame = true) }
    }
    AsyncImage(request, null, modifier, contentScale = ContentScale.Crop)
}
