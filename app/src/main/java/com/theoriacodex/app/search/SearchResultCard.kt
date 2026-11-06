package com.theoriacodex.app.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.theoriacodex.app.media.MediaDeliveryActivation
import com.theoriacodex.app.media.MediaDeliveryCandidate
import com.theoriacodex.app.media.animatedDurationLabel
import com.theoriacodex.app.media.isAuthoritativeDurationMedia
import com.theoriacodex.app.media.isHttpNotFound
import com.theoriacodex.app.media.isPixivUgoiraPost
import com.theoriacodex.app.media.mergeResolvedPostForPresentation
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.app.media.postPlaybackMediaCandidate
import com.theoriacodex.app.media.previewMediaDeliveryPlan
import com.theoriacodex.app.post.displayTitleOrNull
import com.theoriacodex.app.viewer.LocalViewerReturnGrid
import com.theoriacodex.app.viewer.LocalViewerReturnShelf
import com.theoriacodex.app.viewer.LocalViewerReturnTransition
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.app.viewer.PixivUgoiraPlayer
import com.theoriacodex.app.viewer.UgoiraSizeBucket
import com.theoriacodex.app.viewer.ViewerReturnCorners
import com.theoriacodex.app.viewer.viewerReturnCorners
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SearchResultCard(
    post: Post,
    pixivUgoiraClient: PixivUgoiraClient?,
    acquiredDurationMs: Long? = null,
    showSourceBadge: Boolean = false,
    metadataLabel: String? = null,
    viewedMediaNumber: Int? = null,
    showWatchHistory: Boolean = false,
    mediaAspectRatio: Float? = null,
    showTitle: Boolean = true,
    liked: Boolean = false,
    onToggleLike: (() -> Unit)? = null,
    resolvePostById: (suspend (PostId) -> Post?)? = null,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)? = null,
    refreshOnPreviewError: Boolean = false,
    playbackDiagnosticsEnabled: Boolean = false,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onViewportChanged: (Boolean) -> Unit = {},
    onAuthoritativeDurationKnown: (Long) -> Unit = {},
) {
    val viewerReturn = LocalViewerReturnTransition.current
    val viewerReturnGrid = LocalViewerReturnGrid.current
    val viewerReturnShelf = LocalViewerReturnShelf.current
    val mediaGeometry = remember(post.id) { SearchCardMediaGeometry() }
    var isInViewport by remember(post.id) { mutableStateOf(false) }
    val playbackActive = shouldFeedMediaPlay(
        isInViewport = isInViewport,
        isLifecycleStarted = rememberLifecycleStarted(),
    )
    val resolution = remember(post.id) { SearchCardResolution(post.id) }
    DisposableEffect(post.id) {
        onDispose {
            if (isInViewport) onViewportChanged(false)
        }
    }
    val cardShape = CardDefaults.elevatedShape
    val layoutDirection = LocalLayoutDirection.current
    val cardDensity = LocalDensity.current
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(searchCardTestTag(post.id))
            .combinedClickable(
                onClick = {
                    viewerReturn?.openedFrom(
                        viewerReturnGrid,
                        mediaGeometry.coordinates?.takeIf { it.isAttached }?.boundsInWindow(clipBounds = false),
                        shelfId = viewerReturnShelf,
                        corners = mediaGeometry.corners(cardShape, layoutDirection, cardDensity),
                    )
                    onClick()
                },
                onLongClick = onLongPress,
            ),
    ) {
        SearchCardContent(
            post, resolution, pixivUgoiraClient, playbackActive, acquiredDurationMs, showSourceBadge, metadataLabel,
            viewedMediaNumber, showWatchHistory, mediaAspectRatio, showTitle, liked, onToggleLike, resolvePostById,
            recoverPostMedia, refreshOnPreviewError, playbackDiagnosticsEnabled, onAuthoritativeDurationKnown,
            // A visible caption must not retain autoplay demand for media outside the window.
            // Runs every scrolled frame: keep it to the visibility check unless a Viewer return is landing here.
            onMediaPositioned = { coordinates, titleBelowMedia ->
                mediaGeometry.coordinates = coordinates
                mediaGeometry.titleBelowMedia = titleBelowMedia
                if (viewerReturn?.request != null) {
                    viewerReturn.recordBounds(
                        viewerReturnGrid, post.id, coordinates.boundsInWindow(clipBounds = false), viewerReturnShelf,
                        mediaGeometry.corners(cardShape, layoutDirection, cardDensity),
                    )
                }
                val visible = coordinates.isAttached &&
                    isVisibleFeedBounds(coordinates.boundsInWindow(clipBounds = true))
                if (isInViewport != visible) {
                    isInViewport = visible
                    onViewportChanged(visible)
                }
            },
        )
    }
}

/** The media slot's latest placement; its corners are rebuilt only when its size or caption changes. */
private class SearchCardMediaGeometry {
    var coordinates: LayoutCoordinates? = null
    var titleBelowMedia = false
    private var cornersSize = IntSize.Zero
    private var cornersTitleBelowMedia = false
    private var cachedCorners: ViewerReturnCorners? = null

    fun corners(shape: Shape, layoutDirection: LayoutDirection, density: Density): ViewerReturnCorners {
        val size = coordinates?.size ?: IntSize.Zero
        val cached = cachedCorners
        if (cached != null && size == cornersSize && titleBelowMedia == cornersTitleBelowMedia) return cached
        return viewerReturnCorners(shape.createOutline(size.toSize(), layoutDirection, density), titleBelowMedia).also {
            cachedCorners = it
            cornersSize = size
            cornersTitleBelowMedia = titleBelowMedia
        }
    }
}

/** Whether the host is at least started, kept current as its lifecycle moves. */
@Composable
private fun rememberLifecycleStarted(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var started by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return started
}

/** One post's replacement data from its provider: resolving a preview, and recovering dead media. */
@Stable
private class SearchCardResolution(private val postId: PostId) {
    var resolvedPost by mutableStateOf<Post?>(null)
        private set
    private var attempted = false
    private val recoveryAttemptedUrls = mutableSetOf<String>()

    fun requestPreview(scope: CoroutineScope, resolver: (suspend (PostId) -> Post?)?, force: Boolean = false) {
        if (attempted || resolver == null) return
        if (!force && postId.source != SourceKey.RULE34VIDEO && postId.source != SourceKey.RULE34GEN) return
        attempted = true
        scope.launch {
            resolvedPost = runCatchingPreservingCancellation { resolver(postId) }.getOrNull() ?: return@launch
        }
    }

    fun canRecover(canRecoverAtAll: Boolean, error: Throwable, location: String?): Boolean =
        canRecoverAtAll && isHttpNotFound(error) && recoveryAttemptedUrls.add(location.orEmpty())

    /** Returns whether the provider supplied replacement media for this card. */
    suspend fun recover(
        current: Post,
        failedRef: ImageRef,
        recoverPostMedia: (suspend (Post, ImageRef) -> Post?)?,
    ): Boolean {
        val recovered = runCatchingPreservingCancellation { recoverPostMedia?.invoke(current, failedRef) }.getOrNull()
        if (recovered == null || recovered == current) return false
        resolvedPost = recovered
        return true
    }
}

/** How far one delivery plan has progressed: what is shown, what is ready, and the poster to hold. */
@Stable
private class SearchCardImageProgress {
    var displayedIndex by mutableIntStateOf(0)
    var preparedIndex by mutableIntStateOf(0)
    var hasVisibleImage by mutableStateOf(false)
    var posterMemoryCacheKey by mutableStateOf<MemoryCache.Key?>(null)

    fun onImageShown(result: SuccessResult, activation: MediaDeliveryActivation?) {
        hasVisibleImage = true
        preparedIndex = displayedIndex
        if (activation != MediaDeliveryActivation.ANIMATION_UPGRADE) posterMemoryCacheKey = result.memoryCacheKey
    }
}

/** What the card's watch history changes: its preview, progress badge, and watched mark. */
private class SearchCardWatch(
    val watchedPost: Post?,
    val viewedMediaNumber: Int?,
    val watchedDescription: String?,
    val previewPost: Post,
)

@Composable
private fun rememberSearchCardWatch(
    post: Post,
    effectivePost: Post,
    showWatchHistory: Boolean,
    metadataLabel: String?,
    viewedMediaNumber: Int?,
): SearchCardWatch {
    val (lastPosition, watchedHistoryEntry) = rememberPostWatchEntry(post.id).value
    val lastMediaNumber = lastPosition?.mediaNumber
    val watchedEntry = watchedHistoryEntry.takeIf { showWatchHistory }
    // A bare "2h ago" over a result reads as its upload date, so watch history gets its own mark.
    val watchedDescription = watchedEntry?.takeIf { metadataLabel == null }?.let {
        val watchedAt = relativeTimeLabel(System.currentTimeMillis(), lastPosition?.lastViewedAt ?: it.viewedAtEpochMs)
        "Watched ${watchedAt.lowercase(Locale.US)}"
    }
    val watchedPost = watchedHistoryEntry?.post
    val previewPost = remember(effectivePost, watchedPost, lastMediaNumber) {
        val previewSource = if (lastMediaNumber != null && lastMediaNumber > postMediaItems(effectivePost).size && watchedPost != null) {
            mergeResolvedPostForPresentation(original = effectivePost, resolved = watchedPost)
        } else {
            effectivePost
        }
        postForCardPreview(previewSource, lastMediaNumber)
    }
    return SearchCardWatch(watchedPost, viewedMediaNumber ?: watchedEntry?.maxViewedMediaNumber, watchedDescription, previewPost)
}

@Composable
private fun SearchCardContent(
    post: Post,
    resolution: SearchCardResolution,
    pixivUgoiraClient: PixivUgoiraClient?,
    playbackActive: Boolean,
    acquiredDurationMs: Long?,
    showSourceBadge: Boolean,
    metadataLabel: String?,
    viewedMediaNumber: Int?,
    showWatchHistory: Boolean,
    mediaAspectRatio: Float?,
    showTitle: Boolean,
    liked: Boolean,
    onToggleLike: (() -> Unit)?,
    resolvePostById: (suspend (PostId) -> Post?)?,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)?,
    refreshOnPreviewError: Boolean,
    playbackDiagnosticsEnabled: Boolean,
    onAuthoritativeDurationKnown: (Long) -> Unit,
    onMediaPositioned: (LayoutCoordinates, Boolean) -> Unit,
) {
    val effectivePost = resolution.resolvedPost?.let { resolved ->
        mergeResolvedPostForPresentation(original = post, resolved = resolved)
    } ?: post
    val watch = rememberSearchCardWatch(post, effectivePost, showWatchHistory, metadataLabel, viewedMediaNumber)
    val title = effectivePost.displayTitleOrNull()
    val supportingContent = searchCardSupportingContent(
        title = title,
        metadataLabel = metadataLabel,
        showWatchedBadge = watch.watchedDescription != null,
        showSourceBadge = showSourceBadge,
    )
    val previewPost = watch.previewPost
    val videoRef = remember(
        effectivePost.id.source, effectivePost.id.sourcePostId, previewPost.media, previewPost.full, previewPost.preview,
    ) {
        if (allowsInlineAutoplayInSearch(previewPost)) resolveCardVideoRef(previewPost) else null
    }
    val imageDeliveryPlan = remember(previewPost) { previewMediaDeliveryPlan(previewPost) }
    val progress = remember(effectivePost.id, imageDeliveryPlan) { SearchCardImageProgress() }
    val previewCandidate = imageDeliveryPlan.candidates.getOrNull(progress.displayedIndex)
    val ratio = remember(post.id, mediaAspectRatio) { mediaAspectRatio ?: previewAspectRatio(post) }
    val windowSize = LocalWindowInfo.current.containerSize
    val decodeSize = remember(windowSize.width, ratio) {
        feedPreviewDecodeSize(screenWidthPx = windowSize.width, aspectRatio = ratio)
    }
    val imageModel = rememberSearchCardImageModel(previewCandidate, progress, effectivePost.id.source, decodeSize)
    SearchCardImageUpgradeEffect(
        imageDeliveryPlan, progress, decodeSize, effectivePost, playbackActive, resolution, recoverPostMedia,
    )
    val scope = rememberCoroutineScope()
    // A new resolver instance must not restart the request; only gaining one at all should.
    val currentResolvePostById by rememberUpdatedState(resolvePostById)
    LaunchedEffect(effectivePost.id, videoRef?.url, videoRef?.localPath, resolvePostById != null) {
        if (videoRef == null && resolution.resolvedPost == null) resolution.requestPreview(scope, currentResolvePostById)
    }
    val titleBelowMedia = showTitle && supportingContent.showTitleFooter
    Box(
        modifier = Modifier
            .testTag(searchMediaTestTag(post.id))
            .fillMaxWidth()
            .aspectRatio(ratio)
            .onGloballyPositioned { coordinates -> onMediaPositioned(coordinates, titleBelowMedia) },
    ) {
        SearchCardMedia(
            effectivePost, watch.watchedPost, pixivUgoiraClient, title ?: effectivePost.id.sourcePostId, playbackActive,
            videoRef, imageModel, imageDeliveryPlan.candidates.lastIndex, previewCandidate, progress, resolution,
            resolvePostById, recoverPostMedia, refreshOnPreviewError, playbackDiagnosticsEnabled, onAuthoritativeDurationKnown,
        )
        SearchCardBadges(
            effectivePost, watch.viewedMediaNumber, watch.watchedDescription, liked, onToggleLike, metadataLabel, showSourceBadge,
            supportingContent.showPreviewContext, animatedDurationLabel(effectivePost, acquiredDurationMs),
        )
    }
    if (titleBelowMedia) SearchCardTitle(requireNotNull(title))
}

@Composable
private fun rememberSearchCardImageModel(
    candidate: MediaDeliveryCandidate?,
    progress: SearchCardImageProgress,
    source: SourceKey,
    decodeSize: FeedPreviewDecodeSize,
): ImageRequest? {
    val context = LocalContext.current
    val url = candidate?.location
    return remember(context, url, source, decodeSize) {
        url?.let {
            buildFeedImageRequest(
                context = context,
                url = it,
                sourceKey = source,
                decodeSize = decodeSize,
                // Animated drawables skip the memory cache, so keep the poster up while one decodes.
                placeholderMemoryCacheKey = progress.posterMemoryCacheKey.takeIf {
                    candidate.activation == MediaDeliveryActivation.ANIMATION_UPGRADE
                },
            )
        }
    }
}

@Composable
private fun SearchCardTitle(title: String) {
    Column(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Fetches the next image candidates in the background, then swaps to the first that loads. */
@Composable
private fun SearchCardImageUpgradeEffect(
    plan: com.theoriacodex.app.media.MediaDeliveryPlan,
    progress: SearchCardImageProgress,
    decodeSize: FeedPreviewDecodeSize,
    post: Post,
    playbackActive: Boolean,
    resolution: SearchCardResolution,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)?,
) {
    val context = LocalContext.current
    val pendingUpgrades = remember(plan, progress.displayedIndex) { plan.upgradeIndicesAfter(progress.displayedIndex) }
    val upgradesToAnimation = pendingUpgrades.firstOrNull()
        ?.let { index -> plan.candidates[index].activation == MediaDeliveryActivation.ANIMATION_UPGRADE } == true
    // Whole-file animation downloads are costly, so like video players they follow settled visibility.
    val upgradeWanted = !upgradesToAnimation || playbackActive
    LaunchedEffect(plan, progress.displayedIndex, progress.preparedIndex, progress.hasVisibleImage, decodeSize, upgradeWanted) {
        if (!progress.hasVisibleImage || !upgradeWanted || progress.displayedIndex != progress.preparedIndex) {
            return@LaunchedEffect
        }
        if (upgradesToAnimation) delay(FEED_PLAYER_ACTIVATION_DELAY_MS)
        for (nextIndex in pendingUpgrades) {
            val done = tryImageUpgrade(context, plan, nextIndex, progress, decodeSize, post, upgradesToAnimation, resolution, recoverPostMedia)
            if (done) return@LaunchedEffect
        }
    }
}

/** Returns whether this candidate settled the attempt, by loading or by replacing the post's media. */
private suspend fun tryImageUpgrade(
    context: android.content.Context,
    plan: com.theoriacodex.app.media.MediaDeliveryPlan,
    nextIndex: Int,
    progress: SearchCardImageProgress,
    decodeSize: FeedPreviewDecodeSize,
    post: Post,
    upgradesToAnimation: Boolean,
    resolution: SearchCardResolution,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)?,
): Boolean {
    val candidate = plan.candidates[nextIndex]
    val result = runCatchingPreservingCancellation {
        context.imageLoader.execute(
            buildFeedImageRequest(context = context, url = candidate.location, sourceKey = post.id.source, decodeSize = decodeSize)
        )
    }.getOrNull()
    if (result is SuccessResult) {
        progress.preparedIndex = nextIndex
        progress.displayedIndex = nextIndex
        return true
    }
    return upgradesToAnimation && recoverFailedUpgrade(result, candidate, post, resolution, recoverPostMedia)
}

private suspend fun recoverFailedUpgrade(
    result: coil.request.ImageResult?,
    candidate: MediaDeliveryCandidate,
    post: Post,
    resolution: SearchCardResolution,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)?,
): Boolean = result is ErrorResult &&
    resolution.canRecover(recoverPostMedia != null, result.throwable, candidate.location) &&
    resolution.recover(post, candidate.ref.copy(url = candidate.location), recoverPostMedia)

/** The card's single media slot: animation, inline video, image, or a placeholder, in that order. */
@Composable
private fun SearchCardMedia(
    post: Post,
    watchedPost: Post?,
    pixivUgoiraClient: PixivUgoiraClient?,
    description: String,
    playbackActive: Boolean,
    videoRef: ImageRef?,
    imageModel: ImageRequest?,
    lastCandidateIndex: Int,
    previewCandidate: MediaDeliveryCandidate?,
    progress: SearchCardImageProgress,
    resolution: SearchCardResolution,
    resolvePostById: (suspend (PostId) -> Post?)?,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)?,
    refreshOnPreviewError: Boolean,
    playbackDiagnosticsEnabled: Boolean,
    onAuthoritativeDurationKnown: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var videoPlaybackFailed by remember(post.id.source, post.id.sourcePostId, videoRef?.url, videoRef?.localPath) {
        mutableStateOf(false)
    }
    when {
        isPixivUgoiraPost(post) && pixivUgoiraClient != null -> PixivUgoiraPlayer(
            postId = post.id.sourcePostId,
            client = pixivUgoiraClient,
            modifier = Modifier.fillMaxSize(),
            contentDescription = description,
            contentScale = ContentScale.Crop,
            sizeBucket = UgoiraSizeBucket.CARD,
            isActive = playbackActive,
            onDurationKnown = onAuthoritativeDurationKnown,
            loadingPreview = { SearchCardAnimationPoster(imageModel, description, progress, lastCandidateIndex) },
        )
        videoRef != null && !videoPlaybackFailed -> SearchVideoPreview(
            media = videoRef,
            mediaIndex = searchCardVideoIndex(post, watchedPost, videoRef),
            postId = post.id,
            sourceKey = post.id.source,
            playbackDiagnosticsEnabled = playbackDiagnosticsEnabled,
            isActive = playbackActive,
            previewModel = imageModel,
            modifier = Modifier.fillMaxSize(),
            onPlaybackError = {
                videoPlaybackFailed = true
                if (resolution.resolvedPost == null) resolution.requestPreview(scope, resolvePostById, force = refreshOnPreviewError)
            },
            onDurationKnown = { durationMs ->
                if (isAuthoritativeDurationMedia(post, videoRef)) onAuthoritativeDurationKnown(durationMs)
            },
        )
        imageModel != null -> FeedAsyncImage(
            model = imageModel,
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            isActive = playbackActive,
            onSuccess = { result -> progress.onImageShown(result, previewCandidate?.activation) },
            onError = { state ->
                handleSearchCardImageError(
                    state.result.throwable, post, previewCandidate, progress, lastCandidateIndex, resolution,
                    resolvePostById, recoverPostMedia, refreshOnPreviewError, scope,
                )
            },
        )
        else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "No preview", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SearchCardAnimationPoster(
    imageModel: ImageRequest?,
    description: String,
    progress: SearchCardImageProgress,
    lastCandidateIndex: Int,
) {
    FeedAsyncImage(
        model = imageModel,
        contentDescription = description,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
        isActive = false,
        onError = { if (progress.displayedIndex < lastCandidateIndex) progress.displayedIndex += 1 },
    )
}

private fun searchCardVideoIndex(post: Post, watchedPost: Post?, videoRef: ImageRef): Int =
    postMediaItems(post).indexOf(videoRef).takeIf { it >= 0 }
        ?: watchedPost?.let { postMediaItems(it).indexOf(videoRef).takeIf { index -> index >= 0 } }
        ?: 0

/** Recovers dead media first, then tries the next candidate, then asks the provider once more. */
private fun handleSearchCardImageError(
    error: Throwable,
    post: Post,
    candidate: MediaDeliveryCandidate?,
    progress: SearchCardImageProgress,
    lastCandidateIndex: Int,
    resolution: SearchCardResolution,
    resolvePostById: (suspend (PostId) -> Post?)?,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)?,
    refreshOnPreviewError: Boolean,
    scope: CoroutineScope,
) {
    val canAdvance = !progress.hasVisibleImage && progress.displayedIndex < lastCandidateIndex
    val previewUrl = candidate?.location
    val failedRef = candidate?.ref?.copy(url = previewUrl)
    if (failedRef != null && resolution.canRecover(recoverPostMedia != null, error, previewUrl)) {
        scope.launch {
            if (!resolution.recover(post, failedRef, recoverPostMedia) && canAdvance) progress.displayedIndex += 1
        }
    } else if (canAdvance) {
        progress.displayedIndex += 1
    } else if (resolution.resolvedPost == null && refreshOnPreviewError) {
        resolution.requestPreview(scope, resolvePostById, force = true)
    }
}

internal data class SearchCardSupportingContent(
    val showTitleFooter: Boolean,
    val showPreviewContext: Boolean,
)

internal fun searchCardSupportingContent(
    title: String?,
    metadataLabel: String?,
    showWatchedBadge: Boolean,
    showSourceBadge: Boolean,
): SearchCardSupportingContent {
    return SearchCardSupportingContent(
        showTitleFooter = title != null,
        showPreviewContext = metadataLabel != null || showWatchedBadge || showSourceBadge,
    )
}

internal fun imageCountBadgeLabel(count: Int, viewedMediaNumber: Int?): String {
    val progress = viewedMediaNumber?.coerceIn(1, count)
    return progress?.let { "$it/$count" } ?: count.toString()
}

internal fun postMediaCount(post: Post): Int {
    post.mediaCount?.takeIf { it > 0 }?.let { return it }
    val explicitCount = post.media.count { !it.url.isNullOrBlank() || !it.localPath.isNullOrBlank() }
    return when {
        explicitCount > 0 -> explicitCount
        post.full != null -> 1
        else -> 1
    }
}

private fun resolveCardVideoRef(post: Post): ImageRef? {
    return postPlaybackMediaCandidate(post)?.ref
}

internal fun allowsInlineAutoplayInSearch(post: Post): Boolean {
    return post.id.source != SourceKey.IWARA && post.id.source != SourceKey.HITOMI
}

internal fun previewAspectRatio(post: Post): Float {
    val width = post.width ?: return 1f
    val height = post.height ?: return 1f
    if (width <= 0 || height <= 0) return 1f
    return width.toFloat() / height.toFloat()
}
