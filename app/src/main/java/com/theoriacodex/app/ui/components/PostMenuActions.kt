package com.theoriacodex.app.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Source
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import com.theoriacodex.domain.model.Post

/**
 * Post actions that work the same on every screen: sharing, copying, and reaching the post outside the current feed.
 * Provided once for the app so the Viewer's post info and every feed post menu offer the same ones. Menus show Share
 * through [shareFor] and list the rest with [availableFor].
 */
@Immutable
internal class PostMenuActions(
    val openOriginalPost: (url: String) -> Unit = {},
    val openInBrowser: (url: String) -> Unit = {},
    /** "Find on other sites" for a post, or null when no other site can look it up. */
    val findOnOtherSites: (Post) -> (() -> Unit)? = { null },
    /** Opens Android's share sheet for a post link. */
    val share: (url: String) -> Unit = {},
    val copyTags: (Post) -> Unit = {},
    val copyLink: (url: String) -> Unit = {},
)

internal val LocalPostMenuActions = staticCompositionLocalOf { PostMenuActions() }

internal class PostMenuAction(val label: String, val icon: ImageVector, val run: () -> Unit)

/** The post's own page, which Share, Copy link and Open in browser act on. */
private val Post.link: String? get() = pageUrl?.trim()?.takeIf(String::isNotBlank)

/** Shares [post]'s link, or null when it has none. */
internal fun PostMenuActions.shareFor(post: Post): (() -> Unit)? = post.link?.let { url -> { share(url) } }

/** Every action besides Share that [post] supports, in menu order. */
internal fun PostMenuActions.availableFor(post: Post): List<PostMenuAction> = listOfNotNull(
    post.originUrl?.let { url -> PostMenuAction("Open original post", Icons.Default.Source) { openOriginalPost(url) } },
    findOnOtherSites(post)?.let { find -> PostMenuAction("Find on other sites", Icons.Default.ImageSearch, find) },
    post.link?.let { url -> PostMenuAction("Open in browser", Icons.Default.Public) { openInBrowser(url) } },
    PostMenuAction("Copy tags", Icons.Default.ContentCopy) { copyTags(post) },
    post.link?.let { url -> PostMenuAction("Copy link", Icons.Default.Link) { copyLink(url) } },
)

/** Opens Android's share sheet for a post link, from any context. */
internal fun sharePostLink(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
