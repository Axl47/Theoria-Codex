package com.theoriacodex.app.viewer

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.LocalContentColor
import com.theoriacodex.app.ui.motion.LikedHeartColor
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Immutable
import com.theoriacodex.app.related.LocalRelatedPostsSupport
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.codex.PreloadCodexSaveRelevance
import com.theoriacodex.app.codex.LocalCodexPostIds
import com.theoriacodex.app.codex.codexSavedColor
import com.theoriacodex.app.comments.CommentsView
import com.theoriacodex.app.comments.ViewerComments
import com.theoriacodex.app.comments.commentsSection
import com.theoriacodex.app.tags.PostTagSheetExtras
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.theoriacodex.app.comments.AuthorDestination
import kotlinx.coroutines.delay
import com.theoriacodex.app.creator.CreatorProfileAction
import com.theoriacodex.app.creator.creatorProfileActions
import com.theoriacodex.app.tags.LocalPostTagSearchActions
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.ui.components.HeaderLink
import com.theoriacodex.app.ui.components.LocalPostMenuActions
import com.theoriacodex.app.ui.components.PostActionHeader
import com.theoriacodex.app.ui.components.PostMenuAction
import com.theoriacodex.app.ui.components.shareFor
import com.theoriacodex.app.ui.components.availableFor
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey

private const val COMMENT_HIGHLIGHT_MS = 1400L

@Composable
private fun rememberHighlightedComment(post: Post, comments: ViewerComments): String? {
    var highlighted by remember(post.id) { mutableStateOf<String?>(null) }
    val focusKey = comments.stateOf(post)?.focusKey
    LaunchedEffect(focusKey) {
        if (focusKey != null) {
            highlighted = focusKey
            comments.actions.focusShown(post)
        }
    }
    LaunchedEffect(highlighted) {
        if (highlighted != null) {
            delay(COMMENT_HIGHLIGHT_MS)
            highlighted = null
        }
    }
    return highlighted
}

private fun commentsView(
    comments: ViewerComments,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    highlightKey: String?,
    creatorBrowsingSources: Set<SourceKey>,
    controls: ViewerControls,
    onAction: (ViewerAction) -> Unit,
) = CommentsView(
    collapsed, onToggleCollapsed, comments.order, comments.onOrderSelected,
    highlightKey = highlightKey,
    creatorBrowsingSources = creatorBrowsingSources,
    onOpenAuthor = { destination ->
        when (destination) {
            is AuthorDestination.CreatorPage -> {
                controls.setPlaybackEnabled(false)
                onAction(ViewerAction.OpenCreator(destination.profile))
                controls.setInfoSheetVisible(false)
            }
            is AuthorDestination.Browser -> comments.onOpenUrl(destination.url)
        }
    },
    onOpenUrl = comments.onOpenUrl,
)

@Composable
internal fun OpenInfoSheetForFocusedComment(comments: ViewerComments, post: Post, infoSheetVisible: Boolean, controls: ViewerControls) {
    val focusKey = comments.stateOf(post)?.focusKey
    LaunchedEffect(focusKey) {
        if (focusKey != null && !infoSheetVisible) controls.openInfoSheetAtComments()
    }
}


@Composable
internal fun ViewerInfoSheet(
    selection: ViewerSelection,
    controls: ViewerControls,
    host: ViewerScreenHost,
    config: ViewerScreenConfig,
    series: ViewerSeries?,
    saveToCodexQuery: Query?,
    contextActions: ViewerContextActions,
    comments: ViewerComments,
    trail: ViewerTrail = ViewerTrail.None,
) {
    val post = selection.post
    if (series != null) {
        AdaptiveViewerInfo(onDismiss = { controls.setInfoSheetVisible(false) }) {
            ViewerChapterActions(post) {
                if (series is ViewerSeries.Watching) {
                    restartViewerEpisode(post, controls, host.onAction)
                } else {
                    controls.jumpToMedia(post.id, 0)
                    controls.setInfoSheetVisible(false)
                }
            }
        }
    } else {
        PostInfoSheet(selection, controls, host, config, saveToCodexQuery, contextActions, comments, trail)
    }
}

@Composable
private fun PostInfoSheet(
    selection: ViewerSelection,
    controls: ViewerControls,
    host: ViewerScreenHost,
    config: ViewerScreenConfig,
    saveToCodexQuery: Query?,
    contextActions: ViewerContextActions,
    comments: ViewerComments,
    trail: ViewerTrail,
) {
    val post = selection.post
    val onAction = host.onAction
    val searchActions = LocalPostTagSearchActions.current
    PreloadCodexSaveRelevance(post, saveToCodexQuery)
    val openedAtComments = remember(post.id) { controls.infoSheetTarget == InfoSheetTarget.Comments }
    val tagsStartCollapsed = remember(post.id) { comments.countOf(post) != null }
    DisposableEffect(Unit) { onDispose { controls.resetInfoSheetTarget() } }
    val commentsState = comments.stateOf(post)
    val commentCount = comments.countOf(post)
    val nowEpochMs = remember { System.currentTimeMillis() }
    var commentsCollapsed by remember(post.id) { mutableStateOf(false) }
    val highlightedComment = rememberHighlightedComment(post, comments)
    val onFork = rememberViewerTagFork(controls.trailForks, post, saveToCodexQuery) { controls.setInfoSheetVisible(false); controls.markInteraction() }
    AdaptiveViewerInfo(onDismiss = { controls.setInfoSheetVisible(false) }) {
        PostTagActionSection(
            header = {
                ViewerInfoActions(
                    selection, post.id in config.likedPostIds, controls, config.creatorBrowsingSources, onAction, host.onGoToSearch,
                    host.onOpenCreatorFallback, contextActions,
                )
            },
            post = post,
            onAddIncludeTerm = { term ->
                onAction(ViewerAction.IncludeTag(PostTaxonomyTerm(term.value, term.facet, term.sourceNamespace)))
                true
            },
            onAddExcludeTerm = { term ->
                onAction(ViewerAction.ExcludeTag(PostTaxonomyTerm(term.value, term.facet, term.sourceNamespace)))
                true
            },
            onSearch = {
                searchActions.applyDraft()
                controls.setPlaybackEnabled(false)
                host.onGoToSearch()
                controls.setInfoSheetVisible(false)
            },
            sheetExtras = PostTagSheetExtras(
                tagsInitiallyCollapsed = tagsStartCollapsed,
                scrollToAfterTags = openedAtComments,
                scrollToKey = highlightedComment,
                filterBehindIcon = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
                onFork = onFork,
                trailActions = rememberViewerTagTrailActions(trail, post, onFork),
                levelQuery = saveToCodexQuery,
                afterTags = {
                    commentsState?.let { state ->
                        val view = commentsView(
                            comments, commentsCollapsed, { commentsCollapsed = !commentsCollapsed }, highlightedComment,
                            config.creatorBrowsingSources, controls, onAction,
                        )
                        commentsSection(post, state, commentCount, comments.actions, nowEpochMs, view)
                    }
                },
            ),
        )
    }
}

@Immutable
class ViewerContextActions(
    val showRelated: ((Post) -> Unit)? = null,
    val removeFromCodex: ((Post) -> Unit)? = null,
)

@Composable
private fun ViewerInfoActions(
    selection: ViewerSelection,
    liked: Boolean,
    controls: ViewerControls,
    creatorBrowsingSources: Set<SourceKey>,
    onAction: (ViewerAction) -> Unit,
    onGoToSearch: () -> Unit,
    onOpenCreatorFallback: ((Post) -> Unit)?,
    contextActions: ViewerContextActions,
) {
    val post = selection.post
    val creators = if (onOpenCreatorFallback != null) creatorProfileActions(post, creatorBrowsingSources) else emptyList()
    fun closeThen(action: () -> Unit) {
        action()
        controls.setInfoSheetVisible(false)
    }
    fun openCreator(creator: CreatorProfileAction) {
        controls.setPlaybackEnabled(false)
        creator.profile?.let { onAction(ViewerAction.OpenCreator(it)) } ?: onOpenCreatorFallback?.invoke(post)
    }
    val menuActions = LocalPostMenuActions.current
    val share = menuActions.shareFor(post)
    val relatedSupported = LocalRelatedPostsSupport.current(post.id.source)
    val moreActions = buildList {
        contextActions.showRelated?.takeIf { relatedSupported }?.let { show ->
            add(PostMenuAction("More like this", Icons.Default.AutoAwesome) { show(post) })
        }
        creators.drop(1).forEach { creator -> add(PostMenuAction(creator.label, Icons.Default.Person) { openCreator(creator) }) }
        if (selection.mediaIndex > 0) add(PostMenuAction("Start over", Icons.Default.Replay) { controls.jumpToMedia(post.id, 0) })
        add(PostMenuAction("Go to Search", Icons.Default.Search) { controls.setPlaybackEnabled(false); onGoToSearch() })
        addAll(menuActions.availableFor(post))
    }
    Column {
        PostActionHeader(
            post,
            showPostId = true,
            creator = creators.firstOrNull()?.let { creator -> HeaderLink(creator.label) { closeThen { openCreator(creator) } } },
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), Arrangement.SpaceEvenly) {
            ViewerLikeAction(liked) { onAction(ViewerAction.ToggleLike) }
            ViewerCodexAction(post.id in LocalCodexPostIds.current.value) { closeThen { onAction(ViewerAction.Save) } }
            ViewerInfoAction(Icons.Default.Download, "Device", "Save to device", selection.canDownload) {
                closeThen { onAction(ViewerAction.Download) }
            }
            ViewerInfoAction(Icons.Default.Share, "Share", "Share", enabled = share != null) { share?.let(::closeThen) }
            ViewerInfoMoreActions(moreActions, contextActions.removeFromCodex?.let { remove -> { remove(post) } }, ::closeThen)
        }
        HorizontalDivider()
    }
}

@Composable
private fun ViewerChapterActions(post: Post, onStartOver: () -> Unit) {
    Column {
        PostActionHeader(post)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), Arrangement.SpaceEvenly) {
            ViewerInfoAction(Icons.Default.Replay, "Start over", "Start over", onClick = onStartOver)
        }
    }
}

@Composable
private fun ViewerLikeAction(liked: Boolean, onToggle: () -> Unit) {
    if (liked) {
        ViewerInfoAction(Icons.Default.Favorite, "Liked", "Unlike post", tint = LikedHeartColor, onClick = onToggle)
    } else {
        ViewerInfoAction(Icons.Outlined.FavoriteBorder, "Like", "Like post", onClick = onToggle)
    }
}

@Composable
private fun ViewerCodexAction(inCodex: Boolean, onClick: () -> Unit) {
    if (inCodex) {
        ViewerInfoAction(Icons.Default.BookmarkAdded, "Codex", "In a Codex. Save to Codex", tint = codexSavedColor(), onClick = onClick)
    } else {
        ViewerInfoAction(Icons.Default.BookmarkAdd, "Codex", "Save to Codex", onClick = onClick)
    }
}

@Composable
private fun ViewerInfoMoreActions(actions: List<PostMenuAction>, removeFromCodex: (() -> Unit)?, closeThen: (() -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ViewerInfoAction(Icons.Default.MoreHoriz, "More", "More post actions") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                DropdownMenuItem(text = { Text(action.label) }, onClick = { closeThen(action.run) })
            }
            removeFromCodex?.let { remove ->
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Remove from Codex", color = MaterialTheme.colorScheme.error) },
                    onClick = { closeThen(remove) },
                )
            }
        }
    }
}

@Composable
private fun ViewerInfoAction(
    icon: ImageVector,
    label: String,
    description: String,
    enabled: Boolean = true,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .sizeIn(minWidth = 56.dp, minHeight = 48.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint ?: LocalContentColor.current)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
