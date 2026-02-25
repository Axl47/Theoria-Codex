package com.theoriacodex.app.viewer

import android.app.DownloadManager
import android.content.res.Configuration
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.pixiv.PIXIV_UGOIRA_MIME
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(
    posts: List<Post>,
    launchContext: ViewerLaunchContext,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    canLoadMoreFromSource: Boolean = false,
    loadingMoreFromSource: Boolean = false,
    onLoadMoreFromSource: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (Post) -> Unit,
    onOpenInBrowser: (Post) -> Unit,
    onAddIncludeTag: (String) -> Unit,
    onAddExcludeTag: (String) -> Unit,
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
    val currentPostIndex = postPagerState.currentPage.coerceIn(0, posts.lastIndex)
    val selectedPost = posts[currentPostIndex]
    val selectedPostMedia = remember(selectedPost) { viewerMediaItems(selectedPost) }
    val selectedMediaIndex = (mediaIndexByPost[currentPostIndex] ?: 0).coerceIn(0, selectedPostMedia.lastIndex)

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
            val request = buildViewerImageRequest(
                context = context,
                url = data,
                sourceKey = candidate.post.id.source,
            )
            imageLoader.enqueue(request)
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

                Box(
                    modifier = Modifier
                        .fillMaxSize()
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
                        .pointerInput(postPage, mediaPage) {
                            detectTapGestures(
                                onDoubleTap = {
                                    viewerState = viewerState.doubleTap()
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
                ) {
                    val imageUrl = media.url ?: post.full?.url ?: post.preview.url
                    val imageModel = remember(context, imageUrl, post.id.source) {
                        imageUrl?.let { buildViewerImageRequest(context, it, post.id.source) }
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
                        } else if (imageModel != null) {
                            AsyncImage(
                                model = imageModel,
                                contentDescription = post.title ?: post.id.sourcePostId,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
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
                onBack = onDismiss,
            )
            ViewerActionsBar(
                modifier = Modifier.align(Alignment.BottomCenter),
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
        ModalBottomSheet(
            onDismissRequest = { showInfoSheet = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Info & Actions", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        onSave(post)
                        showInfoSheet = false
                    }) {
                        Text("Save to Codex")
                    }
                    if (!post.pageUrl.isNullOrBlank()) {
                        TextButton(onClick = { onOpenInBrowser(post) }) {
                            Text("Open in browser")
                        }
                    }
                }

                Text("Tags", style = MaterialTheme.typography.titleSmall)
                post.canonicalTags.forEach { tag ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tag, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onAddIncludeTag(tag) }) { Text("+") }
                            TextButton(onClick = { onAddExcludeTag(tag) }) { Text("-") }
                        }
                    }
                }

                TextButton(onClick = {
                    onGoToSearch()
                    showInfoSheet = false
                }) {
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
                Text("Image actions", style = MaterialTheme.typography.titleMedium)
                val isCurrentUgoira = currentMedia?.let { media ->
                    isPixivUgoira(selectedPost, media)
                } == true
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
                                if (didQueueDownload) "Download started" else "Image unavailable",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    enabled = (isCurrentUgoira && pixivUgoiraClient != null) || currentMedia?.url != null,
                ) {
                    Text(if (isCurrentUgoira) "Download MP4" else "Download image")
                }
            }
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
    if (sourceKey == SourceKey.PIXIV) {
        builder
            .addHeader("Referer", "https://www.pixiv.net/")
            .addHeader("User-Agent", "Mozilla/5.0")
    }
    return builder.build()
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

private fun isPixivUgoira(post: Post, media: ImageRef): Boolean {
    if (post.id.source != SourceKey.PIXIV) return false
    return media.mime == PIXIV_UGOIRA_MIME || post.full?.mime == PIXIV_UGOIRA_MIME
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
    if (post.id.source == SourceKey.PIXIV) {
        request
            .addRequestHeader("Referer", "https://www.pixiv.net/")
            .addRequestHeader("User-Agent", "Mozilla/5.0")
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

@Composable
private fun ViewerChrome(
    modifier: Modifier = Modifier,
    source: String,
    indexLabel: String,
    onBack: () -> Unit,
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
            Text("$source • $indexLabel", style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = {}) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color.Transparent)
            }
        }
    }
}

@Composable
private fun ViewerActionsBar(
    modifier: Modifier = Modifier,
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
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onSave) {
                Icon(Icons.Default.Save, contentDescription = null)
                Text("Save", modifier = Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = onInfo) {
                Icon(Icons.Default.Info, contentDescription = null)
                Text("Info", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
