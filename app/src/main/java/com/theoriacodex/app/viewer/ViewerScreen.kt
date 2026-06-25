package com.theoriacodex.app.viewer

import android.app.DownloadManager
import android.content.res.Configuration
import android.content.Context
import android.graphics.Movie
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.webkit.URLUtil
import android.widget.Toast
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.theoriacodex.app.creator.CreatorProfileActionButton
import com.theoriacodex.app.media.copyPostUrlToClipboard
import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.isPixivUgoiraMedia
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.app.media.progressiveImageCandidates
import com.theoriacodex.app.media.supportsProgressiveImageCandidates
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(
    posts: List<Post>,
    launchContext: ViewerLaunchContext,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    canLoadMoreFromSource: Boolean = false,
    loadingMoreFromSource: Boolean = false,
    onLoadMoreFromSource: (() -> Unit)? = null,
    likedPostIds: Set<PostId> = emptySet(),
    onToggleLike: ((Post) -> Unit)? = null,
    onRequestPostResolution: ((Post) -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (Post) -> Unit,
    onOpenInBrowser: (Post) -> Unit,
    onAddIncludeTag: (String) -> Unit,
    onAddExcludeTag: (String) -> Unit,
    onRemoveIncludeTag: (String) -> Unit,
    onRemoveExcludeTag: (String) -> Unit,
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)? = null,
    onGoToSearch: () -> Unit,
    onOpenCreatorProfile: ((Post) -> Unit)? = null,
) {
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
    val initialIndex = launchContext.startIndex.coerceIn(0, posts.lastIndex)
    val postPagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { posts.size },
    )
    var viewerState by remember(posts) {
        mutableStateOf(ViewerState(streamSize = posts.size, currentIndex = initialIndex))
    }
    val mediaIndexByPost = remember { mutableStateMapOf<Int, Int>() }
    var showInfoSheet by remember { mutableStateOf(false) }
    var interactionSerial by remember { mutableIntStateOf(0) }
    var timelineInteractionActive by remember { mutableStateOf(false) }
    var lastViewerPaginationRequestSize by remember(posts.size) { mutableIntStateOf(-1) }
    val loadedMediaUrls = remember { mutableStateMapOf<String, Boolean>() }
    val prefetchedVideoUrls = remember { mutableStateMapOf<String, Boolean>() }
    val prefetchInFlightVideoUrls = remember { mutableSetOf<String>() }
    var pendingDismiss by remember { mutableStateOf(false) }
    var mediaPlaybackEnabled by remember { mutableStateOf(true) }
    var playbackRate by remember { mutableStateOf(ViewerPlaybackRate.Normal) }
    var actionsMenuExpanded by remember { mutableStateOf(false) }
    var playbackSettingsExpanded by remember { mutableStateOf(false) }
    var galleryVisible by remember { mutableStateOf(false) }
    val pendingMediaJumpByPost = remember { mutableStateMapOf<Int, Int>() }
    val resolutionRequestedByPostId = remember { mutableStateMapOf<PostId, Boolean>() }
    val currentPostIndex = postPagerState.currentPage.coerceIn(0, posts.lastIndex)
    val selectedPost = posts[currentPostIndex]
    val selectedPostLiked = selectedPost.id in likedPostIds
    val selectedPostMedia = remember(selectedPost) { viewerMediaItems(selectedPost) }
    val selectedMediaIndex = (mediaIndexByPost[currentPostIndex] ?: 0).coerceIn(0, selectedPostMedia.lastIndex)
    val selectedCurrentMedia = selectedPostMedia.getOrNull(selectedMediaIndex)
    val currentIsSeekableMedia = selectedCurrentMedia?.let { media ->
        isVideoMediaRef(media) ||
            isGifMediaRef(media) ||
            (isPixivUgoira(selectedPost, media) && pixivUgoiraClient != null)
    } == true
    val canDownloadCurrentMedia = selectedCurrentMedia?.let { media ->
        (isPixivUgoira(selectedPost, media) && pixivUgoiraClient != null) || !media.url.isNullOrBlank()
    } == true
    val selectedGalleryItems = remember(selectedPost) { viewerGalleryMediaItems(selectedPost) }
    val galleryAvailable = selectedGalleryItems.size > 1 && !currentIsSeekableMedia

    LaunchedEffect(posts, launchContext.queryHash, launchContext.startIndex) {
        pendingDismiss = false
        mediaPlaybackEnabled = true
        playbackRate = ViewerPlaybackRate.Normal
        galleryVisible = false
    }

    LaunchedEffect(currentIsSeekableMedia, viewerState.chromeVisible) {
        if (!currentIsSeekableMedia) {
            playbackSettingsExpanded = false
        }
        if (!viewerState.chromeVisible) {
            actionsMenuExpanded = false
            playbackSettingsExpanded = false
        }
    }

    LaunchedEffect(galleryAvailable) {
        if (!galleryAvailable) {
            galleryVisible = false
        }
    }

    LaunchedEffect(
        selectedPost.id.source,
        selectedPost.id.sourcePostId,
        selectedPost.full?.url,
        selectedPost.full?.localPath,
        onRequestPostResolution,
    ) {
        if (onRequestPostResolution == null) return@LaunchedEffect
        if (!requiresLazyMediaResolution(selectedPost)) return@LaunchedEffect
        if (resolutionRequestedByPostId.put(selectedPost.id, true) == true) return@LaunchedEffect
        onRequestPostResolution(selectedPost)
    }

    LaunchedEffect(pendingDismiss) {
        if (!pendingDismiss) return@LaunchedEffect
        withFrameNanos { }
        onDismiss()
    }

    fun requestDismissViewer() {
        if (pendingDismiss) return
        mediaPlaybackEnabled = false
        showInfoSheet = false
        actionsMenuExpanded = false
        playbackSettingsExpanded = false
        galleryVisible = false
        pendingDismiss = true
    }

    fun markInteraction() {
        interactionSerial += 1
        if (!viewerState.chromeVisible) {
            viewerState = viewerState.copy(chromeVisible = true)
        }
    }

    BackHandler(enabled = !pendingDismiss) {
        if (galleryVisible) {
            galleryVisible = false
            markInteraction()
        } else {
            requestDismissViewer()
        }
    }

    LaunchedEffect(posts, currentPostIndex, selectedMediaIndex) {
        val forwardQueue = buildPrefetchQueue(
            posts = posts,
            currentPostIndex = currentPostIndex,
            currentMediaIndex = selectedMediaIndex,
            limit = VIEWER_PREFETCH_RIGHT_COUNT,
            direction = 1,
        )
        val backwardQueue = buildPrefetchQueue(
            posts = posts,
            currentPostIndex = currentPostIndex,
            currentMediaIndex = selectedMediaIndex,
            limit = VIEWER_PREFETCH_LEFT_COUNT,
            direction = -1,
        )
        val queue = (forwardQueue + backwardQueue).distinctBy { candidate ->
            "${candidate.post.id.source.name}:${candidate.post.id.sourcePostId}:${candidate.media.url ?: candidate.media.localPath}"
        }
        val imageLoader = context.imageLoader
        queue.forEach { candidate ->
            if (isVideoMediaRef(candidate.media)) {
                val videoLocation = candidate.media.localPath ?: candidate.media.url ?: return@forEach
                if (prefetchedVideoUrls[videoLocation] == true) return@forEach
                if (!prefetchInFlightVideoUrls.add(videoLocation)) return@forEach
                val didPrefetch = try {
                    prefetchVideoMedia(
                        context = context,
                        media = candidate.media,
                        headers = viewerRequestHeaders(candidate.post.id.source),
                    )
                } finally {
                    prefetchInFlightVideoUrls.remove(videoLocation)
                }
                if (didPrefetch) {
                    prefetchedVideoUrls[videoLocation] = true
                }
            } else {
                val data = viewerPrefetchImageLocation(candidate.post, candidate.media) ?: return@forEach
                val request = buildViewerImageRequest(
                    context = context,
                    url = data,
                    sourceKey = candidate.post.id.source,
                )
                imageLoader.enqueue(request)
            }
        }
    }

    LaunchedEffect(selectedPost.id.source, selectedPost.id.sourcePostId, selectedMediaIndex) {
        val currentMedia = selectedPostMedia.getOrNull(selectedMediaIndex) ?: return@LaunchedEffect
        if (!isVideoMediaRef(currentMedia)) return@LaunchedEffect
        val location = currentMedia.localPath ?: currentMedia.url ?: return@LaunchedEffect
        if (prefetchedVideoUrls[location] == true) return@LaunchedEffect
        if (!prefetchInFlightVideoUrls.add(location)) return@LaunchedEffect
        val didCache = try {
            prefetchVideoMedia(
                context = context,
                media = currentMedia,
                headers = viewerRequestHeaders(selectedPost.id.source),
            )
        } finally {
            prefetchInFlightVideoUrls.remove(location)
        }
        if (didCache) {
            prefetchedVideoUrls[location] = true
        }
    }

    LaunchedEffect(posts.size, currentPostIndex, canLoadMoreFromSource, loadingMoreFromSource, onLoadMoreFromSource) {
        if (onLoadMoreFromSource == null) return@LaunchedEffect
        if (loadingMoreFromSource || !canLoadMoreFromSource) return@LaunchedEffect
        val triggerIndex = ((posts.lastIndex.coerceAtLeast(0)) * VIEWER_PAGINATION_PREFETCH_RATIO)
            .toInt()
            .coerceAtLeast(0)
        if (currentPostIndex >= triggerIndex && lastViewerPaginationRequestSize != posts.size) {
            lastViewerPaginationRequestSize = posts.size
            onLoadMoreFromSource.invoke()
        }
    }

    fun onTimelineInteractionChanged(isActive: Boolean) {
        timelineInteractionActive = isActive
        if (isActive) {
            markInteraction()
        }
    }

    fun downloadCurrentMedia() {
        val media = selectedCurrentMedia
        val isCurrentUgoira = media?.let { isPixivUgoira(selectedPost, it) } == true
        if (isCurrentUgoira && pixivUgoiraClient != null) {
            scope.launch {
                val result = pixivUgoiraClient.exportToMp4(
                    context = context,
                    postId = selectedPost.id.sourcePostId,
                    title = selectedPost.title,
                )
                val message = result.fold(
                    onSuccess = { "Saved MP4 to device" },
                    onFailure = { error ->
                        "Could not export MP4: ${error.message ?: "unknown error"}"
                    },
                )
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
            markInteraction()
            return
        }

        val didQueueDownload = media?.let {
            enqueueViewerDownload(
                context = context,
                post = selectedPost,
                media = it,
                pageIndex = selectedMediaIndex,
                totalPages = selectedPostMedia.size,
            )
        } ?: false
        Toast.makeText(
            context,
            if (didQueueDownload) "Download started" else "Media unavailable",
            Toast.LENGTH_SHORT,
        ).show()
        markInteraction()
    }

    LaunchedEffect(postPagerState.currentPage) {
        viewerState = viewerState.withIndex(postPagerState.currentPage)
        galleryVisible = false
        markInteraction()
    }

    LaunchedEffect(viewerState.chromeVisible, interactionSerial, timelineInteractionActive, galleryVisible) {
        if (viewerState.chromeVisible && !timelineInteractionActive && !galleryVisible) {
            val serial = interactionSerial
            delay(1500)
            if (serial == interactionSerial && !timelineInteractionActive && !galleryVisible) {
                viewerState = viewerState.hideChrome()
            }
        }
    }

    LaunchedEffect(viewerState.chromeVisible, currentIsSeekableMedia) {
        if (!viewerState.chromeVisible || !currentIsSeekableMedia) {
            timelineInteractionActive = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (galleryVisible && galleryAvailable) {
            ViewerGalleryGrid(
                post = selectedPost,
                items = selectedGalleryItems,
                selectedMediaIndex = selectedMediaIndex,
                onMediaSelected = { mediaIndex ->
                    mediaIndexByPost[currentPostIndex] = mediaIndex
                    pendingMediaJumpByPost[currentPostIndex] = mediaIndex
                    galleryVisible = false
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
            val initialMediaPage = (mediaIndexByPost[postPage] ?: 0).coerceIn(0, postMedia.lastIndex)
            val mediaPagerState = rememberPagerState(
                initialPage = initialMediaPage,
                pageCount = { postMedia.size },
            )
            val pendingMediaJump = pendingMediaJumpByPost[postPage]

            LaunchedEffect(pendingMediaJump, postMedia.size) {
                if (pendingMediaJump == null) return@LaunchedEffect
                val targetPage = pendingMediaJump.coerceIn(0, postMedia.lastIndex)
                mediaPagerState.scrollToPage(targetPage)
                mediaIndexByPost[postPage] = targetPage
                pendingMediaJumpByPost.remove(postPage)
            }

            LaunchedEffect(mediaPagerState.currentPage) {
                mediaIndexByPost[postPage] = mediaPagerState.currentPage
                if (postPage == postPagerState.currentPage) {
                    viewerState = viewerState.withIndex(postPagerState.currentPage)
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
                    mediaIndexByPost[target.postIndex] = target.mediaIndex
                    pendingMediaJumpByPost[target.postIndex] = target.mediaIndex
                    scope.launch {
                        postPagerState.animateScrollToPage(target.postIndex)
                    }
                }
            }

            HorizontalPager(
                state = mediaPagerState,
                userScrollEnabled = false,
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
                val showUgoira = isPixivUgoira(post, media) && pixivUgoiraClient != null
                val isCurrentMediaPage =
                    postPage == postPagerState.currentPage &&
                        mediaPage == mediaPagerState.currentPage
                val isSeekableMedia = isVideoMedia || isGifMedia || showUgoira
                val hasBottomTimeline = viewerState.chromeVisible && (isVideoMedia || isGifMedia || showUgoira)
                var seekJumpSerial by remember(postPage, mediaPage) { mutableIntStateOf(0) }
                var seekJumpDeltaMs by remember(postPage, mediaPage) { mutableLongStateOf(0L) }
                var seekFeedbackSerial by remember(postPage, mediaPage) { mutableIntStateOf(0) }
                var seekFeedback by remember(postPage, mediaPage) { mutableStateOf<SeekJumpFeedback?>(null) }
                val mediaContainerPadding = when {
                    isLandscape -> 0.dp
                    isVideoMedia || isGifMedia || showUgoira -> 0.dp
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
                            viewerState = viewerState.toggleChrome()
                            interactionSerial += 1
                        },
                        onLongPress = {
                            showInfoSheet = true
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
                            navigateFromHorizontalSwipe(direction)
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
                                    showProgressBar = viewerState.chromeVisible && isCurrentMediaPage,
                                    isActive = mediaPlaybackEnabled && isCurrentMediaPage,
                                    seekJumpSerial = seekJumpSerial,
                                    seekJumpDeltaMs = seekJumpDeltaMs,
                                    playbackRate = playbackRate.speed,
                                    onTimelineInteractionActiveChanged = ::onTimelineInteractionChanged,
                                )
                            }
                        } else if (isVideoMedia) {
                            ViewerVideoPlayer(
                                media = media,
                                sourceKey = post.id.source,
                                modifier = Modifier.fillMaxSize(),
                                mediaModifier = mediaTransformModifier,
                                showTimeline = viewerState.chromeVisible && isCurrentMediaPage,
                                isActive = mediaPlaybackEnabled && isCurrentMediaPage,
                                seekJumpSerial = seekJumpSerial,
                                seekJumpDeltaMs = seekJumpDeltaMs,
                                playbackRate = playbackRate.speed,
                                onTimelineInteractionActiveChanged = ::onTimelineInteractionChanged,
                            )
                        } else if (isGifMedia && !gifLocation.isNullOrBlank()) {
                            ViewerGifPlayer(
                                sourceKey = post.id.source,
                                location = gifLocation,
                                modifier = Modifier.fillMaxSize(),
                                mediaModifier = mediaTransformModifier,
                                showTimeline = viewerState.chromeVisible && isCurrentMediaPage,
                                isActive = mediaPlaybackEnabled && isCurrentMediaPage,
                                seekJumpSerial = seekJumpSerial,
                                seekJumpDeltaMs = seekJumpDeltaMs,
                                playbackRate = playbackRate.speed,
                                onTimelineInteractionActiveChanged = ::onTimelineInteractionChanged,
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
                                },
                                onError = {
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
                                            displayedCandidateIndex = 0
                                            maxPreparedCandidateIndex = 0
                                            hasVisibleImage = false
                                            imageLoading = imageCandidates.isNotEmpty()
                                            imageLoadFailed = false
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

        if (viewerState.chromeVisible) {
            ViewerChrome(
                modifier = Modifier.align(Alignment.TopCenter),
                source = selectedPost.id.source.displayName(),
                indexLabel = "${selectedMediaIndex + 1} / ${selectedPostMedia.size}",
                onBack = ::requestDismissViewer,
                liked = selectedPostLiked,
                onToggleLike = onToggleLike?.let { toggle ->
                    {
                        toggle(selectedPost)
                        markInteraction()
                    }
                },
                actionsMenuExpanded = actionsMenuExpanded,
                onActionsMenuExpandedChange = { expanded ->
                    actionsMenuExpanded = expanded
                    if (expanded) {
                        playbackSettingsExpanded = false
                        markInteraction()
                    }
                },
                playbackSettingsExpanded = playbackSettingsExpanded,
                onPlaybackSettingsExpandedChange = { expanded ->
                    playbackSettingsExpanded = expanded && currentIsSeekableMedia
                    if (expanded && currentIsSeekableMedia) {
                        actionsMenuExpanded = false
                        markInteraction()
                    }
                },
                playbackSettingsEnabled = currentIsSeekableMedia,
                playbackRate = playbackRate,
                onPlaybackRateSelected = { selectedRate ->
                    playbackRate = selectedRate
                    playbackSettingsExpanded = false
                    markInteraction()
                },
                galleryAvailable = galleryAvailable,
                galleryVisible = galleryVisible,
                onToggleGallery = {
                    if (galleryAvailable) {
                        galleryVisible = !galleryVisible
                        actionsMenuExpanded = false
                        playbackSettingsExpanded = false
                        markInteraction()
                    }
                },
                onDownload = ::downloadCurrentMedia,
                downloadEnabled = canDownloadCurrentMedia,
                onInfo = {
                    actionsMenuExpanded = false
                    showInfoSheet = true
                    markInteraction()
                },
            )
        }
    }

    if (showInfoSheet) {
        val post = selectedPost
        ModalBottomSheet(
            onDismissRequest = { showInfoSheet = false },
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
                            onSave(post)
                            showInfoSheet = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.BookmarkAdd,
                                contentDescription = "Save to Codex",
                            )
                        }
                        IconButton(onClick = {
                            mediaPlaybackEnabled = false
                            onGoToSearch()
                            showInfoSheet = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Go to Search",
                            )
                        }
                        IconButton(onClick = {
                            val copied = copyPostUrlToClipboard(context, post)
                            val message = if (copied) {
                                "Post URL copied"
                            } else {
                                "No post URL available"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            showInfoSheet = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                            )
                        }
                        if (!post.pageUrl.isNullOrBlank()) {
                            IconButton(onClick = {
                                onOpenInBrowser(post)
                                showInfoSheet = false
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
                if (onOpenCreatorProfile != null) {
                    CreatorProfileActionButton(
                        post = post,
                        onClick = {
                            mediaPlaybackEnabled = false
                            onOpenCreatorProfile(post)
                            showInfoSheet = false
                        },
                    )
                }

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

                TextButton(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    onClick = {
                        mediaPlaybackEnabled = false
                        onGoToSearch()
                        showInfoSheet = false
                    },
                ) {
                    Text("Go to Search")
                }
            }
        }
    }

}

@Composable
private fun ViewerGalleryGrid(
    post: Post,
    items: List<ViewerGalleryMediaItem>,
    selectedMediaIndex: Int,
    onMediaSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val selectedGalleryIndex = remember(items, selectedMediaIndex) {
        items.indexOfFirst { item -> item.mediaIndex == selectedMediaIndex }
            .coerceAtLeast(0)
    }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = selectedGalleryIndex,
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
            val thumbnailUrl = remember(post, item.media) {
                viewerPrefetchImageLocation(post, item.media)
            }
            val imageModel = remember(context, thumbnailUrl, post.id.source) {
                thumbnailUrl?.let { url ->
                    buildViewerImageRequest(
                        context = context,
                        url = url,
                        sourceKey = post.id.source,
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
                        contentDescription = "Page ${item.mediaIndex + 1}",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = "Unavailable",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
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
            }
        }
    }
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
    seekJumpSerial: Int = 0,
    seekJumpDeltaMs: Long = 0L,
    playbackRate: Float = 1f,
    onTimelineInteractionActiveChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val playbackLocation = remember(media.localPath, media.url, media.mime) {
        resolveViewerVideoLocation(context, media)
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
            headers = viewerRequestHeaders(sourceKey),
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
        if (isActive && !playbackPaused) {
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

    LaunchedEffect(isActive, playerRef, loadFailed, playbackPaused) {
        val player = playerRef ?: return@LaunchedEffect
        runCatching {
            if (!isActive || loadFailed || playbackPaused) {
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

    DisposableEffect(lifecycleOwner, playerRef, isActive, loadFailed, playbackPaused) {
        val player = playerRef ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    if (isActive && !loadFailed && !playbackPaused) {
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
                        isPaused = playbackPaused,
                        onToggle = {
                            playbackPaused = !playbackPaused
                            val player = playerRef
                            if (playbackPaused) {
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
private fun ViewerGifPlayer(
    sourceKey: SourceKey,
    location: String,
    modifier: Modifier = Modifier,
    mediaModifier: Modifier = Modifier,
    showTimeline: Boolean = true,
    isActive: Boolean = true,
    seekJumpSerial: Int = 0,
    seekJumpDeltaMs: Long = 0L,
    playbackRate: Float = 1f,
    onTimelineInteractionActiveChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var movie by remember(location) { mutableStateOf<Movie?>(null) }
    var loading by remember(location) { mutableStateOf(true) }
    var loadFailed by remember(location) { mutableStateOf(false) }
    var positionMs by remember(location) { mutableLongStateOf(0L) }
    var isScrubbing by remember(location) { mutableStateOf(false) }
    var playbackPaused by remember(location) { mutableStateOf(false) }
    val effectivePlaybackRate = playbackRate.coerceAtLeast(MIN_PLAYBACK_RATE)

    LaunchedEffect(location, sourceKey) {
        loading = true
        loadFailed = false
        positionMs = 0L
        playbackPaused = false
        movie = loadGifMovie(
            context = context,
            location = location,
            headers = viewerRequestHeaders(sourceKey),
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

    LaunchedEffect(seekJumpSerial, seekJumpDeltaMs, durationMs, isScrubbing, isActive) {
        if (seekJumpSerial <= 0 || seekJumpDeltaMs == 0L || isScrubbing || !isActive) {
            return@LaunchedEffect
        }
        val target = (positionMs + seekJumpDeltaMs).coerceIn(0L, durationMs)
        positionMs = target
    }

    LaunchedEffect(activeMovie, durationMs, isScrubbing, playbackPaused, isActive, effectivePlaybackRate) {
        if (isScrubbing || playbackPaused || !isActive) return@LaunchedEffect
        while (true) {
            delay(16L)
            positionMs = if (durationMs <= 0L) {
                0L
            } else {
                val frameDelayMs = 16L
                val next = positionMs + (frameDelayMs * effectivePlaybackRate).toLong().coerceAtLeast(1L)
                if (next >= durationMs) 0L else next
            }
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
                        isPaused = playbackPaused,
                        onToggle = {
                            playbackPaused = !playbackPaused
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

private suspend fun prefetchVideoMedia(
    context: Context,
    media: ImageRef,
    headers: Map<String, String>,
): Boolean = withContext(Dispatchers.IO) {
    val location = media.localPath ?: media.url ?: return@withContext false
    when {
        location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true) -> {
            val output = viewerVideoCacheFile(context, location, media.mime)
            if (output.exists() && output.length() > 0L) {
                output.setLastModified(System.currentTimeMillis())
                trimViewerVideoCache(context)
                return@withContext true
            }
            val connection = URL(location).openConnection() as? HttpURLConnection ?: return@withContext false
            val temp = File(output.absolutePath + ".part")
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 12_000
                connection.readTimeout = 24_000
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                val statusCode = connection.responseCode
                if (statusCode !in 200..299) {
                    false
                } else {
                    connection.inputStream.use { input ->
                        output.parentFile?.mkdirs()
                        temp.outputStream().use { out ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count <= 0) break
                                out.write(buffer, 0, count)
                            }
                        }
                    }
                    if (temp.length() <= 0L) {
                        temp.delete()
                        false
                    } else {
                        if (output.exists()) {
                            output.delete()
                        }
                        val renamed = temp.renameTo(output)
                        if (renamed) {
                            trimViewerVideoCache(context)
                        }
                        renamed
                    }
                }
            } finally {
                connection.disconnect()
                if (temp.exists() && (!output.exists() || output.length() == 0L)) {
                    temp.delete()
                }
            }
        }

        location.startsWith("content://", ignoreCase = true) -> true
        else -> File(location).exists()
    }
}

private fun trimViewerVideoCache(context: Context) {
    val directory = File(context.cacheDir, "theoria_codex/viewer/videos")
    val files = directory.listFiles()
        ?.filter { it.isFile && !it.name.endsWith(".part") }
        ?.toMutableList()
        ?: return
    var totalBytes = files.sumOf { it.length() }
    var fileCount = files.size
    if (fileCount <= VIEWER_VIDEO_CACHE_MAX_FILES && totalBytes <= VIEWER_VIDEO_CACHE_MAX_BYTES) {
        return
    }
    files.sortBy { it.lastModified() }
    for (file in files) {
        if (fileCount <= VIEWER_VIDEO_CACHE_MAX_FILES && totalBytes <= VIEWER_VIDEO_CACHE_MAX_BYTES) {
            break
        }
        val bytes = file.length()
        if (file.delete()) {
            fileCount -= 1
            totalBytes = (totalBytes - bytes).coerceAtLeast(0L)
        }
    }
}

private fun resolveViewerVideoLocation(context: Context, media: ImageRef): String? {
    val local = media.localPath?.takeIf { it.isNotBlank() }
    if (local != null) return local

    val remoteUrl = media.url?.takeIf { it.isNotBlank() } ?: return null
    if (!remoteUrl.startsWith("http://", ignoreCase = true) &&
        !remoteUrl.startsWith("https://", ignoreCase = true)
    ) {
        return remoteUrl
    }

    val cached = viewerVideoCacheFile(context, remoteUrl, media.mime)
    return if (cached.exists() && cached.length() > 0L) cached.absolutePath else remoteUrl
}

private fun viewerVideoCacheFile(context: Context, remoteUrl: String, mime: String?): File {
    val guessedName = URLUtil.guessFileName(remoteUrl, null, mime)
    val extension = guessedName.substringAfterLast('.', "").ifBlank { "bin" }
    val key = sha256(remoteUrl)
    val directory = File(context.cacheDir, "theoria_codex/viewer/videos")
    return File(directory, "$key.$extension")
}

private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            append(((byte.toInt() ushr 4) and 0xF).toString(16))
            append((byte.toInt() and 0xF).toString(16))
        }
    }
}

private fun buildViewerImageRequest(
    context: Context,
    url: String,
    sourceKey: SourceKey,
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(url)
        .crossfade(true)
        .allowHardware(false)
    viewerRequestHeaders(sourceKey).forEach { (name, value) ->
        builder.addHeader(name, value)
    }
    return builder.build()
}

private fun viewerRequestHeaders(sourceKey: SourceKey): Map<String, String> {
    return sourceKey.requestHeaders()
}

private data class PrefetchCandidate(
    val post: Post,
    val media: ImageRef,
)

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

private fun buildPrefetchQueue(
    posts: List<Post>,
    currentPostIndex: Int,
    currentMediaIndex: Int,
    limit: Int,
    direction: Int,
): List<PrefetchCandidate> {
    if (posts.isEmpty() || limit <= 0 || direction == 0) return emptyList()

    val queue = mutableListOf<PrefetchCandidate>()
    var postIndex = currentPostIndex
    var mediaIndex = if (direction > 0) currentMediaIndex + 1 else currentMediaIndex - 1

    while (postIndex in posts.indices && queue.size < limit) {
        val post = posts[postIndex]
        val mediaItems = viewerMediaItems(post)
        if (direction > 0) {
            while (mediaIndex <= mediaItems.lastIndex && queue.size < limit) {
                val media = mediaItems[mediaIndex]
                if (!isPixivUgoira(post, media)) {
                    queue += PrefetchCandidate(post = post, media = media)
                }
                mediaIndex += 1
            }
            postIndex += 1
            mediaIndex = 0
        } else {
            while (mediaIndex >= 0 && queue.size < limit) {
                val media = mediaItems[mediaIndex]
                if (!isPixivUgoira(post, media)) {
                    queue += PrefetchCandidate(post = post, media = media)
                }
                mediaIndex -= 1
            }
            postIndex -= 1
            if (postIndex !in posts.indices) break
            mediaIndex = viewerMediaItems(posts[postIndex]).lastIndex
        }
    }

    return queue
}

private fun viewerMediaItems(post: Post): List<ImageRef> {
    return postMediaItems(post)
}

internal data class ViewerGalleryMediaItem(
    val mediaIndex: Int,
    val media: ImageRef,
)

internal fun viewerGalleryMediaItems(post: Post): List<ViewerGalleryMediaItem> {
    return viewerMediaItems(post).mapIndexedNotNull { index, media ->
        if (isVideoMediaRef(media) || isGifMediaRef(media) || isPixivUgoira(post, media)) {
            null
        } else if (viewerImageCandidates(post, media).isEmpty()) {
            null
        } else {
            ViewerGalleryMediaItem(mediaIndex = index, media = media)
        }
    }
}

internal fun viewerImageCandidates(post: Post, media: ImageRef): List<String> {
    return progressiveImageCandidates(post, media)
}

internal fun viewerPrefetchImageLocation(post: Post, media: ImageRef): String? {
    return viewerImageCandidates(post, media).firstOrNull()
}

private fun supportsProgressiveImageUpgrade(post: Post, media: ImageRef): Boolean {
    return supportsProgressiveImageCandidates(post, media) && media.progressiveUrls.isNotEmpty()
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

private fun enqueueViewerDownload(
    context: Context,
    post: Post,
    media: ImageRef,
    pageIndex: Int,
    totalPages: Int,
): Boolean {
    val url = media.url ?: return false
    val request = DownloadManager.Request(Uri.parse(url))
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setMimeType(media.mime)
    viewerRequestHeaders(post.id.source).forEach { (name, value) ->
        request.addRequestHeader(name, value)
    }

    val fileName = buildDownloadFileName(post, media, pageIndex, totalPages, url)
    request.setTitle(fileName)
    request.setDescription(post.pageUrl ?: "Saved from Theoria Codex")
    runCatching {
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            "TheoriaCodex/$fileName",
        )
    }.onFailure {
        request.setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            fileName,
        )
    }

    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
    return runCatching {
        manager.enqueue(request)
        true
    }.getOrElse { false }
}

private fun buildDownloadFileName(
    post: Post,
    media: ImageRef,
    pageIndex: Int,
    totalPages: Int,
    fallbackUrl: String,
): String {
    val guessedName = URLUtil.guessFileName(fallbackUrl, null, media.mime)
    val extension = guessedName.substringAfterLast('.', "")
    val base = post.title
        ?.sanitizeFileName()
        ?.takeIf { it.isNotBlank() }
        ?: "${post.id.source.name.lowercase()}_${post.id.sourcePostId}"
    val pageSuffix = if (totalPages > 1) "_p${pageIndex + 1}" else ""
    return if (extension.isNotBlank()) {
        "${base}$pageSuffix.$extension"
    } else {
        "$base$pageSuffix"
    }
}

private fun String.sanitizeFileName(): String {
    val cleaned = trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
    return cleaned.ifBlank { "image" }
}

private const val VIEWER_PREFETCH_LEFT_COUNT = 3
private const val VIEWER_PREFETCH_RIGHT_COUNT = 3
private const val VIEWER_PAGINATION_PREFETCH_RATIO = 0.8f
private const val VIEWER_HORIZONTAL_SWIPE_MIN_DISTANCE_PX = 48f
private const val VIEWER_HORIZONTAL_SWIPE_WIDTH_RATIO = 0.12f
private const val VIEWER_HORIZONTAL_SWIPE_SLOP_FRACTION = 0.35f
private const val VIEWER_HORIZONTAL_SWIPE_AXIS_RATIO = 1.25f
private const val GIF_FALLBACK_DURATION_MS = 1000L
private const val VIEWER_VIDEO_CACHE_MAX_FILES = 80
private const val VIEWER_VIDEO_CACHE_MAX_BYTES = 750L * 1024L * 1024L
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
    galleryAvailable: Boolean,
    galleryVisible: Boolean,
    onToggleGallery: () -> Unit,
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
                if (galleryAvailable) {
                    IconButton(onClick = onToggleGallery) {
                        Icon(
                            imageVector = Icons.Default.Collections,
                            contentDescription = if (galleryVisible) "Close gallery" else "Open gallery",
                            tint = if (galleryVisible) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                } else {
                    Box {
                        IconButton(
                            onClick = {
                                onPlaybackSettingsExpandedChange(!playbackSettingsExpanded)
                            },
                            enabled = playbackSettingsEnabled,
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
                    }
                }
            }
        }
    }
}
