package com.theoriacodex.app.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.MainActivity
import com.theoriacodex.app.codex.PreloadCodexSaveRelevance
import com.theoriacodex.app.codex.codexSavedColor
import com.theoriacodex.app.comments.CommentCount
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
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.tags.LocalPostTagSearchActions
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.ui.components.HeaderLink
import com.theoriacodex.app.ui.components.PostActionHeader
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey

/** Everything drawn over the media: top bar, post strip, rotate and slideshow buttons, and related drawer. */
@Composable
internal fun BoxScope.ViewerChromeOverlays(
    frame: ViewerFrame,
    postPagerState: PagerState,
    screen: ViewerScreenState,
    controls: ViewerControls,
    swipe: ViewerSwipeController,
    activity: MainActivity?,
    quality: MutableState<com.theoriacodex.domain.model.VideoQuality>,
    qualityHeight: MutableState<Int?>,
    pendingPostId: MutableState<PostId?>,
    likedPostIds: Set<PostId>,
    invertMultiImageScrollDirection: Boolean,
    onAction: (ViewerAction) -> Unit,
    loadMoreFromStrip: () -> Unit,
    onIncludeRelatedPosts: () -> Unit,
    onDismissRelatedPosts: () -> Unit,
    onRetryRelatedPosts: () -> Unit,
    comments: ViewerComments,
) {
    val selection = frame.selection
    val navigation = frame.navigation
    OpenInfoSheetForFocusedComment(comments, selection.post, frame.uiState.controls.metadataVisible, controls)
    // While scrubbing everything but the timeline steps back. Only its alpha changes, so the strip keeps its scroll position.
    val scrubFade by animateFloatAsState(if (screen.timelineInteractionActive) 0f else 1f, tween(TheoriaMotion.SHORT_MS), label = "Viewer scrub fade")
    val fadesWhileScrubbing = Modifier.graphicsLayer { alpha = scrubFade }
    val mirrored = viewerMediaPagerReverseLayout(selection.mediaItems.size, invertMultiImageScrollDirection)
    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_STRIPS && !frame.mediaOverviewVisible, Modifier.align(Alignment.BottomCenter)) {
        val stripIndex = navigation.originalPosts.indexOfFirst { it.id == frame.posts.getOrNull(postPagerState.currentPage)?.id }
            .takeIf { it >= 0 } ?: navigation.stripAnchorIndex
        ViewerBottomChrome(
            timelinePresent = selection.isAnimated,
            fadesWhileScrubbing = fadesWhileScrubbing,
            pageBar = {
                if (selection.overviewItems.size > 1) {
                    // Keyed by post so a drag held across a post change is cancelled instead of landing on the new post.
                    key(selection.post.id) {
                        ViewerPageScrubber(
                            source = selection.post.id.source,
                            items = selection.overviewItems,
                            aspectRatio = viewerAspectRatio(selection.post),
                            pageIndex = selection.mediaIndex,
                            mirrored = mirrored,
                            onPageSelected = { controls.jumpToMedia(selection.post.id, it) },
                            onInteractionActiveChanged = { controls.onTimelineInteractionChanged(it) },
                        )
                    }
                }
            },
        ) {
            ViewerPostStrip(navigation.originalPosts, stripIndex, { index ->
                pendingPostId.value = navigation.originalPosts[index].id
            }, Modifier.testTag("viewer-post-navigation-strip"),
                onLoadMore = loadMoreFromStrip, onInteraction = { controls.markInteraction() },
                openPages = ViewerStripPages(selection.post.id, selection.overviewItems, selection.mediaIndex, mirrored) {
                    controls.jumpToMedia(selection.post.id, it)
                })
        }
    }
    ViewerFooterButtons(frame, screen, activity, onAction)
    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_TOP_BAR, Modifier.align(Alignment.TopCenter).then(fadesWhileScrubbing)) {
        ViewerTopBar(frame, controls, activity, quality, qualityHeight, selection.post.id in likedPostIds, comments.countOf(selection.post), onAction)
    }
    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_STRIPS && !frame.mediaOverviewVisible, Modifier.align(Alignment.TopEnd).padding(top = 72.dp).then(fadesWhileScrubbing)) {
        ViewerRelatedStrip(
            state = frame.uiState.related.state, posts = frame.uiState.related.posts, currentPostId = selection.post.id,
            onSelect = { post ->
                controls.markInteraction()
                if (post.id != selection.post.id) {
                    pendingPostId.value = post.id
                    onIncludeRelatedPosts()
                }
            },
            onDismiss = { onDismissRelatedPosts(); controls.markInteraction() },
            onRetry = { onRetryRelatedPosts(); controls.markInteraction() },
            enabled = !swipe.settling && !swipe.dragging,
            onExpandedChange = { screen.relatedStripExpanded = it },
            onInteraction = { controls.markInteraction() },
        )
    }
}

/** The rotate button on the left and the slideshow button on the right, hidden while another surface owns the bottom. */
@Composable
private fun BoxScope.ViewerFooterButtons(frame: ViewerFrame, screen: ViewerScreenState, activity: MainActivity?, onAction: (ViewerAction) -> Unit) {
    val bottomOwned = frame.mediaOverviewVisible || screen.timelineInteractionActive
    // A running slideshow keeps the image clear, including of the rotate hint.
    val rotationSuppressed = bottomOwned || frame.uiState.controls.slideshow
    ViewerRotationControl(frame.selection, activity?.viewerRotation, frame.inPictureInPicture, frame.chromeVisible, rotationSuppressed)
    ViewerSlideshowControl(frame, suppressed = bottomOwned || frame.inPictureInPicture, onAction)
}

@Composable
private fun ViewerTopBar(
    frame: ViewerFrame,
    controls: ViewerControls,
    activity: MainActivity?,
    quality: MutableState<com.theoriacodex.domain.model.VideoQuality>,
    qualityHeight: MutableState<Int?>,
    liked: Boolean,
    commentCount: CommentCount?,
    onAction: (ViewerAction) -> Unit,
) {
    val uiState = frame.uiState
    val selection = frame.selection
    val playbackAvailable = uiState.controls.playback.available
    ViewerChrome(
        onPictureInPicture = viewerPictureInPictureAction(activity, selection.post, selection.media, uiState.mediaError != null),
        playbackMode = uiState.controls.playbackMode,
        onPlaybackModeSelected = { onAction(ViewerAction.SetPlaybackMode(it)) },
        quality = quality.value,
        qualityHeight = qualityHeight.value,
        videoVariants = uiState.currentMedia?.ref?.videoVariants.orEmpty(),
        onQualitySelected = { selected, height -> quality.value = selected; qualityHeight.value = height },
        source = selection.post.id.source.displayName(),
        indexLabel = if (selection.mediaItems.size > 1) "${selection.mediaIndex + 1} / ${selection.mediaItems.size}" else null,
        onBack = { controls.requestDismiss() },
        liked = liked,
        onToggleLike = {
            onAction(ViewerAction.ToggleLike)
            controls.markInteraction()
        },
        actionsMenuExpanded = uiState.controls.actionsMenuVisible,
        onActionsMenuExpandedChange = { expanded ->
            controls.setActionsMenuExpanded(expanded)
            if (expanded) {
                controls.setPlaybackSettingsExpanded(false)
                controls.markInteraction()
            }
        },
        playbackSettingsExpanded = uiState.controls.playbackSettingsVisible,
        onPlaybackSettingsExpandedChange = { expanded ->
            controls.setPlaybackSettingsExpanded(expanded && playbackAvailable)
            if (expanded && playbackAvailable) {
                controls.setActionsMenuExpanded(false)
                controls.markInteraction()
            }
        },
        playbackSettingsEnabled = playbackAvailable,
        playbackRateEnabled = selection.isSeekable,
        playbackRate = closestViewerPlaybackRate(uiState.controls.playback.playbackRate),
        onPlaybackRateSelected = { selectedRate ->
            onAction(ViewerAction.SetPlaybackRate(selectedRate.speed))
            controls.markInteraction()
        },
        mediaOverviewAvailable = selection.overviewAvailable,
        mediaOverviewVisible = frame.mediaOverviewVisible,
        onToggleMediaOverview = {
            if (selection.overviewAvailable) {
                controls.setOverviewVisible(!frame.mediaOverviewVisible)
                controls.setActionsMenuExpanded(false)
                controls.setPlaybackSettingsExpanded(false)
                controls.markInteraction()
            }
        },
        onInfo = { controls.showInfoSheet(atComments = false) },
        commentCount = commentCount,
        onComments = { controls.showInfoSheet(atComments = true) },
    )
}

/** How long a comment stays tinted after a link led to it, before the tint fades. */
private const val COMMENT_HIGHLIGHT_MS = 1400L

/**
 * The comment a link led to, while it is to be scrolled to and tinted. It is taken from the post's comments
 * state, which clears it so that it acts once, and held for [COMMENT_HIGHLIGHT_MS]. Null at other times.
 */
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

/** The comments section as the info sheet shows it; an author's creator page replaces the Viewer, a profile on the site opens beside it. */
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
            // Playback pauses and the sheet closes, as for the post's own creator.
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

/** When a comment link's target is found, shows the comments; the info sheet scrolls to it and clears the focus. */
@Composable
internal fun OpenInfoSheetForFocusedComment(comments: ViewerComments, post: Post, infoSheetVisible: Boolean, controls: ViewerControls) {
    val focusKey = comments.stateOf(post)?.focusKey
    LaunchedEffect(focusKey) {
        if (focusKey != null && !infoSheetVisible) controls.openInfoSheetAtComments()
    }
}

/** Opens the info sheet from the top bar, at its top or scrolled to the comments. */
private fun ViewerControls.showInfoSheet(atComments: Boolean) {
    setActionsMenuExpanded(false)
    if (atComments) openInfoSheetAtComments() else setInfoSheetVisible(true)
    markInteraction()
}

@Composable
internal fun ViewerInfoSheet(
    selection: ViewerSelection,
    controls: ViewerControls,
    creatorBrowsingSources: Set<SourceKey>,
    saveToCodexQuery: Query?,
    tagVideoCountProvider: (SourceKey, String) -> Int?,
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?>,
    onAction: (ViewerAction) -> Unit,
    onOpenInBrowser: (Post) -> Unit,
    onOpenOriginalPost: (Post) -> Unit,
    findOnOtherSites: (Post) -> (() -> Unit)?,
    onRemoveIncludeTerm: (Post, SearchTerm) -> Unit,
    onRemoveExcludeTerm: (Post, SearchTerm) -> Unit,
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)?,
    onGoToSearch: () -> Unit,
    onOpenCreatorFallback: ((Post) -> Unit)?,
    liked: Boolean = false,
    inCodex: Boolean = false,
    comments: ViewerComments = ViewerComments.None,
) {
    val post = selection.post
    val searchActions = LocalPostTagSearchActions.current
    PreloadCodexSaveRelevance(post, saveToCodexQuery)
    // Both settle as the sheet opens: whatever opened it, and whether the post has comments then. Neither moves later.
    val openedAtComments = remember(post.id) { controls.infoSheetTarget == InfoSheetTarget.Comments }
    val tagsStartCollapsed = remember(post.id) { comments.countOf(post) != null }
    DisposableEffect(Unit) { onDispose { controls.resetInfoSheetTarget() } }
    val commentsState = comments.stateOf(post)
    val commentCount = comments.countOf(post)
    val nowEpochMs = remember { System.currentTimeMillis() }
    var commentsCollapsed by remember(post.id) { mutableStateOf(false) }
    val highlightedComment = rememberHighlightedComment(post, comments)
    AdaptiveViewerInfo(onDismiss = { controls.setInfoSheetVisible(false) }) {
        PostTagActionSection(
            header = {
                ViewerInfoActions(
                    selection, liked, inCodex, controls, creatorBrowsingSources, onAction, onOpenInBrowser, onOpenOriginalPost,
                    findOnOtherSites, onGoToSearch, onOpenCreatorFallback,
                )
            },
            post = post,
            tagVideoCountProvider = tagVideoCountProvider,
            fetchTagVideoCounts = fetchTagVideoCounts,
            onAddIncludeTerm = { term ->
                onAction(ViewerAction.IncludeTag(PostTaxonomyTerm(term.value, term.facet, term.sourceNamespace)))
                true
            },
            onAddExcludeTerm = { term ->
                onAction(ViewerAction.ExcludeTag(PostTaxonomyTerm(term.value, term.facet, term.sourceNamespace)))
                true
            },
            onRemoveIncludeTerm = { term -> onRemoveIncludeTerm(post, term) },
            onRemoveExcludeTerm = { term -> onRemoveExcludeTerm(post, term) },
            onFavoriteTagLongPress = onFavoriteTagLongPress,
            onSearch = {
                searchActions.applyDraft()
                controls.setPlaybackEnabled(false)
                onGoToSearch()
                controls.setInfoSheetVisible(false)
            },
            sheetExtras = PostTagSheetExtras(
                tagsInitiallyCollapsed = tagsStartCollapsed,
                scrollToAfterTags = openedAtComments,
                scrollToKey = highlightedComment,
                afterTags = {
                    commentsState?.let { state ->
                        val view = commentsView(
                            comments, commentsCollapsed, { commentsCollapsed = !commentsCollapsed }, highlightedComment,
                            creatorBrowsingSources, controls, onAction,
                        )
                        commentsSection(post, state, commentCount, comments.actions, nowEpochMs, view)
                    }
                },
            ),
        )
    }
}

/**
 * Identifies the post, links its creator, and offers a compact action row, so its tags stay near the
 * top of the sheet. Liking stays in the sheet; the other actions close it.
 */
@Composable
private fun ViewerInfoActions(
    selection: ViewerSelection,
    liked: Boolean,
    inCodex: Boolean,
    controls: ViewerControls,
    creatorBrowsingSources: Set<SourceKey>,
    onAction: (ViewerAction) -> Unit,
    onOpenInBrowser: (Post) -> Unit,
    onOpenOriginalPost: (Post) -> Unit,
    findOnOtherSites: (Post) -> (() -> Unit)?,
    onGoToSearch: () -> Unit,
    onOpenCreatorFallback: ((Post) -> Unit)?,
) {
    val post = selection.post
    val creators = if (onOpenCreatorFallback != null) creatorProfileActions(post, creatorBrowsingSources) else emptyList()
    fun closeThen(action: () -> Unit) {
        action()
        controls.setInfoSheetVisible(false)
    }
    fun openCreator(creator: CreatorProfileAction) = closeThen {
        controls.setPlaybackEnabled(false)
        creator.profile?.let { onAction(ViewerAction.OpenCreator(it)) } ?: onOpenCreatorFallback?.invoke(post)
    }
    Column {
        PostActionHeader(
            post,
            showPostId = true,
            creator = creators.firstOrNull()?.let { creator -> HeaderLink(creator.label) { openCreator(creator) } },
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), Arrangement.SpaceEvenly) {
            ViewerLikeAction(liked) { onAction(ViewerAction.ToggleLike) }
            ViewerCodexAction(inCodex) { closeThen { onAction(ViewerAction.Save) } }
            ViewerInfoAction(Icons.Default.Download, "Device", "Save to device", selection.canDownload) {
                closeThen { onAction(ViewerAction.Download) }
            }
            ViewerInfoAction(Icons.Default.Share, "Share", "Share") { closeThen { onAction(ViewerAction.Share) } }
            ViewerInfoMoreActions(
                otherCreators = creators.drop(1),
                onOpenCreator = ::openCreator,
                onGoToSearch = { closeThen { controls.setPlaybackEnabled(false); onGoToSearch() } },
                onOpenOriginalPost = if (post.originUrl == null) null else ({ closeThen { onOpenOriginalPost(post) } }),
                onFindOnOtherSites = findOnOtherSites(post)?.let { find -> { closeThen(find) } },
                onOpenInBrowser = if (post.pageUrl.isNullOrBlank()) null else ({ closeThen { onOpenInBrowser(post) } }),
            )
        }
        HorizontalDivider()
    }
}

/** Like stays in the sheet, so its label and heart follow the post's liked state. */
@Composable
private fun ViewerLikeAction(liked: Boolean, onToggle: () -> Unit) {
    if (liked) {
        ViewerInfoAction(Icons.Default.Favorite, "Liked", "Unlike post", tint = LikedHeartColor, onClick = onToggle)
    } else {
        ViewerInfoAction(Icons.Outlined.FavoriteBorder, "Like", "Like post", onClick = onToggle)
    }
}

/** Marks a post already in a Codex of its own (Likes aside); opening the sheet still adds it to more. */
@Composable
private fun ViewerCodexAction(inCodex: Boolean, onClick: () -> Unit) {
    if (inCodex) {
        ViewerInfoAction(Icons.Default.BookmarkAdded, "Codex", "In a Codex. Save to Codex", tint = codexSavedColor(), onClick = onClick)
    } else {
        ViewerInfoAction(Icons.Default.BookmarkAdd, "Codex", "Save to Codex", onClick = onClick)
    }
}

@Composable
private fun ViewerInfoMoreActions(
    otherCreators: List<CreatorProfileAction>,
    onOpenCreator: (CreatorProfileAction) -> Unit,
    onGoToSearch: () -> Unit,
    onOpenOriginalPost: (() -> Unit)?,
    onFindOnOtherSites: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ViewerInfoAction(Icons.Default.MoreHoriz, "More", "More post actions") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            otherCreators.forEach { creator ->
                DropdownMenuItem(text = { Text(creator.label) }, onClick = { onOpenCreator(creator) })
            }
            DropdownMenuItem(text = { Text("Go to Search") }, onClick = onGoToSearch)
            onOpenOriginalPost?.let { open -> DropdownMenuItem(text = { Text("Open original post") }, onClick = open) }
            onFindOnOtherSites?.let { find -> DropdownMenuItem(text = { Text("Find on other sites") }, onClick = find) }
            onOpenInBrowser?.let { open -> DropdownMenuItem(text = { Text("Open in browser") }, onClick = open) }
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

