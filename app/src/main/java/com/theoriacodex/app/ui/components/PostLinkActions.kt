package com.theoriacodex.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Source
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import com.theoriacodex.domain.model.Post

/**
 * Ways to reach a post beyond the current feed, provided once for the app so the Viewer's post info and every feed
 * post menu offer the same ones. Menus list them with [availableFor].
 */
@Immutable
internal class PostLinkActions(
    val openOriginalPost: (url: String) -> Unit = {},
    val openInBrowser: (url: String) -> Unit = {},
    /** "Find on other sites" for a post, or null when no other site can look it up. */
    val findOnOtherSites: (Post) -> (() -> Unit)? = { null },
)

internal val LocalPostLinkActions = staticCompositionLocalOf { PostLinkActions() }

internal class PostLinkAction(val label: String, val icon: ImageVector, val run: () -> Unit)

/** The link actions [post] supports, in menu order. */
internal fun PostLinkActions.availableFor(post: Post): List<PostLinkAction> = listOfNotNull(
    post.originUrl?.let { url -> PostLinkAction("Open original post", Icons.Default.Source) { openOriginalPost(url) } },
    findOnOtherSites(post)?.let { find -> PostLinkAction("Find on other sites", Icons.Default.ImageSearch, find) },
    post.pageUrl?.takeIf(String::isNotBlank)?.let { url ->
        PostLinkAction("Open in browser", Icons.Default.Public) { openInBrowser(url) }
    },
)
