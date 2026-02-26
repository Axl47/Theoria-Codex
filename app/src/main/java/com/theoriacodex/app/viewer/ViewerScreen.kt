package com.theoriacodex.app.viewer

import android.app.DownloadManager
import android.content.res.Configuration
import android.content.Context
import android.graphics.Movie
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.URLUtil
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.isPixivUgoiraMedia
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(
    posts: List<Post>,
    launchContext: ViewerLaunchContext,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCount: suspend (SourceKey, String) -> Int? = { _, _ -> null },
    canLoadMoreFromSource: Boolean = false,
    loadingMoreFromSource: Boolean = false,
    onLoadMoreFromSource: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (Post) -> Unit,
    onOpenInBrowser: (Post) -> Unit,
    onAddIncludeTag: (String) -> Unit,
    onAddExcludeTag: (String) -> Unit,
    onRemoveIncludeTag: (String) -> Unit,
    onRemoveExcludeTag: (String) -> Unit,
    onGoToSearch: () -> Unit,
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
    var showMediaActionsSheet by remember { mutableStateOf(false) }
    var interactionSerial by remember { mutableIntStateOf(0) }
    var lastViewerPaginationRequestSize by remember(posts.size) { mutableIntStateOf(-1) }
    val loadedMediaUrls = remember { mutableStateMapOf<String, Boolean>() }
    val prefetchedVideoUrls = remember { mutableStateMapOf<String, Boolean>() }
    var pendingDismiss by remember { mutableStateOf(false) }
    var mediaPlaybackEnabled by remember { mutableStateOf(true) }
    val currentPostIndex = postPagerState.currentPage.coerceIn(0, posts.lastIndex)
    val selectedPost = posts[currentPostIndex]
    val selectedPostMedia = remember(selectedPost) { viewerMediaItems(selectedPost) }
    val selectedMediaIndex = (mediaIndexByPost[currentPostIndex] ?: 0).coerceIn(0, selectedPostMedia.lastIndex)

    LaunchedEffect(posts, launchContext.queryHash, launchContext.startIndex) {
        pendingDismiss = false
        mediaPlaybackEnabled = true
    }

    LaunchedEffect(pendingDismiss) {
        if (!pendingDismiss) return@LaunchedEffect
        delay(VIEWER_DISMISS_DELAY_MS)
        onDismiss()
    }

    fun requestDismissViewer() {
        if (pendingDismiss) return
        mediaPlaybackEnabled = false
        showInfoSheet = false
        showMediaActionsSheet = false
        pendingDismiss = true
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
            val data = candidate.media.localPath
                ?: candidate.media.url
                ?: candidate.post.full?.localPath
                ?: candidate.post.full?.url
                ?: candidate.post.preview.localPath
                ?: candidate.post.preview.url
                ?: return@forEach
            if (isVideoMediaRef(candidate.media)) {
                val videoLocation = candidate.media.localPath ?: candidate.media.url ?: return@forEach
                if (prefetchedVideoUrls[videoLocation] == true) return@forEach
                val didPrefetch = prefetchVideoMedia(
                    context = context,
                    media = candidate.media,
                    headers = viewerRequestHeaders(candidate.post.id.source),
                )
                if (didPrefetch) {
                    prefetchedVideoUrls[videoLocation] = true
                }
            } else {
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
        val didCache = prefetchVideoMedia(
            context = context,
            media = currentMedia,
            headers = viewerRequestHeaders(selectedPost.id.source),
        )
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

    fun markInteraction() {
        interactionSerial += 1
        if (!viewerState.chromeVisible) {
            viewerState = viewerState.copy(chromeVisible = true)
        }
    }

    LaunchedEffect(postPagerState.currentPage) {
        viewerState = viewerState.withIndex(postPagerState.currentPage)
        markInteraction()
    }

    LaunchedEffect(viewerState.chromeVisible, interactionSerial) {
        if (viewerState.chromeVisible) {
            val serial = interactionSerial
            delay(1500)
            if (serial == interactionSerial) {
                viewerState = viewerState.hideChrome()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        HorizontalPager(
            state = postPagerState,
            userScrollEnabled = viewerState.zoom <= ViewerState.FIT_SCALE + 0.01f,
            modifier = Modifier.fillMaxSize(),
        ) { postPage ->
            val post = posts[postPage]
            val postMedia = remember(post) { viewerMediaItems(post) }
            val initialMediaPage = (mediaIndexByPost[postPage] ?: 0).coerceIn(0, postMedia.lastIndex)
            val mediaPagerState = rememberPagerState(
                initialPage = initialMediaPage,
                pageCount = { postMedia.size },
            )

            LaunchedEffect(mediaPagerState.currentPage) {
                mediaIndexByPost[postPage] = mediaPagerState.currentPage
                if (postPage == postPagerState.currentPage) {
                    viewerState = viewerState.withIndex(postPagerState.currentPage)
                    markInteraction()
                }
            }

            VerticalPager(
                state = mediaPagerState,
                userScrollEnabled = viewerState.zoom <= ViewerState.FIT_SCALE + 0.01f,
                modifier = Modifier.fillMaxSize(),
                contentPadding = if (isLandscape) {
                    PaddingValues(0.dp)
                } else {
                    PaddingValues(vertical = 40.dp)
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
                val gifLocation = remember(post, media) { viewerGifLocation(post, media) }
                val mediaGestureModifier = Modifier.pointerInput(postPage, mediaPage) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (!isVideoMedia) {
                                viewerState = viewerState.doubleTap()
                            }
                            markInteraction()
                        },
                        onTap = {
                            viewerState = viewerState.toggleChrome()
                            interactionSerial += 1
                        },
                        onLongPress = {
                            showMediaActionsSheet = true
                            markInteraction()
                        },
                    )
                }
                val transformModifier = if (isVideoMedia) {
                    Modifier
                } else {
                    Modifier
                        .graphicsLayer {
                            scaleX = viewerState.zoom
                            scaleY = viewerState.zoom
                            translationX = viewerState.panX
                            translationY = viewerState.panY
                        }
                        .transformable(
                            state = transformState,
                            canPan = { viewerState.zoom > ViewerState.FIT_SCALE + 0.01f },
                        )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(transformModifier)
                        .then(mediaGestureModifier)
                ) {
                    val imageCandidates = remember(post, media, isVideoMedia) {
                        if (isVideoMedia) emptyList() else viewerImageCandidates(post, media)
                    }
                    var imageCandidateIndex by remember(postPage, mediaPage) { mutableIntStateOf(0) }
                    var imageLoading by remember(postPage, mediaPage) { mutableStateOf(false) }
                    var imageLoadFailed by remember(postPage, mediaPage) { mutableStateOf(false) }
                    val activeImageUrl = imageCandidates.getOrNull(imageCandidateIndex)
                    val imageModel = remember(context, activeImageUrl, post.id.source) {
                        activeImageUrl?.let { buildViewerImageRequest(context, it, post.id.source) }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isLandscape) 0.dp else 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val showUgoira = isPixivUgoira(post, media) && pixivUgoiraClient != null
                        if (showUgoira) {
                            PixivUgoiraPlayer(
                                postId = post.id.sourcePostId,
                                client = requireNotNull(pixivUgoiraClient),
                                contentDescription = post.title ?: post.id.sourcePostId,
                                modifier = Modifier.fillMaxSize(),
                                showProgressBar = true,
                            )
                        } else if (isVideoMedia) {
                            ViewerVideoPlayer(
                                media = media,
                                sourceKey = post.id.source,
                                modifier = Modifier.fillMaxSize(),
                                showTimeline = true,
                                isActive = mediaPlaybackEnabled,
                            )
                        } else if (isGifMedia && !gifLocation.isNullOrBlank()) {
                            ViewerGifPlayer(
                                sourceKey = post.id.source,
                                location = gifLocation,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else if (imageModel != null) {
                            AsyncImage(
                                model = imageModel,
                                contentDescription = post.title ?: post.id.sourcePostId,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                                onLoading = {
                                    imageLoading = true
                                    imageLoadFailed = false
                                },
                                onSuccess = {
                                    imageLoading = false
                                    imageLoadFailed = false
                                    activeImageUrl?.let { loadedMediaUrls[it] = true }
                                },
                                onError = {
                                    if (imageCandidateIndex < imageCandidates.lastIndex) {
                                        imageCandidateIndex += 1
                                        imageLoading = false
                                        imageLoadFailed = false
                                    } else {
                                        imageLoading = false
                                        imageLoadFailed = true
                                    }
                                },
                            )
                            val alreadyLoaded = activeImageUrl?.let { loadedMediaUrls[it] == true } == true
                            if (imageLoading && !alreadyLoaded) {
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
                                            imageCandidateIndex = 0
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
                                    text = post.id.source.name,
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
                    }
                }
            }
        }

        if (viewerState.chromeVisible) {
            ViewerChrome(
                modifier = Modifier.align(Alignment.TopCenter),
                source = selectedPost.id.source.name,
                indexLabel = "${selectedMediaIndex + 1} / ${selectedPostMedia.size}",
                onBack = ::requestDismissViewer,
                onSave = {
                    onSave(selectedPost)
                    markInteraction()
                },
                onInfo = {
                    showInfoSheet = true
                    markInteraction()
                },
            )
        }
    }

    if (showInfoSheet) {
        val post = selectedPost
        var tagSelections by remember(post.id.source, post.id.sourcePostId) {
            mutableStateOf<Map<String, ViewerTagSelection>>(emptyMap())
        }
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

                Text("Tags", style = MaterialTheme.typography.titleSmall)
                val distinctTags = remember(post.canonicalTags) { post.canonicalTags.distinct() }
                var tagVideoCounts by remember(post.id.source, distinctTags) {
                    mutableStateOf(
                        distinctTags.associateWith { tag ->
                            tagVideoCountProvider(post.id.source, tag)
                        }
                    )
                }
                LaunchedEffect(post.id.source, distinctTags) {
                    distinctTags.forEach { tag ->
                        if (tagVideoCounts[tag] != null) return@forEach
                        val count = fetchTagVideoCount(post.id.source, tag)
                        if (count != null) {
                            tagVideoCounts = tagVideoCounts + (tag to count)
                        }
                    }
                }
                ViewerTagSelectionGrid(
                    tags = distinctTags,
                    videoCounts = tagVideoCounts,
                    selections = tagSelections,
                    onIncludeTag = { tag ->
                        when (tagSelections[tag]) {
                            ViewerTagSelection.INCLUDE -> {
                                onRemoveIncludeTag(tag)
                                tagSelections = tagSelections - tag
                            }
                            ViewerTagSelection.EXCLUDE -> {
                                onRemoveExcludeTag(tag)
                                onAddIncludeTag(tag)
                                tagSelections = tagSelections + (tag to ViewerTagSelection.INCLUDE)
                            }
                            null -> {
                                onAddIncludeTag(tag)
                                tagSelections = tagSelections + (tag to ViewerTagSelection.INCLUDE)
                            }
                        }
                    },
                    onExcludeTag = { tag ->
                        when (tagSelections[tag]) {
                            ViewerTagSelection.EXCLUDE -> {
                                onRemoveExcludeTag(tag)
                                tagSelections = tagSelections - tag
                            }
                            ViewerTagSelection.INCLUDE -> {
                                onRemoveIncludeTag(tag)
                                onAddExcludeTag(tag)
                                tagSelections = tagSelections + (tag to ViewerTagSelection.EXCLUDE)
                            }
                            null -> {
                                onAddExcludeTag(tag)
                                tagSelections = tagSelections + (tag to ViewerTagSelection.EXCLUDE)
                            }
                        }
                    },
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

    if (showMediaActionsSheet) {
        val currentMedia = selectedPostMedia.getOrNull(selectedMediaIndex)
        ModalBottomSheet(
            onDismissRequest = { showMediaActionsSheet = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Media actions", style = MaterialTheme.typography.titleMedium)
                val isCurrentUgoira = currentMedia?.let { media ->
                    isPixivUgoira(selectedPost, media)
                } == true
                val isCurrentVideo = currentMedia?.let(::isVideoMediaRef) == true
                TextButton(
                    onClick = {
                        showMediaActionsSheet = false
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
                        } else {
                            val didQueueDownload = currentMedia?.let { media ->
                                enqueueViewerDownload(
                                    context = context,
                                    post = selectedPost,
                                    media = media,
                                    pageIndex = selectedMediaIndex,
                                    totalPages = selectedPostMedia.size,
                                )
                            } ?: false
                            Toast.makeText(
                                context,
                                if (didQueueDownload) "Download started" else "Media unavailable",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    enabled = (isCurrentUgoira && pixivUgoiraClient != null) || currentMedia?.url != null,
                ) {
                    Text(
                        when {
                            isCurrentUgoira -> "Download MP4"
                            isCurrentVideo -> "Download video"
                            else -> "Download image"
                        }
                    )
                }
            }
        }
    }
}

private enum class ViewerTagSelection {
    INCLUDE,
    EXCLUDE,
}

@Composable
private fun ViewerTagSelectionGrid(
    tags: List<String>,
    videoCounts: Map<String, Int?>,
    selections: Map<String, ViewerTagSelection>,
    onIncludeTag: (String) -> Unit,
    onExcludeTag: (String) -> Unit,
) {
    if (tags.isEmpty()) {
        Text(
            text = "No tags",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    tags.chunked(3).forEach { rowTags ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            rowTags.forEach { tag ->
                ViewerTagActionCell(
                    tag = tag,
                    videoCount = videoCounts[tag],
                    selection = selections[tag],
                    onInclude = { onIncludeTag(tag) },
                    onExclude = { onExcludeTag(tag) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(3 - rowTags.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ViewerTagActionCell(
    tag: String,
    videoCount: Int?,
    selection: ViewerTagSelection?,
    onInclude: () -> Unit,
    onExclude: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(999.dp),
            color = if (selection == null) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            } else {
                accent.copy(alpha = 0.16f)
            },
        ) {
            Text(
                text = tag,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (videoCount != null) {
            Text(
                text = videoCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ViewerTagActionPill(
                label = "+",
                selected = selection == ViewerTagSelection.INCLUDE,
                onClick = onInclude,
                modifier = Modifier.weight(1f),
            )
            ViewerTagActionPill(
                label = "-",
                selected = selection == ViewerTagSelection.EXCLUDE,
                onClick = onExclude,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ViewerTagActionPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            accent.copy(alpha = 0.24f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ViewerVideoPlayer(
    media: ImageRef,
    sourceKey: SourceKey,
    modifier: Modifier = Modifier,
    showTimeline: Boolean = false,
    isActive: Boolean = true,
) {
    val context = LocalContext.current
    val location = resolveViewerVideoLocation(context, media)
    if (location.isNullOrBlank()) {
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

    var loading by remember(location) { mutableStateOf(true) }
    var loadFailed by remember(location) { mutableStateOf(false) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }
    var durationMs by remember(location) { mutableLongStateOf(0L) }
    var positionMs by remember(location) { mutableLongStateOf(0L) }
    var isScrubbing by remember(location) { mutableStateOf(false) }

    DisposableEffect(location) {
        onDispose {
            videoViewRef?.stopPlayback()
            videoViewRef = null
            mediaPlayerRef = null
        }
    }

    LaunchedEffect(isActive, videoViewRef) {
        val videoView = videoViewRef ?: return@LaunchedEffect
        if (!isActive) {
            runCatching { videoView.pause() }
            runCatching { videoView.stopPlayback() }
        } else if (!loading && !loadFailed) {
            runCatching { videoView.start() }
        }
    }

    LaunchedEffect(location, videoViewRef, loadFailed, isScrubbing) {
        if (loadFailed) return@LaunchedEffect
        while (true) {
            delay(120L)
            val videoView = videoViewRef ?: continue
            val duration = videoView.duration.takeIf { it > 0 }?.toLong()
            if (duration != null) {
                durationMs = duration
            }
            if (!isScrubbing) {
                val nextPosition = videoView.currentPosition.takeIf { it >= 0 }?.toLong() ?: 0L
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
            modifier = Modifier.fillMaxSize(),
            factory = { _ ->
                VideoView(context).apply {
                    videoViewRef = this
                    setOnPreparedListener { player ->
                        mediaPlayerRef = player
                        loading = false
                        loadFailed = false
                        player.isLooping = true
                        durationMs = player.duration.takeIf { it > 0 }?.toLong() ?: 0L
                        positionMs = 0L
                        start()
                    }
                    setOnErrorListener { _, _, _ ->
                        loading = false
                        loadFailed = true
                        mediaPlayerRef = null
                        true
                    }
                }
            },
            update = { videoView ->
                videoViewRef = videoView
                val currentTag = videoView.tag as? String
                if (currentTag != location) {
                    loading = true
                    loadFailed = false
                    durationMs = 0L
                    positionMs = 0L
                    isScrubbing = false
                    mediaPlayerRef = null
                    videoView.tag = location
                    val headers = viewerRequestHeaders(sourceKey)
                    val uri = Uri.parse(location)
                    if (headers.isEmpty()) {
                        videoView.setVideoURI(uri)
                    } else {
                        videoView.setVideoURI(uri, headers)
                    }
                    videoView.requestFocus()
                    if (isActive) {
                        videoView.start()
                    }
                }
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
        if (isActive && showTimeline && !loading && !loadFailed && durationMs > 0L) {
            MediaTimelineBar(
                positionMs = positionMs,
                durationMs = durationMs,
                onSeekStarted = {
                    isScrubbing = true
                },
                onSeekChanged = { target ->
                    positionMs = target
                    seekVideoToTimelinePosition(
                        videoView = videoViewRef,
                        mediaPlayer = mediaPlayerRef,
                        targetMs = target,
                    )
                },
                onSeekFinished = { target ->
                    seekVideoToTimelinePosition(
                        videoView = videoViewRef,
                        mediaPlayer = mediaPlayerRef,
                        targetMs = target,
                    )
                    positionMs = target
                    isScrubbing = false
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ViewerGifPlayer(
    sourceKey: SourceKey,
    location: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var movie by remember(location) { mutableStateOf<Movie?>(null) }
    var loading by remember(location) { mutableStateOf(true) }
    var loadFailed by remember(location) { mutableStateOf(false) }
    var positionMs by remember(location) { mutableLongStateOf(0L) }
    var isScrubbing by remember(location) { mutableStateOf(false) }

    LaunchedEffect(location, sourceKey) {
        loading = true
        loadFailed = false
        positionMs = 0L
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

    LaunchedEffect(activeMovie, durationMs, isScrubbing) {
        if (isScrubbing) return@LaunchedEffect
        while (true) {
            delay(16L)
            positionMs = if (durationMs <= 0L) {
                0L
            } else {
                val next = positionMs + 16L
                if (next >= durationMs) 0L else next
            }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
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
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
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
                            input.copyTo(out)
                        }
                    }
                    if (temp.length() <= 0L) {
                        temp.delete()
                        false
                    } else {
                        if (output.exists()) {
                            output.delete()
                        }
                        temp.renameTo(output)
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
    return when (sourceKey) {
        SourceKey.PIXIV -> mapOf(
            "Referer" to "https://www.pixiv.net/",
            "User-Agent" to "Mozilla/5.0",
        )

        SourceKey.GELBOORU -> mapOf(
            "Referer" to "https://gelbooru.com/",
            "User-Agent" to "Mozilla/5.0",
        )

        SourceKey.AIBOORU -> mapOf(
            "Referer" to "https://aibooru.online/",
            "User-Agent" to "Mozilla/5.0",
        )
    }
}

private data class PrefetchCandidate(
    val post: Post,
    val media: ImageRef,
)

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
    val explicitMedia = post.media.filter { ref ->
        !ref.url.isNullOrBlank() || !ref.localPath.isNullOrBlank()
    }
    if (explicitMedia.isNotEmpty()) {
        return explicitMedia
    }
    return listOfNotNull(post.full).ifEmpty { listOf(post.preview) }
}

private fun viewerImageCandidates(post: Post, media: ImageRef): List<String> {
    val refs = buildList {
        add(media)
        post.full?.let { add(it) }
        add(post.preview)
    }
    val preferred = refs
        .mapNotNull { ref ->
            val location = ref.localPath ?: ref.url
            if (location.isNullOrBlank()) {
                null
            } else if (isLikelyImageLocation(ref.mime, location)) {
                location
            } else {
                null
            }
        }
        .distinct()
    if (preferred.isNotEmpty()) return preferred
    return refs
        .mapNotNull { ref -> ref.localPath ?: ref.url }
        .filter { it.isNotBlank() }
        .distinct()
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

private fun isLikelyImageLocation(mime: String?, location: String): Boolean {
    val normalizedMime = mime?.trim()?.lowercase()
    if (normalizedMime != null) {
        if (normalizedMime.startsWith("image/")) return true
        if (normalizedMime.startsWith("video/")) return false
    }
    val normalizedLocation = location
        .substringBefore('?')
        .lowercase()
    return normalizedLocation.endsWith(".jpg") ||
        normalizedLocation.endsWith(".jpeg") ||
        normalizedLocation.endsWith(".png") ||
        normalizedLocation.endsWith(".webp") ||
        normalizedLocation.endsWith(".gif") ||
        normalizedLocation.endsWith(".bmp") ||
        normalizedLocation.endsWith(".heic") ||
        normalizedLocation.endsWith(".heif") ||
        normalizedLocation.endsWith(".avif")
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

private fun seekVideoToTimelinePosition(
    videoView: VideoView?,
    mediaPlayer: MediaPlayer?,
    targetMs: Long,
) {
    val clamped = targetMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mediaPlayer != null) {
        runCatching {
            mediaPlayer.seekTo(clamped.toLong(), MediaPlayer.SEEK_CLOSEST)
        }.onFailure {
            videoView?.seekTo(clamped)
        }
    } else {
        videoView?.seekTo(clamped)
    }
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
private const val GIF_FALLBACK_DURATION_MS = 1000L
private const val VIEWER_DISMISS_DELAY_MS = 24L

@Composable
private fun ViewerChrome(
    modifier: Modifier = Modifier,
    source: String,
    indexLabel: String,
    onBack: () -> Unit,
    onSave: () -> Unit,
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
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                IconButton(onClick = onSave) {
                    Icon(Icons.Default.Save, contentDescription = "Save")
                }
                IconButton(onClick = onInfo) {
                    Icon(Icons.Default.Info, contentDescription = "Info")
                }
            }
        }
    }
}
