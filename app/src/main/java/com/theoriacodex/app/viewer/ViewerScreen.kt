package com.theoriacodex.app.viewer

import android.content.res.Configuration
import android.content.Context
import android.graphics.Movie
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.drawable.ScaleDrawable
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.github.penfeizhou.animation.webp.WebPDrawable
import com.theoriacodex.app.creator.CreatorProfileActionButton
import com.theoriacodex.app.media.isAnimatedImageMediaRef
import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.isHttpNotFound
import com.theoriacodex.app.media.isPixivUgoiraMedia
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.media.PostMediaKind
import com.theoriacodex.app.media.mediaKind
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.app.media.progressiveImageCandidates
import com.theoriacodex.app.media.supportsProgressiveImageCandidates
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaError
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.delay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ViewerScreen(
    uiState: ViewerUiState,
    creatorBrowsingSources: Set<SourceKey>,
    onAction: (ViewerAction) -> Unit,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    canLoadMoreFromSource: Boolean = false,
    loadingMoreFromSource: Boolean = false,
    invertMultiImageScrollDirection: Boolean = false,
    onInvertMultiImageScrollDirectionChange: (Boolean) -> Unit = {},
    likedPostIds: Set<PostId> = emptySet(),
    onRequestMediaRecovery: ((Post, ImageRef) -> Unit)? = null,
    onVisiblePostChanged: ((Post) -> Unit)? = null,
    onOpenInBrowser: (Post) -> Unit,
    onRemoveIncludeTerm: (Post, SearchTerm) -> Unit,
    onRemoveExcludeTerm: (Post, SearchTerm) -> Unit,
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)? = null,
    onGoToSearch: () -> Unit,
    onOpenCreatorFallback: ((Post) -> Unit)? = null,
) {
    val posts = uiState.pages.map { page -> page.post }
    if (posts.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Text("No posts to view", color = MaterialTheme.colorScheme.onBackground)
        }
        return
    }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    val initialIndex = uiState.currentPageIndex.coerceIn(0, posts.lastIndex)
    val postPagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { posts.size },
    )
    var viewerState by remember(uiState.session?.value) {
        mutableStateOf(ViewerState(streamSize = posts.size, currentIndex = initialIndex))
    }
    var interactionSerial by remember { mutableIntStateOf(0) }
    var timelineInteractionActive by remember { mutableStateOf(false) }
    var lastViewerPaginationRequestSize by remember(posts.size) { mutableIntStateOf(-1) }
    val loadedMediaUrls = remember { mutableStateMapOf<String, Boolean>() }
    var pendingDismiss by remember { mutableStateOf(false) }
    val pendingMediaJumpByPost = remember { mutableStateMapOf<Int, Int>() }
    val mediaRecoveryRequestedKeys = remember { mutableSetOf<String>() }
    val currentPostIndex = uiState.currentPageIndex.coerceIn(0, posts.lastIndex)
    val selectedPost = posts[currentPostIndex]
    val selectedPostLiked = selectedPost.id in likedPostIds
    val selectedPostMedia = remember(selectedPost) { viewerMediaItems(selectedPost) }
    val selectedMediaIndex = uiState.pages
        .getOrNull(currentPostIndex)
        ?.selectedMediaIndex
        ?.coerceIn(0, selectedPostMedia.lastIndex)
        ?: 0
    val selectedCurrentMedia = selectedPostMedia.getOrNull(selectedMediaIndex)
    val currentIsSeekableMedia = selectedCurrentMedia?.let { media ->
        isVideoMediaRef(media) ||
            isGifMediaRef(media) ||
            (isPixivUgoira(selectedPost, media) && pixivUgoiraClient != null)
    } == true
    val canDownloadCurrentMedia = selectedCurrentMedia?.let { media ->
        (isPixivUgoira(selectedPost, media) && pixivUgoiraClient != null) || !media.url.isNullOrBlank()
    } == true
    val selectedMediaOverviewItems = remember(selectedPost) { viewerMediaOverviewItems(selectedPost) }
    val mediaOverviewAvailable = viewerMediaOverviewAvailable(selectedMediaOverviewItems)
    val chromeVisible = uiState.controls.chromeVisible
    val showInfoSheet = uiState.controls.metadataVisible
    val mediaPlaybackEnabled = uiState.controls.playback.playing
    val playbackRestartRequest = uiState.controls.playback.restartRequest
    val playbackRate = closestViewerPlaybackRate(uiState.controls.playback.playbackRate)
    val actionsMenuExpanded = uiState.controls.actionsMenuVisible
    val playbackSettingsExpanded = uiState.controls.playbackSettingsVisible
    val mediaOverviewVisible = uiState.overview.visible

    fun setInfoSheetVisible(visible: Boolean) {
        onAction(if (visible) ViewerAction.ShowMetadata else ViewerAction.HideMetadata)
    }

    fun setPlaybackEnabled(enabled: Boolean) {
        onAction(if (enabled) ViewerAction.Play else ViewerAction.Pause)
    }

    fun setActionsMenuExpanded(expanded: Boolean) {
        onAction(if (expanded) ViewerAction.ShowActionsMenu else ViewerAction.HideActionsMenu)
    }

    fun setPlaybackSettingsExpanded(expanded: Boolean) {
        onAction(if (expanded) ViewerAction.ShowPlaybackSettings else ViewerAction.HidePlaybackSettings)
    }

    fun setOverviewVisible(visible: Boolean) {
        if (visible != uiState.overview.visible) {
            onAction(ViewerAction.ToggleOverview)
        }
    }

    fun reportRouteMediaFailure(message: String) {
        val session = uiState.session ?: return
        val mediaKey = uiState.currentMedia?.key ?: return
        onAction(
            ViewerAction.MediaFailed(
                session = session,
                error = ViewerMediaError.Recoverable(mediaKey = mediaKey, message = message),
            )
        )
    }

    LaunchedEffect(uiState.session) {
        pendingDismiss = false
    }

    LaunchedEffect(currentIsSeekableMedia, chromeVisible) {
        if (!currentIsSeekableMedia) {
            setPlaybackSettingsExpanded(false)
        }
        if (!chromeVisible) {
            setActionsMenuExpanded(false)
            setPlaybackSettingsExpanded(false)
        }
    }

    LaunchedEffect(mediaOverviewAvailable) {
        if (!mediaOverviewAvailable) {
            setOverviewVisible(false)
        }
    }

    LaunchedEffect(
        selectedPost.id.source,
        selectedPost.id.sourcePostId,
        selectedPost.full?.url,
        selectedPost.full?.localPath,
        uiState.currentPage?.resolution?.status,
    ) {
        val status = uiState.currentPage?.resolution?.status
        if (
            status == com.theoriacodex.app.viewer.state.ViewerResolutionStatus.IDLE ||
            status == com.theoriacodex.app.viewer.state.ViewerResolutionStatus.FAILED
        ) {
            onAction(ViewerAction.RequestCurrentPageResolution)
        }
    }

    LaunchedEffect(pendingDismiss) {
        if (!pendingDismiss) return@LaunchedEffect
        withFrameNanos { }
        onAction(ViewerAction.Dismiss)
    }

    fun requestDismissViewer() {
        if (pendingDismiss) return
        setPlaybackEnabled(false)
        setInfoSheetVisible(false)
        setActionsMenuExpanded(false)
        setPlaybackSettingsExpanded(false)
        setOverviewVisible(false)
        pendingDismiss = true
    }

    fun markInteraction() {
        interactionSerial += 1
        if (!chromeVisible) {
            onAction(ViewerAction.ToggleChrome)
        }
    }

    BackHandler(enabled = !pendingDismiss) {
        if (mediaOverviewVisible) {
            setOverviewVisible(false)
            markInteraction()
        } else {
            requestDismissViewer()
        }
    }

    LaunchedEffect(posts, currentPostIndex, selectedMediaIndex) {
        val currentKey = uiState.currentMedia?.key
        val keys = uiState.pages
            .asSequence()
            .flatMap { page -> page.media.asSequence() }
            .map { media -> media.key }
            .filter { key -> key != currentKey }
            .take(VIEWER_PREFETCH_LEFT_COUNT + VIEWER_PREFETCH_RIGHT_COUNT)
            .toList()
        onAction(ViewerAction.QueuePrefetch(keys))
    }

    LaunchedEffect(posts.size, currentPostIndex, canLoadMoreFromSource, loadingMoreFromSource) {
        if (loadingMoreFromSource || !canLoadMoreFromSource) return@LaunchedEffect
        val triggerIndex = ((posts.lastIndex.coerceAtLeast(0)) * VIEWER_PAGINATION_PREFETCH_RATIO)
            .toInt()
            .coerceAtLeast(0)
        if (currentPostIndex >= triggerIndex && lastViewerPaginationRequestSize != posts.size) {
            lastViewerPaginationRequestSize = posts.size
            onAction(ViewerAction.LoadMore)
        }
    }

    fun onTimelineInteractionChanged(isActive: Boolean) {
        timelineInteractionActive = isActive
        if (isActive) {
            markInteraction()
        }
    }

    LaunchedEffect(uiState.currentPageIndex, posts.size) {
        val targetPage = uiState.currentPageIndex.coerceIn(0, posts.lastIndex)
        if (postPagerState.currentPage != targetPage) {
            postPagerState.scrollToPage(targetPage)
        }
        setOverviewVisible(false)
        markInteraction()
    }

    LaunchedEffect(selectedPost.id, currentPostIndex) {
        onVisiblePostChanged?.invoke(selectedPost)
    }

    LaunchedEffect(chromeVisible, interactionSerial, timelineInteractionActive, mediaOverviewVisible) {
        if (chromeVisible && !timelineInteractionActive && !mediaOverviewVisible) {
            val serial = interactionSerial
            delay(1500)
            if (serial == interactionSerial && !timelineInteractionActive && !mediaOverviewVisible) {
                onAction(ViewerAction.ToggleChrome)
            }
        }
    }

    LaunchedEffect(chromeVisible, currentIsSeekableMedia) {
        if (!chromeVisible || !currentIsSeekableMedia) {
            timelineInteractionActive = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (mediaOverviewVisible && mediaOverviewAvailable) {
            ViewerMediaOverviewGrid(
                post = selectedPost,
                items = selectedMediaOverviewItems,
                selectedMediaIndex = selectedMediaIndex,
                onMediaSelected = { mediaIndex ->
                    onAction(ViewerAction.SelectOverviewMedia(mediaIndex))
                    pendingMediaJumpByPost[currentPostIndex] = mediaIndex
                    setOverviewVisible(false)
                    markInteraction()
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            HorizontalPager(
                state = postPagerState,
                userScrollEnabled = false,
                modifier = Modifier.fillMaxSize(),
            ) { postPage ->
            val post = posts[postPage]
            val postMedia = remember(post) { viewerMediaItems(post) }
            val mediaPagerReverseLayout = viewerMediaPagerReverseLayout(
                mediaCount = postMedia.size,
                invertMultiImageScrollDirection = invertMultiImageScrollDirection,
            )
            val initialMediaPage = uiState.pages
                .getOrNull(postPage)
                ?.selectedMediaIndex
                ?.coerceIn(0, postMedia.lastIndex)
                ?: 0
            val mediaPagerState = rememberPagerState(
                initialPage = initialMediaPage,
                pageCount = { postMedia.size },
            )
            val pendingMediaJump = pendingMediaJumpByPost[postPage]

            LaunchedEffect(initialMediaPage, postMedia.size) {
                if (mediaPagerState.currentPage != initialMediaPage) {
                    mediaPagerState.scrollToPage(initialMediaPage)
                }
            }

            LaunchedEffect(pendingMediaJump, postMedia.size) {
                if (pendingMediaJump == null) return@LaunchedEffect
                val targetPage = pendingMediaJump.coerceIn(0, postMedia.lastIndex)
                mediaPagerState.scrollToPage(targetPage)
                if (postPage == currentPostIndex && selectedMediaIndex != targetPage) {
                    onAction(ViewerAction.SelectMedia(targetPage))
                }
                pendingMediaJumpByPost.remove(postPage)
            }

            LaunchedEffect(mediaPagerState.currentPage) {
                if (postPage == currentPostIndex && mediaPagerState.currentPage != selectedMediaIndex) {
                    onAction(ViewerAction.SelectMedia(mediaPagerState.currentPage))
                }
                if (postPage == postPagerState.currentPage) {
                    markInteraction()
                }
            }

            fun navigateFromHorizontalSwipe(direction: ViewerHorizontalSwipeDirection) {
                if (viewerState.zoom > ViewerState.FIT_SCALE + 0.01f) return
                if (postPage != postPagerState.currentPage) return
                val targetPostIndex = when (direction) {
                    ViewerHorizontalSwipeDirection.Previous -> postPage - 1
                    ViewerHorizontalSwipeDirection.Next -> postPage + 1
                }
                val targetPostMediaCount = posts.getOrNull(targetPostIndex)
                    ?.let { viewerMediaItems(it).size }
                    ?: 0
                val target = viewerHorizontalSwipeTarget(
                    currentPostIndex = postPage,
                    currentMediaIndex = mediaPagerState.currentPage,
                    currentMediaCount = postMedia.size,
                    postCount = posts.size,
                    targetPostMediaCount = targetPostMediaCount,
                    direction = direction,
                ) ?: return

                markInteraction()
                if (target.postIndex == postPage) {
                    scope.launch {
                        mediaPagerState.animateScrollToPage(target.mediaIndex)
                    }
                } else {
                    onAction(ViewerAction.SelectPage(target.postIndex))
                    onAction(ViewerAction.SelectMedia(target.mediaIndex))
                    pendingMediaJumpByPost[target.postIndex] = target.mediaIndex
                    scope.launch {
                        postPagerState.animateScrollToPage(target.postIndex)
                    }
                }
            }

            HorizontalPager(
                state = mediaPagerState,
                userScrollEnabled = false,
                reverseLayout = mediaPagerReverseLayout,
                modifier = Modifier.fillMaxSize(),
                contentPadding = if (isLandscape) {
                    PaddingValues(0.dp)
                } else {
                    PaddingValues(top = 40.dp)
                },
                pageSpacing = if (isLandscape) 0.dp else 8.dp,
            ) { mediaPage ->
                val media = postMedia[mediaPage]
                val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                    viewerState = viewerState.transform(
                        zoomChange = zoomChange,
                        panChangeX = panChange.x,
                        panChangeY = panChange.y,
                    )
                    markInteraction()
                }
                val isVideoMedia = isVideoMediaRef(media)
                val isGifMedia = isGifMediaRef(media)
                val isControlledAnimatedWebP = post.id.source == SourceKey.HITOMI &&
                    isAnimatedImageMediaRef(media)
                val showUgoira = isPixivUgoira(post, media) && pixivUgoiraClient != null
                val isCurrentMediaPage =
                    postPage == postPagerState.currentPage &&
                        mediaPage == mediaPagerState.currentPage
                val isSeekableMedia = isVideoMedia || isGifMedia || showUgoira
                val hasBottomTimeline = chromeVisible &&
                    (isVideoMedia || isGifMedia || showUgoira || isControlledAnimatedWebP)
                var seekJumpSerial by remember(postPage, mediaPage) { mutableIntStateOf(0) }
                var seekJumpDeltaMs by remember(postPage, mediaPage) { mutableLongStateOf(0L) }
                var seekFeedbackSerial by remember(postPage, mediaPage) { mutableIntStateOf(0) }
                var seekFeedback by remember(postPage, mediaPage) { mutableStateOf<SeekJumpFeedback?>(null) }
                val mediaContainerPadding = when {
                    isLandscape -> 0.dp
                    isVideoMedia || isGifMedia || showUgoira || isControlledAnimatedWebP -> 0.dp
                    else -> 16.dp
                }
                val mediaAspectRatio = remember(post.width, post.height) {
                    val width = post.width?.takeIf { it > 0 }
                    val height = post.height?.takeIf { it > 0 }
                    if (width != null && height != null) {
                        width.toFloat() / height.toFloat()
                    } else {
                        null
                    }
                }
                val gifLocation = remember(post, media) { viewerGifLocation(post, media) }
                val mediaGestureModifier = Modifier.pointerInput(postPage, mediaPage) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            val tapRatio = if (size.width > 0) {
                                offset.x / size.width.toFloat()
                            } else {
                                0.5f
                            }
                            val isCenterTap = tapRatio > 0.35f && tapRatio < 0.65f
                            val seekDeltaMs = when {
                                !isSeekableMedia -> 0L
                                tapRatio <= 0.35f -> -10_000L
                                tapRatio >= 0.65f -> 10_000L
                                else -> 0L
                            }
                            if (seekDeltaMs != 0L) {
                                seekJumpDeltaMs = seekDeltaMs
                                seekJumpSerial += 1
                                seekFeedbackSerial += 1
                                seekFeedback = nextSeekJumpFeedback(
                                    previous = seekFeedback,
                                    deltaMs = seekDeltaMs,
                                    nowElapsedMs = SystemClock.elapsedRealtime(),
                                    nextSerial = seekFeedbackSerial,
                                )
                                markInteraction()
                            } else if (isCenterTap) {
                                viewerState = viewerState.doubleTap()
                                markInteraction()
                            }
                        },
                        onTap = {
                            onAction(ViewerAction.ToggleChrome)
                            interactionSerial += 1
                        },
                        onLongPress = {
                            setInfoSheetVisible(true)
                            markInteraction()
                        },
                    )
                }
                val mediaTransformModifier = Modifier
                    .graphicsLayer {
                        scaleX = viewerState.zoom
                        scaleY = viewerState.zoom
                        translationX = viewerState.panX
                        translationY = viewerState.panY
                    }
                val transformInputModifier = Modifier
                    .transformable(
                        state = transformState,
                        canPan = { viewerState.zoom > ViewerState.FIT_SCALE + 0.01f },
                    )
                val horizontalPageSwipeModifier = Modifier.pointerInput(
                    postPage,
                    mediaPage,
                    postMedia.size,
                    posts.size,
                    viewerState.zoom,
                    invertMultiImageScrollDirection,
                ) {
                    if (viewerState.zoom > ViewerState.FIT_SCALE + 0.01f) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalX = 0f
                        var totalY = 0f
                        var consumingHorizontalSwipe = false
                        val swipeThreshold = maxOf(
                            VIEWER_HORIZONTAL_SWIPE_MIN_DISTANCE_PX,
                            size.width * VIEWER_HORIZONTAL_SWIPE_WIDTH_RATIO,
                        )
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            if (event.changes.size > 1) break
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val delta = change.position - change.previousPosition
                            totalX += delta.x
                            totalY += delta.y
                            val horizontalDistance = abs(totalX)
                            val verticalDistance = abs(totalY)
                            if (
                                horizontalDistance > swipeThreshold * VIEWER_HORIZONTAL_SWIPE_SLOP_FRACTION &&
                                horizontalDistance > verticalDistance * VIEWER_HORIZONTAL_SWIPE_AXIS_RATIO
                            ) {
                                consumingHorizontalSwipe = true
                            }
                            if (consumingHorizontalSwipe) {
                                change.consume()
                            }
                            if (!change.pressed) break
                        }
                        val horizontalDistance = abs(totalX)
                        val verticalDistance = abs(totalY)
                        if (
                            consumingHorizontalSwipe &&
                            horizontalDistance >= swipeThreshold &&
                            horizontalDistance > verticalDistance * VIEWER_HORIZONTAL_SWIPE_AXIS_RATIO
                        ) {
                            val direction = if (totalX < 0f) {
                                ViewerHorizontalSwipeDirection.Next
                            } else {
                                ViewerHorizontalSwipeDirection.Previous
                            }
                            navigateFromHorizontalSwipe(
                                viewerSwipeDirectionForSetting(
                                    rawDirection = direction,
                                    currentMediaCount = postMedia.size,
                                    invertMultiImageScrollDirection = invertMultiImageScrollDirection,
                                )
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    val imageCandidates = remember(post, media, isVideoMedia) {
                        if (isVideoMedia) emptyList() else viewerImageCandidates(post, media)
                    }
                    var displayedCandidateIndex by remember(postPage, mediaPage) { mutableIntStateOf(0) }
                    var maxPreparedCandidateIndex by remember(postPage, mediaPage) { mutableIntStateOf(0) }
                    var imageLoading by remember(postPage, mediaPage) { mutableStateOf(false) }
                    var imageLoadFailed by remember(postPage, mediaPage) { mutableStateOf(false) }
                    var hasVisibleImage by remember(postPage, mediaPage) { mutableStateOf(false) }
                    val activeImageUrl = imageCandidates.getOrNull(displayedCandidateIndex)
                    val imageModel = remember(context, activeImageUrl, post.id.source) {
                        activeImageUrl?.let { buildViewerImageRequest(context, it, post.id.source) }
                    }
                    LaunchedEffect(imageCandidates) {
                        displayedCandidateIndex = 0
                        maxPreparedCandidateIndex = 0
                        imageLoadFailed = false
                    }
                    LaunchedEffect(
                        imageCandidates,
                        displayedCandidateIndex,
                        maxPreparedCandidateIndex,
                        hasVisibleImage,
                        post.id.source,
                        media.progressiveUrls,
                    ) {
                        if (!supportsProgressiveImageUpgrade(post, media)) return@LaunchedEffect
                        if (!hasVisibleImage) return@LaunchedEffect
                        if (displayedCandidateIndex != maxPreparedCandidateIndex) return@LaunchedEffect
                        val nextIndex = displayedCandidateIndex + 1
                        if (nextIndex > imageCandidates.lastIndex) return@LaunchedEffect
                        val nextUrl = imageCandidates[nextIndex]
                        if (loadedMediaUrls[nextUrl] == true) {
                            maxPreparedCandidateIndex = nextIndex
                            displayedCandidateIndex = nextIndex
                            imageLoadFailed = false
                            return@LaunchedEffect
                        }
                        val result = runCatching {
                            context.imageLoader.execute(
                                buildViewerImageRequest(
                                    context = context,
                                    url = nextUrl,
                                    sourceKey = post.id.source,
                                ),
                            )
                        }.getOrNull()
                        if (result is SuccessResult) {
                            loadedMediaUrls[nextUrl] = true
                            maxPreparedCandidateIndex = nextIndex
                            displayedCandidateIndex = nextIndex
                            imageLoadFailed = false
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(mediaContainerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (showUgoira) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(mediaTransformModifier),
                            ) {
                                PixivUgoiraPlayer(
                                    postId = post.id.sourcePostId,
                                    client = requireNotNull(pixivUgoiraClient),
                                    contentDescription = post.title ?: post.id.sourcePostId,
                                    modifier = Modifier.fillMaxSize(),
                                    showProgressBar = chromeVisible && isCurrentMediaPage,
                                    isActive = isCurrentMediaPage,
                                    isPlaying = mediaPlaybackEnabled,
                                    seekJumpSerial = seekJumpSerial,
                                    seekJumpDeltaMs = seekJumpDeltaMs,
                                    playbackRate = playbackRate.speed,
                                    restartRequest = playbackRestartRequest,
                                    onTimelineInteractionActiveChanged = ::onTimelineInteractionChanged,
                                    onTogglePlayback = { onAction(ViewerAction.TogglePlayback) },
                                    onProgressChanged = { positionMs, durationMs ->
                                        onAction(ViewerAction.TimelineProgressChanged(positionMs, durationMs))
                                    },
                                )
                            }
                        } else if (isVideoMedia) {
                            ViewerVideoPlayer(
                                media = media,
                                sourceKey = post.id.source,
                                modifier = Modifier.fillMaxSize(),
                                mediaModifier = mediaTransformModifier,
                                showTimeline = chromeVisible && isCurrentMediaPage,
                                isActive = isCurrentMediaPage,
                                isPlaying = mediaPlaybackEnabled,
                                seekJumpSerial = seekJumpSerial,
                                seekJumpDeltaMs = seekJumpDeltaMs,
                                playbackRate = playbackRate.speed,
                                restartRequest = playbackRestartRequest,
                                onTimelineInteractionActiveChanged = ::onTimelineInteractionChanged,
                                onTogglePlayback = { onAction(ViewerAction.TogglePlayback) },
                                onProgressChanged = { positionMs, durationMs ->
                                    onAction(ViewerAction.TimelineProgressChanged(positionMs, durationMs))
                                },
                            )
                        } else if (isGifMedia && !gifLocation.isNullOrBlank()) {
                            ViewerGifPlayer(
                                sourceKey = post.id.source,
                                location = gifLocation,
                                modifier = Modifier.fillMaxSize(),
                                mediaModifier = mediaTransformModifier,
                                showTimeline = chromeVisible && isCurrentMediaPage,
                                isActive = isCurrentMediaPage,
                                isPlaying = mediaPlaybackEnabled,
                                seekJumpSerial = seekJumpSerial,
                                seekJumpDeltaMs = seekJumpDeltaMs,
                                playbackRate = playbackRate.speed,
                                restartRequest = playbackRestartRequest,
                                onTimelineInteractionActiveChanged = ::onTimelineInteractionChanged,
                                onTogglePlayback = { onAction(ViewerAction.TogglePlayback) },
                                onProgressChanged = { positionMs, durationMs ->
                                    onAction(ViewerAction.TimelineProgressChanged(positionMs, durationMs))
                                },
                            )
                        } else if (isControlledAnimatedWebP && activeImageUrl != null) {
                            ViewerAnimatedWebPPlayer(
                                sourceKey = post.id.source,
                                location = activeImageUrl,
                                contentDescription = post.title ?: post.id.sourcePostId,
                                modifier = Modifier.fillMaxSize(),
                                mediaModifier = mediaTransformModifier,
                                showControls = chromeVisible && isCurrentMediaPage,
                                isActive = isCurrentMediaPage,
                                isPlaying = mediaPlaybackEnabled,
                                restartRequest = playbackRestartRequest,
                                onTogglePlayback = { onAction(ViewerAction.TogglePlayback) },
                                onRestartPlayback = { onAction(ViewerAction.RestartPlayback) },
                                onFrameProgressChanged = { frameIndex, frameCount ->
                                    onAction(ViewerAction.FrameProgressChanged(frameIndex, frameCount))
                                },
                                onLoading = {
                                    imageLoading = true
                                    imageLoadFailed = false
                                },
                                onSuccess = {
                                    imageLoading = false
                                    imageLoadFailed = false
                                    hasVisibleImage = true
                                    loadedMediaUrls[activeImageUrl] = true
                                    if (isCurrentMediaPage) {
                                        onAction(ViewerAction.ClearMediaError)
                                    }
                                },
                                onError = { throwable ->
                                    val recoveryKey =
                                        "${post.id.source.name}:${post.id.sourcePostId}:$mediaPage:$activeImageUrl"
                                    if (
                                        onRequestMediaRecovery != null &&
                                        isHttpNotFound(throwable) &&
                                        mediaRecoveryRequestedKeys.add(recoveryKey)
                                    ) {
                                        onRequestMediaRecovery(post, media.copy(url = activeImageUrl))
                                    }
                                    val canAdvance = displayedCandidateIndex < imageCandidates.lastIndex
                                    if (canAdvance) {
                                        val nextIndex = displayedCandidateIndex + 1
                                        displayedCandidateIndex = nextIndex
                                        maxPreparedCandidateIndex = nextIndex
                                        imageLoading = false
                                        imageLoadFailed = false
                                    } else {
                                        imageLoading = false
                                        imageLoadFailed = !hasVisibleImage
                                        if (!hasVisibleImage && isCurrentMediaPage) {
                                            reportRouteMediaFailure(throwable.message ?: "Could not load media")
                                        }
                                    }
                                },
                            )
                        } else if (imageModel != null) {
                            AsyncImage(
                                model = imageModel,
                                contentDescription = post.title ?: post.id.sourcePostId,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(mediaTransformModifier),
                                contentScale = ContentScale.Fit,
                                onLoading = {
                                    imageLoading = true
                                    imageLoadFailed = false
                                },
                                onSuccess = {
                                    imageLoading = false
                                    imageLoadFailed = false
                                    hasVisibleImage = true
                                    activeImageUrl?.let { loadedMediaUrls[it] = true }
                                    if (isCurrentMediaPage) {
                                        onAction(ViewerAction.ClearMediaError)
                                    }
                                },
                                onError = { state ->
                                    val recoveryKey =
                                        "${post.id.source.name}:${post.id.sourcePostId}:$mediaPage:$activeImageUrl"
                                    if (
                                        activeImageUrl != null &&
                                        onRequestMediaRecovery != null &&
                                        isHttpNotFound(state.result.throwable) &&
                                        mediaRecoveryRequestedKeys.add(recoveryKey)
                                    ) {
                                        onRequestMediaRecovery(post, media.copy(url = activeImageUrl))
                                    }
                                    val canAdvance = displayedCandidateIndex < imageCandidates.lastIndex &&
                                        (!hasVisibleImage || !supportsProgressiveImageUpgrade(post, media))
                                    if (canAdvance) {
                                        val nextIndex = displayedCandidateIndex + 1
                                        displayedCandidateIndex = nextIndex
                                        maxPreparedCandidateIndex = nextIndex
                                        imageLoading = false
                                        imageLoadFailed = false
                                    } else {
                                        imageLoading = false
                                        imageLoadFailed = !hasVisibleImage
                                        if (!hasVisibleImage && isCurrentMediaPage) {
                                            reportRouteMediaFailure(
                                                state.result.throwable.message ?: "Could not load image"
                                            )
                                        }
                                    }
                                },
                            )
                            if (imageLoading && !hasVisibleImage) {
                                CircularProgressIndicator()
                            }
                            if (imageLoadFailed) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = "Could not load image",
                                        color = MaterialTheme.colorScheme.onBackground,
                                    )
                                    TextButton(
                                        onClick = {
                                            onAction(ViewerAction.RetryMedia)
                                        },
                                    ) {
                                        Text("Retry")
                                    }
                                }
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = post.id.source.displayName(),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    text = post.id.sourcePostId,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                post.canonicalTags.firstOrNull()?.let { tag ->
                                    AssistChip(onClick = {}, label = { Text("#$tag") })
                                }
                            }
                        }
                        FittedSeekJumpFeedbackOverlay(
                            feedback = seekFeedback,
                            aspectRatio = mediaAspectRatio,
                            onExpired = { expiredSerial ->
                                if (seekFeedback?.serial == expiredSerial) {
                                    seekFeedback = null
                                }
                            },
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .then(horizontalPageSwipeModifier)
                                    .then(transformInputModifier)
                                    .then(mediaGestureModifier),
                            )
                            if (hasBottomTimeline) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(96.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        if (chromeVisible) {
            ViewerChrome(
                modifier = Modifier.align(Alignment.TopCenter),
                source = selectedPost.id.source.displayName(),
                indexLabel = "${selectedMediaIndex + 1} / ${selectedPostMedia.size}",
                onBack = ::requestDismissViewer,
                liked = selectedPostLiked,
                onToggleLike = {
                    onAction(ViewerAction.ToggleLike)
                    markInteraction()
                },
                actionsMenuExpanded = actionsMenuExpanded,
                onActionsMenuExpandedChange = { expanded ->
                    setActionsMenuExpanded(expanded)
                    if (expanded) {
                        setPlaybackSettingsExpanded(false)
                        markInteraction()
                    }
                },
                playbackSettingsExpanded = playbackSettingsExpanded,
                onPlaybackSettingsExpandedChange = { expanded ->
                    setPlaybackSettingsExpanded(expanded && currentIsSeekableMedia)
                    if (expanded && currentIsSeekableMedia) {
                        setActionsMenuExpanded(false)
                        markInteraction()
                    }
                },
                playbackSettingsEnabled = currentIsSeekableMedia,
                playbackRate = playbackRate,
                onPlaybackRateSelected = { selectedRate ->
                    onAction(ViewerAction.SetPlaybackRate(selectedRate.speed))
                    setPlaybackSettingsExpanded(false)
                    markInteraction()
                },
                mediaOverviewAvailable = mediaOverviewAvailable,
                mediaOverviewVisible = mediaOverviewVisible,
                onToggleMediaOverview = {
                    if (mediaOverviewAvailable) {
                        setOverviewVisible(!mediaOverviewVisible)
                        setActionsMenuExpanded(false)
                        setPlaybackSettingsExpanded(false)
                        markInteraction()
                    }
                },
                invertScrollOptionVisible = selectedPostMedia.size > 1,
                invertMultiImageScrollDirection = invertMultiImageScrollDirection,
                onInvertMultiImageScrollDirectionChange = onInvertMultiImageScrollDirectionChange,
                onDownload = { onAction(ViewerAction.Download) },
                downloadEnabled = canDownloadCurrentMedia,
                onInfo = {
                    setActionsMenuExpanded(false)
                    setInfoSheetVisible(true)
                    markInteraction()
                },
            )
        }
    }

    if (showInfoSheet) {
        val post = selectedPost
        ModalBottomSheet(
            onDismissRequest = { setInfoSheetVisible(false) },
            dragHandle = null,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Info", style = MaterialTheme.typography.titleMedium)
                    Row {
                        IconButton(onClick = {
                            onAction(ViewerAction.Save)
                            setInfoSheetVisible(false)
                        }) {
                            Icon(
                                imageVector = Icons.Default.BookmarkAdd,
                                contentDescription = "Save to Codex",
                            )
                        }
                        IconButton(onClick = {
                            setPlaybackEnabled(false)
                            onGoToSearch()
                            setInfoSheetVisible(false)
                        }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Go to Search",
                            )
                        }
                        IconButton(onClick = {
                            onAction(ViewerAction.Share)
                            setInfoSheetVisible(false)
                        }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                            )
                        }
                        if (!post.pageUrl.isNullOrBlank()) {
                            IconButton(onClick = {
                                onOpenInBrowser(post)
                                setInfoSheetVisible(false)
                            }) {
                                Icon(
                                    imageVector = Icons.Default.OpenInBrowser,
                                    contentDescription = "Open in browser",
                                )
                            }
                        }
                    }
                }

                Text(
                    text = post.title?.takeIf { it.isNotBlank() } ?: post.id.sourcePostId,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "${post.id.source.displayName()} • ${post.id.sourcePostId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onOpenCreatorFallback != null) {
                    CreatorProfileActionButton(
                        post = post,
                        creatorBrowsingSources = creatorBrowsingSources,
                        onOpenProfile = { profile ->
                            setPlaybackEnabled(false)
                            onAction(ViewerAction.OpenCreator(profile))
                            setInfoSheetVisible(false)
                        },
                        onOpenLegacyPost = {
                            setPlaybackEnabled(false)
                            onOpenCreatorFallback(post)
                            setInfoSheetVisible(false)
                        },
                    )
                }

                PostTagActionSection(
                    post = post,
                    tagVideoCountProvider = tagVideoCountProvider,
                    fetchTagVideoCounts = fetchTagVideoCounts,
                    onAddIncludeTerm = { term ->
                        onAction(
                            ViewerAction.IncludeTag(
                                PostTaxonomyTerm(term.value, term.facet, term.sourceNamespace)
                            )
                        )
                        true
                    },
                    onAddExcludeTerm = { term ->
                        onAction(
                            ViewerAction.ExcludeTag(
                                PostTaxonomyTerm(term.value, term.facet, term.sourceNamespace)
                            )
                        )
                        true
                    },
                    onRemoveIncludeTerm = { term -> onRemoveIncludeTerm(post, term) },
                    onRemoveExcludeTerm = { term -> onRemoveExcludeTerm(post, term) },
                    onFavoriteTagLongPress = onFavoriteTagLongPress,
                )

                TextButton(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    onClick = {
                        setPlaybackEnabled(false)
                        onGoToSearch()
                        setInfoSheetVisible(false)
                    },
                ) {
                    Text("Go to Search")
                }
            }
        }
    }

}

@Composable
private fun ViewerMediaOverviewGrid(
    post: Post,
    items: List<ViewerMediaOverviewItem>,
    selectedMediaIndex: Int,
    onMediaSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val selectedOverviewIndex = remember(items, selectedMediaIndex) {
        items.indexOfFirst { item -> item.mediaIndex == selectedMediaIndex }
            .coerceAtLeast(0)
    }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = selectedOverviewIndex,
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 10.dp),
        contentPadding = PaddingValues(top = 64.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = items,
            key = { item -> item.mediaIndex },
        ) { item ->
            val imageModel = remember(context, item.posterLocation, item.kind, post.id.source) {
                item.posterLocation?.let { url ->
                    buildViewerImageRequest(
                        context = context,
                        url = url,
                        sourceKey = post.id.source,
                        staticAnimatedWebPFrame = shouldDecodeStaticOverviewFrame(item.kind),
                    )
                }
            }
            val selected = item.mediaIndex == selectedMediaIndex
            val borderColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .border(width = if (selected) 2.dp else 1.dp, color = borderColor)
                    .background(Color.Black)
                    .clickable { onMediaSelected(item.mediaIndex) },
                contentAlignment = Alignment.Center,
            ) {
                if (imageModel != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = "Media ${item.mediaIndex + 1}: ${item.kind.accessibilityLabel}",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = "Preview unavailable",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text = "${item.mediaIndex + 1}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
                item.kind.badgeLabel?.let { label ->
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.kind == ViewerMediaOverviewKind.VIDEO) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                            )
                        }
                        Text(
                            text = label,
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

internal fun shouldDecodeStaticOverviewFrame(kind: ViewerMediaOverviewKind): Boolean {
    return kind != ViewerMediaOverviewKind.ANIMATED_IMAGE
}

@Composable
private fun FittedSeekJumpFeedbackOverlay(
    feedback: SeekJumpFeedback?,
    aspectRatio: Float?,
    onExpired: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (feedback == null) return

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val fittedModifier = if (aspectRatio != null) {
            val containerRatio = if (maxHeight > 0.dp) {
                maxWidth / maxHeight
            } else {
                aspectRatio
            }
            if (containerRatio > aspectRatio) {
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(aspectRatio)
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
            }
        } else {
            Modifier.fillMaxSize()
        }

        SeekJumpFeedbackOverlay(
            feedback = feedback,
            onExpired = onExpired,
            modifier = fittedModifier.clipToBounds(),
        )
    }
}

@Composable
private fun SeekJumpFeedbackOverlay(
    feedback: SeekJumpFeedback?,
    onExpired: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (feedback == null) return

    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.92f) }
    val direction = feedback.direction
    val label = remember(feedback.totalDeltaMs) {
        formatSeekJumpFeedback(feedback.totalDeltaMs)
    }

    LaunchedEffect(feedback.serial) {
        alpha.snapTo(0f)
        scale.snapTo(0.92f)
        launch {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 90),
            )
        }
        launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 140),
            )
        }
        delay(750L)
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 180),
        )
        onExpired(feedback.serial)
    }

    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha.value
            scaleX = scale.value
            scaleY = scale.value
        },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val horizontalRadius = (maxOf(size.height / 2f, size.width / 2f) * 0.30f)
                .coerceAtLeast(96.dp.toPx())
            val centerX = if (direction == SeekJumpDirection.Backward) {
                0f
            } else {
                size.width
            }
            drawOval(
                color = Color.Black.copy(alpha = 0.50f),
                topLeft = Offset(centerX - horizontalRadius, 0f),
                size = Size(horizontalRadius * 2f, size.height),
            )
        }
        Box(
            modifier = Modifier
                .align(
                    if (direction == SeekJumpDirection.Backward) {
                        Alignment.CenterStart
                    } else {
                        Alignment.CenterEnd
                    },
                )
                .fillMaxHeight()
                .width(112.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ViewerVideoPlayer(
    media: ImageRef,
    sourceKey: SourceKey,
    modifier: Modifier = Modifier,
    mediaModifier: Modifier = Modifier,
    showTimeline: Boolean = false,
    isActive: Boolean = true,
    isPlaying: Boolean? = null,
    seekJumpSerial: Int = 0,
    seekJumpDeltaMs: Long = 0L,
    playbackRate: Float = 1f,
    restartRequest: Long = 0L,
    onTimelineInteractionActiveChanged: (Boolean) -> Unit = {},
    onTogglePlayback: (() -> Unit)? = null,
    onProgressChanged: (Long, Long?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val playbackLocation = remember(media.localPath, media.url, media.mime) {
        resolveViewerVideoPlaybackLocation(context, media)
    }
    if (playbackLocation.isNullOrBlank()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Video unavailable",
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        return
    }
    if (!isActive) {
        Box(modifier = modifier.background(Color.Black)) {}
        return
    }

    var loading by remember(playbackLocation) { mutableStateOf(true) }
    var loadFailed by remember(playbackLocation) { mutableStateOf(false) }
    var playerRef by remember(playbackLocation, sourceKey) { mutableStateOf<ExoPlayer?>(null) }
    var playerViewRef by remember(playbackLocation, sourceKey) { mutableStateOf<PlayerView?>(null) }
    var durationMs by remember(playbackLocation) { mutableLongStateOf(0L) }
    var positionMs by remember(playbackLocation) { mutableLongStateOf(0L) }
    var isScrubbing by remember(playbackLocation) { mutableStateOf(false) }
    var playbackPaused by remember(playbackLocation) { mutableStateOf(false) }
    var lastSeekDispatchAtMs by remember(playbackLocation) { mutableLongStateOf(0L) }
    var lastSeekDispatchTargetMs by remember(playbackLocation) { mutableLongStateOf(0L) }
    val effectivePlaybackRate = playbackRate.coerceAtLeast(MIN_PLAYBACK_RATE)
    val effectivePlaybackPaused = isPlaying?.not() ?: playbackPaused

    DisposableEffect(playbackLocation, sourceKey) {
        loading = true
        loadFailed = false
        durationMs = 0L
        positionMs = 0L
        isScrubbing = false
        playbackPaused = false
        lastSeekDispatchAtMs = 0L
        lastSeekDispatchTargetMs = 0L
        val player = createLoopingExoPlayer(
            context = context,
            location = playbackLocation,
            headers = sourceKey.requestHeaders(),
            muted = false,
        )
        player.playbackParameters = PlaybackParameters(effectivePlaybackRate)
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playerRef !== player) return
                runCatching {
                    loading = playbackState == Player.STATE_IDLE || playbackState == Player.STATE_BUFFERING
                    val duration = player.duration.takeIf { it > 0L }
                    if (duration != null) {
                        durationMs = duration
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (playerRef !== player) return
                loading = false
                loadFailed = true
            }
        }
        player.addListener(listener)
        playerRef = player
        if (isActive && !effectivePlaybackPaused) {
            runCatching {
                player.playWhenReady = true
                player.play()
            }
        }
        onDispose {
            player.removeListener(listener)
            if (playerRef === player) {
                playerRef = null
            }
            runCatching {
                player.playWhenReady = false
                player.pause()
            }
            runCatching {
                playerViewRef?.player = null
            }
            runCatching {
                player.release()
            }
            playerViewRef = null
        }
    }

    LaunchedEffect(playerRef, effectivePlaybackRate) {
        val player = playerRef ?: return@LaunchedEffect
        runCatching {
            player.playbackParameters = PlaybackParameters(effectivePlaybackRate)
        }
    }

    LaunchedEffect(restartRequest, playerRef) {
        if (restartRequest <= 0L) return@LaunchedEffect
        val player = playerRef ?: return@LaunchedEffect
        runCatching {
            player.seekTo(0L)
            positionMs = 0L
            if (isActive && !effectivePlaybackPaused) player.play()
        }
    }

    LaunchedEffect(isActive, playerRef, loadFailed, effectivePlaybackPaused) {
        val player = playerRef ?: return@LaunchedEffect
        runCatching {
            if (!isActive || loadFailed || effectivePlaybackPaused) {
                player.playWhenReady = false
                player.pause()
            } else {
                player.playWhenReady = true
                player.play()
            }
        }
    }

    LaunchedEffect(seekJumpSerial, seekJumpDeltaMs, playerRef, isActive, loadFailed, isScrubbing, durationMs) {
        if (seekJumpSerial <= 0 || seekJumpDeltaMs == 0L) return@LaunchedEffect
        if (!isActive || loadFailed || isScrubbing) return@LaunchedEffect
        val player = playerRef ?: return@LaunchedEffect
        runCatching {
            val current = player.currentPosition.coerceAtLeast(0L)
            val knownDuration = player.duration.takeIf { it > 0L } ?: durationMs.takeIf { it > 0L }
            val target = if (knownDuration != null) {
                (current + seekJumpDeltaMs).coerceIn(0L, knownDuration)
            } else {
                (current + seekJumpDeltaMs).coerceAtLeast(0L)
            }
            player.seekTo(target)
            positionMs = target
        }
    }

    DisposableEffect(lifecycleOwner, playerRef, isActive, loadFailed, effectivePlaybackPaused) {
        val player = playerRef ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    if (isActive && !loadFailed && !effectivePlaybackPaused) {
                        runCatching {
                            player.playWhenReady = true
                            player.play()
                        }
                    }
                }

                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    runCatching {
                        player.playWhenReady = false
                        player.pause()
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(playbackLocation, playerRef, loadFailed, isScrubbing) {
        if (loadFailed) return@LaunchedEffect
        while (true) {
            delay(120L)
            val player = playerRef ?: continue
            val duration = runCatching { player.duration.takeIf { it > 0L } }
                .getOrNull()
            if (duration != null) {
                durationMs = duration
            }
            if (!isScrubbing) {
                val nextPosition = runCatching { player.currentPosition.coerceAtLeast(0L) }
                    .getOrNull()
                    ?: continue
                val maxPosition = durationMs.coerceAtLeast(0L)
                positionMs = if (maxPosition > 0L) {
                    nextPosition.coerceIn(0L, maxPosition)
                } else {
                    nextPosition.coerceAtLeast(0L)
                }
                onProgressChanged(positionMs, durationMs.takeIf { it > 0L })
            }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .then(mediaModifier),
            factory = { factoryContext ->
                createTexturePlayerView(factoryContext).apply {
                    useController = false
                    player = playerRef
                    playerViewRef = this
                }
            },
            update = { playerView ->
                playerViewRef = playerView
                playerView.useController = false
                playerView.player = playerRef
            },
        )

        if (loading && !loadFailed) {
            CircularProgressIndicator()
        }
        if (loadFailed) {
            Text(
                text = "Could not play video",
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (isActive && showTimeline && !loadFailed && durationMs > 0L && playerRef != null) {
            ViewerPlaybackFooter(
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimelinePlaybackButton(
                        isPaused = effectivePlaybackPaused,
                        onToggle = {
                            if (onTogglePlayback != null) {
                                onTogglePlayback()
                            } else {
                                playbackPaused = !playbackPaused
                            }
                            val player = playerRef
                            val pauseAfterToggle = if (onTogglePlayback != null) {
                                !effectivePlaybackPaused
                            } else {
                                playbackPaused
                            }
                            if (pauseAfterToggle) {
                                runCatching {
                                    player?.playWhenReady = false
                                    player?.pause()
                                }
                            } else if (isActive && !loadFailed) {
                                runCatching {
                                    player?.playWhenReady = true
                                    player?.play()
                                }
                            }
                            onTimelineInteractionActiveChanged(true)
                            onTimelineInteractionActiveChanged(false)
                        },
                    )
                    MediaTimelineBar(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        onSeekStarted = {
                            isScrubbing = true
                        },
                        onSeekChanged = { target ->
                            positionMs = target
                            val now = SystemClock.elapsedRealtime()
                            val shouldDispatch =
                                (now - lastSeekDispatchAtMs) >= 90L ||
                                    abs(target - lastSeekDispatchTargetMs) >= 750L
                            if (shouldDispatch) {
                                runCatching {
                                    playerRef?.seekTo(target)
                                }
                                lastSeekDispatchAtMs = now
                                lastSeekDispatchTargetMs = target
                            }
                        },
                        onSeekFinished = { target ->
                            runCatching {
                                playerRef?.seekTo(target)
                            }
                            positionMs = target
                            lastSeekDispatchAtMs = SystemClock.elapsedRealtime()
                            lastSeekDispatchTargetMs = target
                            isScrubbing = false
                        },
                        onInteractionActiveChanged = onTimelineInteractionActiveChanged,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerAnimatedWebPPlayer(
    sourceKey: SourceKey,
    location: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    mediaModifier: Modifier = Modifier,
    showControls: Boolean = true,
    isActive: Boolean = true,
    isPlaying: Boolean? = null,
    restartRequest: Long = 0L,
    onTogglePlayback: (() -> Unit)? = null,
    onRestartPlayback: (() -> Unit)? = null,
    onFrameProgressChanged: (Int, Int) -> Unit = { _, _ -> },
    onLoading: () -> Unit = {},
    onSuccess: () -> Unit = {},
    onError: (Throwable) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val request = remember(context, location, sourceKey) {
        MediaRequestFactory.imageRequest(
            context = context,
            url = location,
            sourceKey = sourceKey,
            crossfade = false,
            controllableAnimatedWebP = true,
        )
    }
    var drawable by remember(location) { mutableStateOf<WebPDrawable?>(null) }
    var paused by remember(location) { mutableStateOf(false) }
    var frameIndex by remember(location) { mutableIntStateOf(0) }
    var frameCount by remember(location) { mutableIntStateOf(0) }
    val effectivePaused = isPlaying?.not() ?: paused

    DisposableEffect(drawable, lifecycleOwner, isActive, effectivePaused) {
        val activeDrawable = drawable
        fun syncPlayback() {
            if (activeDrawable == null) return
            if (isActive && !effectivePaused && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (activeDrawable.isPaused) activeDrawable.resume() else activeDrawable.start()
            } else {
                activeDrawable.pause()
            }
        }
        val observer = LifecycleEventObserver { _, _ -> syncPlayback() }
        lifecycleOwner.lifecycle.addObserver(observer)
        syncPlayback()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activeDrawable?.pause()
        }
    }

    LaunchedEffect(drawable, isActive) {
        val activeDrawable = drawable ?: return@LaunchedEffect
        frameCount = activeDrawable.frameSeqDecoder.frameCount
        while (isActive) {
            frameIndex = activeDrawable.frameSeqDecoder.frameIndex.coerceAtLeast(0)
            onFrameProgressChanged(frameIndex, frameCount)
            delay(100L)
        }
    }

    LaunchedEffect(restartRequest, drawable) {
        if (restartRequest <= 0L) return@LaunchedEffect
        drawable?.reset()
        frameIndex = 0
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize().then(mediaModifier),
            contentScale = ContentScale.Fit,
            onLoading = { onLoading() },
            onSuccess = { state ->
                drawable = state.result.drawable.unwrapWebPDrawable()
                frameCount = drawable?.frameSeqDecoder?.frameCount ?: 0
                onSuccess()
            },
            onError = { state -> onError(state.result.throwable) },
        )
        if (showControls && drawable != null) {
            ViewerPlaybackFooter(modifier = Modifier.align(Alignment.BottomCenter)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimelinePlaybackButton(
                        isPaused = effectivePaused,
                        onToggle = {
                            if (onTogglePlayback != null) onTogglePlayback() else paused = !paused
                        },
                    )
                    IconButton(
                        onClick = {
                            onRestartPlayback?.invoke()
                            drawable?.reset()
                            if (isActive) drawable?.start()
                            frameIndex = 0
                            paused = false
                        },
                    ) {
                        Icon(Icons.Default.Replay, contentDescription = "Restart animation")
                    }
                    LinearProgressIndicator(
                        progress = {
                            if (frameCount <= 1) 0f else {
                                frameIndex.coerceIn(0, frameCount - 1).toFloat() / (frameCount - 1).toFloat()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (frameCount > 0) {
                            "${frameIndex.coerceIn(0, frameCount - 1) + 1}/$frameCount"
                        } else {
                            "—"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private tailrec fun android.graphics.drawable.Drawable.unwrapWebPDrawable(): WebPDrawable? {
    return when (this) {
        is WebPDrawable -> this
        is ScaleDrawable -> child.unwrapWebPDrawable()
        else -> null
    }
}

@Composable
private fun ViewerGifPlayer(
    sourceKey: SourceKey,
    location: String,
    modifier: Modifier = Modifier,
    mediaModifier: Modifier = Modifier,
    showTimeline: Boolean = true,
    isActive: Boolean = true,
    isPlaying: Boolean? = null,
    seekJumpSerial: Int = 0,
    seekJumpDeltaMs: Long = 0L,
    playbackRate: Float = 1f,
    restartRequest: Long = 0L,
    onTimelineInteractionActiveChanged: (Boolean) -> Unit = {},
    onTogglePlayback: (() -> Unit)? = null,
    onProgressChanged: (Long, Long?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    var movie by remember(location) { mutableStateOf<Movie?>(null) }
    var loading by remember(location) { mutableStateOf(true) }
    var loadFailed by remember(location) { mutableStateOf(false) }
    var positionMs by remember(location) { mutableLongStateOf(0L) }
    var isScrubbing by remember(location) { mutableStateOf(false) }
    var playbackPaused by remember(location) { mutableStateOf(false) }
    val effectivePlaybackRate = playbackRate.coerceAtLeast(MIN_PLAYBACK_RATE)
    val effectivePlaybackPaused = isPlaying?.not() ?: playbackPaused

    LaunchedEffect(location, sourceKey) {
        loading = true
        loadFailed = false
        positionMs = 0L
        playbackPaused = false
        movie = loadGifMovie(
            context = context,
            location = location,
            headers = sourceKey.requestHeaders(),
        )
        loading = false
        if (movie == null) {
            loadFailed = true
        }
    }

    val activeMovie = movie
    if (activeMovie == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = "Could not play GIF",
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        return
    }

    val durationMs = remember(activeMovie) {
        activeMovie.duration().takeIf { it > 0 }?.toLong() ?: GIF_FALLBACK_DURATION_MS
    }


    LaunchedEffect(restartRequest) {
        if (restartRequest > 0L) positionMs = 0L
    }

    LaunchedEffect(seekJumpSerial, seekJumpDeltaMs, durationMs, isScrubbing, isActive) {
        if (seekJumpSerial <= 0 || seekJumpDeltaMs == 0L || isScrubbing || !isActive) {
            return@LaunchedEffect
        }
        val target = (positionMs + seekJumpDeltaMs).coerceIn(0L, durationMs)
        positionMs = target
    }

    LaunchedEffect(activeMovie, durationMs, isScrubbing, effectivePlaybackPaused, isActive, effectivePlaybackRate) {
        if (isScrubbing || effectivePlaybackPaused || !isActive) return@LaunchedEffect
        while (true) {
            delay(16L)
            positionMs = if (durationMs <= 0L) {
                0L
            } else {
                val frameDelayMs = 16L
                val next = positionMs + (frameDelayMs * effectivePlaybackRate).toLong().coerceAtLeast(1L)
                if (next >= durationMs) 0L else next
            }
            onProgressChanged(positionMs, durationMs)
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(mediaModifier),
        ) {
            val movieWidth = activeMovie.width().toFloat().coerceAtLeast(1f)
            val movieHeight = activeMovie.height().toFloat().coerceAtLeast(1f)
            val scale = minOf(size.width / movieWidth, size.height / movieHeight)
            val drawWidth = movieWidth * scale
            val drawHeight = movieHeight * scale
            val offsetX = (size.width - drawWidth) / 2f
            val offsetY = (size.height - drawHeight) / 2f

            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                nativeCanvas.save()
                nativeCanvas.translate(offsetX, offsetY)
                nativeCanvas.scale(scale, scale)
                activeMovie.setTime(positionMs.toInt())
                activeMovie.draw(nativeCanvas, 0f, 0f)
                nativeCanvas.restore()
            }
        }

        if (showTimeline) {
            ViewerPlaybackFooter(
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimelinePlaybackButton(
                        isPaused = effectivePlaybackPaused,
                        onToggle = {
                            if (onTogglePlayback != null) {
                                onTogglePlayback()
                            } else {
                                playbackPaused = !playbackPaused
                            }
                            onTimelineInteractionActiveChanged(true)
                            onTimelineInteractionActiveChanged(false)
                        },
                    )
                    MediaTimelineBar(
                        positionMs = positionMs.coerceIn(0L, durationMs),
                        durationMs = durationMs,
                        onSeekStarted = {
                            isScrubbing = true
                        },
                        onSeekChanged = { target ->
                            positionMs = target.coerceIn(0L, durationMs)
                        },
                        onSeekFinished = { target ->
                            positionMs = target.coerceIn(0L, durationMs)
                            isScrubbing = false
                        },
                        onInteractionActiveChanged = onTimelineInteractionActiveChanged,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerPlaybackFooter(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

private suspend fun loadGifMovie(
    context: Context,
    location: String,
    headers: Map<String, String>,
): Movie? = withContext(Dispatchers.IO) {
    val bytes = when {
        location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true) -> {
            val connection = URL(location).openConnection() as? HttpURLConnection ?: return@withContext null
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 12_000
                connection.readTimeout = 18_000
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                val statusCode = connection.responseCode
                if (statusCode !in 200..299) {
                    null
                } else {
                    connection.inputStream.use { input -> input.readBytes() }
                }
            } finally {
                connection.disconnect()
            }
        }

        location.startsWith("content://", ignoreCase = true) -> {
            context.contentResolver.openInputStream(Uri.parse(location))?.use { input ->
                input.readBytes()
            }
        }

        else -> {
            val file = File(location)
            if (file.exists()) file.readBytes() else null
        }
    } ?: return@withContext null

    Movie.decodeByteArray(bytes, 0, bytes.size)
}

private fun buildViewerImageRequest(
    context: Context,
    url: String,
    sourceKey: SourceKey,
    staticAnimatedWebPFrame: Boolean = false,
): ImageRequest {
    return MediaRequestFactory.imageRequest(
        context = context,
        url = url,
        sourceKey = sourceKey,
        crossfade = true,
        staticAnimatedWebPFrame = staticAnimatedWebPFrame,
    )
}

internal data class ViewerHorizontalSwipeTarget(
    val postIndex: Int,
    val mediaIndex: Int,
)

internal enum class ViewerHorizontalSwipeDirection {
    Previous,
    Next,
}

internal fun viewerHorizontalSwipeTarget(
    currentPostIndex: Int,
    currentMediaIndex: Int,
    currentMediaCount: Int,
    postCount: Int,
    targetPostMediaCount: Int,
    direction: ViewerHorizontalSwipeDirection,
): ViewerHorizontalSwipeTarget? {
    if (currentPostIndex !in 0 until postCount) return null
    if (currentMediaCount <= 0) return null

    val safeMediaIndex = currentMediaIndex.coerceIn(0, currentMediaCount - 1)
    return when (direction) {
        ViewerHorizontalSwipeDirection.Previous -> {
            if (safeMediaIndex > 0) {
                ViewerHorizontalSwipeTarget(
                    postIndex = currentPostIndex,
                    mediaIndex = safeMediaIndex - 1,
                )
            } else {
                val previousPostIndex = currentPostIndex - 1
                if (previousPostIndex !in 0 until postCount || targetPostMediaCount <= 0) {
                    null
                } else {
                    ViewerHorizontalSwipeTarget(
                        postIndex = previousPostIndex,
                        mediaIndex = 0,
                    )
                }
            }
        }

        ViewerHorizontalSwipeDirection.Next -> {
            if (safeMediaIndex < currentMediaCount - 1) {
                ViewerHorizontalSwipeTarget(
                    postIndex = currentPostIndex,
                    mediaIndex = safeMediaIndex + 1,
                )
            } else {
                val nextPostIndex = currentPostIndex + 1
                if (nextPostIndex !in 0 until postCount || targetPostMediaCount <= 0) {
                    null
                } else {
                    ViewerHorizontalSwipeTarget(
                        postIndex = nextPostIndex,
                        mediaIndex = 0,
                    )
                }
            }
        }
    }
}

internal fun viewerSwipeDirectionForSetting(
    rawDirection: ViewerHorizontalSwipeDirection,
    currentMediaCount: Int,
    invertMultiImageScrollDirection: Boolean,
): ViewerHorizontalSwipeDirection {
    if (!invertMultiImageScrollDirection || currentMediaCount <= 1) return rawDirection
    return when (rawDirection) {
        ViewerHorizontalSwipeDirection.Previous -> ViewerHorizontalSwipeDirection.Next
        ViewerHorizontalSwipeDirection.Next -> ViewerHorizontalSwipeDirection.Previous
    }
}

internal fun viewerMediaPagerReverseLayout(
    mediaCount: Int,
    invertMultiImageScrollDirection: Boolean,
): Boolean {
    return invertMultiImageScrollDirection && mediaCount > 1
}

private fun viewerMediaItems(post: Post): List<ImageRef> {
    return postMediaItems(post)
}

internal enum class ViewerMediaOverviewKind(
    val badgeLabel: String?,
    val accessibilityLabel: String,
) {
    STILL_IMAGE(badgeLabel = null, accessibilityLabel = "still image"),
    ANIMATED_IMAGE(badgeLabel = "Animated", accessibilityLabel = "animated image"),
    GIF(badgeLabel = "GIF", accessibilityLabel = "GIF"),
    UGOIRA(badgeLabel = "Ugoira", accessibilityLabel = "Ugoira animation"),
    VIDEO(badgeLabel = "Video", accessibilityLabel = "video"),
    UNKNOWN(badgeLabel = "Media", accessibilityLabel = "media"),
}

internal data class ViewerMediaOverviewItem(
    val mediaIndex: Int,
    val media: ImageRef,
    val kind: ViewerMediaOverviewKind,
    val posterLocation: String?,
)

internal fun viewerMediaOverviewItems(post: Post): List<ViewerMediaOverviewItem> {
    return viewerMediaItems(post).mapIndexed { index, media ->
        val kind = viewerMediaOverviewKind(post, media)
        ViewerMediaOverviewItem(
            mediaIndex = index,
            media = media,
            kind = kind,
            posterLocation = viewerMediaOverviewPosterLocation(post, media, kind),
        )
    }
}

internal fun viewerMediaOverviewAvailable(items: List<ViewerMediaOverviewItem>): Boolean {
    return items.size > 1
}

internal fun viewerMediaOverviewKind(post: Post, media: ImageRef): ViewerMediaOverviewKind {
    if (isPixivUgoira(post, media)) return ViewerMediaOverviewKind.UGOIRA
    if (isVideoMediaRef(media)) return ViewerMediaOverviewKind.VIDEO
    if (isGifMediaRef(media)) return ViewerMediaOverviewKind.GIF
    if (isAnimatedImageMediaRef(media)) return ViewerMediaOverviewKind.ANIMATED_IMAGE
    return when (mediaKind(media)) {
        PostMediaKind.IMAGE -> ViewerMediaOverviewKind.STILL_IMAGE
        PostMediaKind.VIDEO -> ViewerMediaOverviewKind.VIDEO
        PostMediaKind.UGOIRA -> ViewerMediaOverviewKind.UGOIRA
        PostMediaKind.UNKNOWN -> ViewerMediaOverviewKind.UNKNOWN
    }
}

internal fun viewerMediaOverviewPosterLocation(
    post: Post,
    media: ImageRef,
    kind: ViewerMediaOverviewKind = viewerMediaOverviewKind(post, media),
): String? {
    val mediaPoster = when (kind) {
        ViewerMediaOverviewKind.STILL_IMAGE -> {
            if (post.id.source == SourceKey.HITOMI) {
                overviewWebPImageLocation(media) ?: viewerPrefetchImageLocation(post, media)
            } else {
                viewerPrefetchImageLocation(post, media)
            }
        }
        ViewerMediaOverviewKind.ANIMATED_IMAGE -> staticOverviewImageLocation(media)
        ViewerMediaOverviewKind.GIF,
        ViewerMediaOverviewKind.UGOIRA,
        ViewerMediaOverviewKind.VIDEO,
        ViewerMediaOverviewKind.UNKNOWN,
        -> null
    }
    if (mediaPoster != null) return mediaPoster
    if (media == post.preview) return null
    return staticOverviewImageLocation(post.preview)
}

private fun staticOverviewImageLocation(ref: ImageRef): String? {
    if (mediaKind(ref) != PostMediaKind.IMAGE || isGifMediaRef(ref)) return null
    val locations = buildList {
        ref.localPath?.takeIf(String::isNotBlank)?.let(::add)
        addAll(ref.progressiveUrls.filter(String::isNotBlank))
        ref.url?.takeIf(String::isNotBlank)?.let(::add)
    }.distinct()
    if (!ref.isAnimated) return locations.firstOrNull()
    return locations.firstOrNull { location -> isOverviewWebPImageLocation(ref, location) }
        ?: locations.firstOrNull(::isStaticOverviewImageLocation)
}

private fun overviewWebPImageLocation(ref: ImageRef): String? {
    val locations = buildList {
        ref.localPath?.takeIf(String::isNotBlank)?.let(::add)
        addAll(ref.progressiveUrls.filter(String::isNotBlank))
        ref.url?.takeIf(String::isNotBlank)?.let(::add)
    }.distinct()
    return locations.firstOrNull { location -> isOverviewWebPImageLocation(ref, location) }
}

private fun isOverviewWebPImageLocation(ref: ImageRef, location: String): Boolean {
    val extension = location
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
    if (extension == "webp") return true
    val normalizedMime = ref.mime?.substringBefore(';')?.trim()?.lowercase()
    return normalizedMime == "image/webp" && (location == ref.localPath || location == ref.url)
}

private fun isStaticOverviewImageLocation(location: String): Boolean {
    val extension = location
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
    return extension in STATIC_OVERVIEW_IMAGE_EXTENSIONS
}

internal fun viewerImageCandidates(post: Post, media: ImageRef): List<String> {
    return progressiveImageCandidates(post, media)
}

internal fun viewerPrefetchImageLocation(post: Post, media: ImageRef): String? {
    return viewerImageCandidates(post, media).firstOrNull()
}

private fun supportsProgressiveImageUpgrade(post: Post, media: ImageRef): Boolean {
    return post.id.source != SourceKey.HITOMI &&
        supportsProgressiveImageCandidates(post, media) &&
        media.progressiveUrls.isNotEmpty()
}

private fun viewerGifLocation(post: Post, media: ImageRef): String? {
    val refs = buildList {
        add(media)
        post.full?.let { add(it) }
        add(post.preview)
    }
    return refs.firstOrNull(::isGifMediaRef)?.let { ref ->
        ref.localPath ?: ref.url
    }
}

private fun isPixivUgoira(post: Post, media: ImageRef): Boolean {
    return isPixivUgoiraMedia(post, media)
}

private val STATIC_OVERVIEW_IMAGE_EXTENSIONS = setOf(
    "avif",
    "bmp",
    "heic",
    "heif",
    "jpeg",
    "jpg",
    "png",
)

private const val VIEWER_PREFETCH_LEFT_COUNT = 3
private const val VIEWER_PREFETCH_RIGHT_COUNT = 3
private const val VIEWER_PAGINATION_PREFETCH_RATIO = 0.8f
private const val VIEWER_HORIZONTAL_SWIPE_MIN_DISTANCE_PX = 48f
private const val VIEWER_HORIZONTAL_SWIPE_WIDTH_RATIO = 0.12f
private const val VIEWER_HORIZONTAL_SWIPE_SLOP_FRACTION = 0.35f
private const val VIEWER_HORIZONTAL_SWIPE_AXIS_RATIO = 1.25f
private const val GIF_FALLBACK_DURATION_MS = 1000L
private const val MIN_PLAYBACK_RATE = 0.1f

private enum class ViewerPlaybackRate(
    val speed: Float,
    val menuLabel: String,
    val contentDescription: String,
) {
    VerySlow(0.2f, "0.2x Very slow", "Playback rate 0.2x very slow"),
    Slow(0.5f, "0.5x Slow", "Playback rate 0.5x slow"),
    Normal(1f, "1x Normal", "Playback rate 1x normal"),
    Fast(1.5f, "1.5x Fast", "Playback rate 1.5x fast"),
    VeryFast(2f, "2x Very fast", "Playback rate 2x very fast"),
}

private fun closestViewerPlaybackRate(rate: Float): ViewerPlaybackRate {
    return ViewerPlaybackRate.entries.minBy { option -> abs(option.speed - rate) }
}

@Composable
private fun ViewerChrome(
    modifier: Modifier = Modifier,
    source: String,
    indexLabel: String,
    onBack: () -> Unit,
    liked: Boolean,
    onToggleLike: (() -> Unit)? = null,
    actionsMenuExpanded: Boolean,
    onActionsMenuExpandedChange: (Boolean) -> Unit,
    playbackSettingsExpanded: Boolean,
    onPlaybackSettingsExpandedChange: (Boolean) -> Unit,
    playbackSettingsEnabled: Boolean,
    playbackRate: ViewerPlaybackRate,
    onPlaybackRateSelected: (ViewerPlaybackRate) -> Unit,
    mediaOverviewAvailable: Boolean,
    mediaOverviewVisible: Boolean,
    onToggleMediaOverview: () -> Unit,
    invertScrollOptionVisible: Boolean,
    invertMultiImageScrollDirection: Boolean,
    onInvertMultiImageScrollDirectionChange: (Boolean) -> Unit,
    downloadEnabled: Boolean,
    onDownload: () -> Unit,
    onInfo: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                if (onToggleLike != null) {
                    IconButton(onClick = onToggleLike) {
                        Icon(
                            imageVector = if (liked) {
                                Icons.Default.Favorite
                            } else {
                                Icons.Outlined.FavoriteBorder
                            },
                            contentDescription = if (liked) {
                                "Unlike post"
                            } else {
                                "Like post"
                            },
                            tint = if (liked) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
            Text(
                text = "$source • $indexLabel",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (mediaOverviewAvailable) {
                    IconButton(onClick = onToggleMediaOverview) {
                        Icon(
                            imageVector = Icons.Default.Collections,
                            contentDescription = if (mediaOverviewVisible) {
                                "Close media overview"
                            } else {
                                "Open media overview"
                            },
                            tint = if (mediaOverviewVisible) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                if (playbackSettingsEnabled) {
                    Box {
                        IconButton(
                            onClick = {
                                onPlaybackSettingsExpandedChange(!playbackSettingsExpanded)
                            },
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Playback settings")
                        }
                        DropdownMenu(
                            expanded = playbackSettingsExpanded,
                            onDismissRequest = { onPlaybackSettingsExpandedChange(false) },
                        ) {
                            ViewerPlaybackRate.entries.forEach { rate ->
                                val selected = rate == playbackRate
                                val selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                                val selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                DropdownMenuItem(
                                    modifier = Modifier.background(
                                        if (selected) selectedContainerColor else Color.Transparent,
                                    ),
                                    text = { Text(rate.menuLabel) },
                                    onClick = { onPlaybackRateSelected(rate) },
                                    colors = MenuDefaults.itemColors(
                                        textColor = if (selected) {
                                            selectedContentColor
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        leadingIconColor = if (selected) {
                                            selectedContentColor
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    ),
                                    leadingIcon = {
                                        if (selected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = rate.contentDescription,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                Box {
                    IconButton(
                        onClick = {
                            onActionsMenuExpandedChange(!actionsMenuExpanded)
                        },
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(
                        expanded = actionsMenuExpanded,
                        onDismissRequest = { onActionsMenuExpandedChange(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Info") },
                            onClick = {
                                onActionsMenuExpandedChange(false)
                                onInfo()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Info, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Download") },
                            onClick = {
                                onActionsMenuExpandedChange(false)
                                onDownload()
                            },
                            enabled = downloadEnabled,
                            leadingIcon = {
                                Icon(Icons.Default.Download, contentDescription = null)
                            },
                        )
                        if (invertScrollOptionVisible) {
                            DropdownMenuItem(
                                text = { Text("Invert scroll direction") },
                                onClick = {
                                    onInvertMultiImageScrollDirectionChange(!invertMultiImageScrollDirection)
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = invertMultiImageScrollDirection,
                                        onCheckedChange = null,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
