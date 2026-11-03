package com.theoriacodex.app.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
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
import com.theoriacodex.app.media.animatedDurationLabel
import com.theoriacodex.app.media.isAuthoritativeDurationMedia
import com.theoriacodex.app.media.isHttpNotFound
import com.theoriacodex.app.media.isPixivUgoiraPost
import com.theoriacodex.app.media.mergeResolvedPostForPresentation
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.app.media.postPlaybackMediaCandidate
import com.theoriacodex.app.media.previewMediaDeliveryPlan
import com.theoriacodex.app.post.displayTitleOrNull
import com.theoriacodex.app.source.SourceLogo
import com.theoriacodex.app.source.isRule34Family
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
    var viewerMediaCoordinates by remember(post.id) { mutableStateOf<LayoutCoordinates?>(null) }
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
    var mediaCorners by remember { mutableStateOf(ViewerReturnCorners()) }
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
                        viewerMediaCoordinates?.takeIf { it.isAttached }?.boundsInWindow(clipBounds = false),
                        shelfId = viewerReturnShelf,
                        corners = mediaCorners,
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
            onMediaPositioned = { coordinates, titleBelowMedia ->
                viewerMediaCoordinates = coordinates
                mediaCorners = viewerReturnCorners(
                    cardShape.createOutline(coordinates.size.toSize(), layoutDirection, cardDensity), titleBelowMedia,
                )
                viewerReturn?.recordBounds(viewerReturnGrid, post.id, coordinates.boundsInWindow(clipBounds = false), viewerReturnShelf, mediaCorners)
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val watchPresentation = LocalPostWatchPresentation.current.value
    val lastPosition = watchPresentation.positions[post.id]
    val lastMediaNumber = lastPosition?.mediaNumber
    val watchedEntry = watchPresentation.watched[post.id].takeIf { showWatchHistory }
    val cardViewedMediaNumber = viewedMediaNumber ?: watchedEntry?.maxViewedMediaNumber
    // A bare "2h ago" over a result reads as its upload date, so watch history gets its own mark.
    val watchedDescription = watchedEntry?.takeIf { metadataLabel == null }?.let {
        val watchedAt = relativeTimeLabel(System.currentTimeMillis(), lastPosition?.lastViewedAt ?: it.viewedAtEpochMs)
        "Watched ${watchedAt.lowercase(Locale.US)}"
    }
    val effectivePost = resolution.resolvedPost?.let { resolved ->
        mergeResolvedPostForPresentation(original = post, resolved = resolved)
    } ?: post
    val watchedPost = watchPresentation.watched[post.id]?.post
    val previewPost = remember(effectivePost, watchedPost, lastMediaNumber) {
        val previewSource = if (lastMediaNumber != null && lastMediaNumber > postMediaItems(effectivePost).size && watchedPost != null) {
            mergeResolvedPostForPresentation(original = effectivePost, resolved = watchedPost)
        } else {
            effectivePost
        }
        postForCardPreview(previewSource, lastMediaNumber)
    }
    val title = effectivePost.displayTitleOrNull()
    val supportingContent = searchCardSupportingContent(
        title = title,
        metadataLabel = metadataLabel,
        showWatchedBadge = watchedDescription != null,
        showSourceBadge = showSourceBadge,
    )
    val videoRef = remember(
        effectivePost.id.source,
        effectivePost.id.sourcePostId,
        previewPost.media,
        previewPost.full,
        previewPost.preview,
    ) {
        if (allowsInlineAutoplayInSearch(previewPost)) resolveCardVideoRef(previewPost) else null
    }
    val imageDeliveryPlan = remember(previewPost) { previewMediaDeliveryPlan(previewPost) }
    val imageCandidates = imageDeliveryPlan.candidates
    val progress = remember(effectivePost.id, imageDeliveryPlan) { SearchCardImageProgress() }
    val previewCandidate = imageCandidates.getOrNull(progress.displayedIndex)
    val previewUrl = previewCandidate?.location
    val ratio = remember(post.id, mediaAspectRatio) { mediaAspectRatio ?: previewAspectRatio(post) }
    val windowSize = LocalWindowInfo.current.containerSize
    val decodeSize = remember(windowSize.width, ratio) {
        feedPreviewDecodeSize(screenWidthPx = windowSize.width, aspectRatio = ratio)
    }
    val imageModel = remember(context, previewUrl, effectivePost.id.source, decodeSize) {
        previewUrl?.let {
            buildFeedImageRequest(
                context = context,
                url = it,
                sourceKey = effectivePost.id.source,
                decodeSize = decodeSize,
                // Animated drawables skip the memory cache, so keep the poster up while one decodes.
                placeholderMemoryCacheKey = progress.posterMemoryCacheKey.takeIf {
                    previewCandidate.activation == MediaDeliveryActivation.ANIMATION_UPGRADE
                },
            )
        }
    }
    SearchCardImageUpgradeEffect(
        imageDeliveryPlan, progress, decodeSize, effectivePost, playbackActive, resolution, recoverPostMedia,
    )
    LaunchedEffect(effectivePost.id, videoRef?.url, videoRef?.localPath, resolvePostById) {
        if (videoRef == null && resolution.resolvedPost == null) resolution.requestPreview(scope, resolvePostById)
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
            effectivePost, watchedPost, pixivUgoiraClient, title ?: effectivePost.id.sourcePostId, playbackActive,
            videoRef, imageModel, imageCandidates.lastIndex, previewCandidate?.ref, previewCandidate?.activation,
            previewUrl, progress, resolution, resolvePostById, recoverPostMedia, refreshOnPreviewError,
            playbackDiagnosticsEnabled, onAuthoritativeDurationKnown,
        )
        val mediaCount = postMediaCount(effectivePost)
        if (mediaCount > 1) {
            com.theoriacodex.app.ui.components.MediaCountBadge(
                text = imageCountBadgeLabel(mediaCount, cardViewedMediaNumber),
                description = if (cardViewedMediaNumber == null) "Image count" else "Watched media progress",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            )
        }
        if (onToggleLike != null) SearchCardLikeButton(liked, onToggleLike, Modifier.align(Alignment.TopStart))
        if (supportingContent.showPreviewContext) {
            SearchCardPreviewContext(
                metadataLabel, watchedDescription, showSourceBadge, effectivePost.id.source,
                Modifier.align(Alignment.BottomEnd),
            )
        }
        animatedDurationLabel(effectivePost, acquiredDurationMs)?.let { durationLabel ->
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                PreviewMetadataLabel(durationLabel)
            }
        }
    }
    if (titleBelowMedia) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = requireNotNull(title),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
        pendingUpgrades.forEach { nextIndex ->
            val nextCandidate = plan.candidates[nextIndex]
            val result = runCatchingPreservingCancellation {
                context.imageLoader.execute(
                    buildFeedImageRequest(
                        context = context,
                        url = nextCandidate.location,
                        sourceKey = post.id.source,
                        decodeSize = decodeSize,
                    )
                )
            }.getOrNull()
            if (result is SuccessResult) {
                progress.preparedIndex = nextIndex
                progress.displayedIndex = nextIndex
                return@LaunchedEffect
            }
            if (
                upgradesToAnimation &&
                result is ErrorResult &&
                resolution.canRecover(recoverPostMedia != null, result.throwable, nextCandidate.location) &&
                resolution.recover(post, nextCandidate.ref.copy(url = nextCandidate.location), recoverPostMedia)
            ) {
                return@LaunchedEffect
            }
        }
    }
}

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
    previewRef: ImageRef?,
    previewActivation: MediaDeliveryActivation?,
    previewUrl: String?,
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
            loadingPreview = {
                FeedAsyncImage(
                    model = imageModel,
                    contentDescription = description,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    isActive = false,
                    onError = {
                        if (progress.displayedIndex < lastCandidateIndex) progress.displayedIndex += 1
                    },
                )
            },
        )
        videoRef != null && !videoPlaybackFailed -> SearchVideoPreview(
            media = videoRef,
            mediaIndex = postMediaItems(post).indexOf(videoRef).takeIf { it >= 0 }
                ?: watchedPost?.let { postMediaItems(it).indexOf(videoRef).takeIf { index -> index >= 0 } }
                ?: 0,
            postId = post.id,
            sourceKey = post.id.source,
            playbackDiagnosticsEnabled = playbackDiagnosticsEnabled,
            isActive = playbackActive,
            previewModel = imageModel,
            modifier = Modifier.fillMaxSize(),
            onPlaybackError = {
                videoPlaybackFailed = true
                if (resolution.resolvedPost == null) {
                    resolution.requestPreview(scope, resolvePostById, force = refreshOnPreviewError)
                }
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
            onSuccess = { result ->
                progress.hasVisibleImage = true
                progress.preparedIndex = progress.displayedIndex
                if (previewActivation != MediaDeliveryActivation.ANIMATION_UPGRADE) {
                    progress.posterMemoryCacheKey = result.memoryCacheKey
                }
            },
            onError = { state ->
                val canAdvance = !progress.hasVisibleImage && progress.displayedIndex < lastCandidateIndex
                val failedRef = previewRef?.copy(url = previewUrl)
                if (failedRef != null && resolution.canRecover(recoverPostMedia != null, state.result.throwable, previewUrl)) {
                    scope.launch {
                        if (!resolution.recover(post, failedRef, recoverPostMedia) && canAdvance) {
                            progress.displayedIndex += 1
                        }
                    }
                } else if (canAdvance) {
                    progress.displayedIndex += 1
                } else if (resolution.resolvedPost == null && refreshOnPreviewError) {
                    resolution.requestPreview(scope, resolvePostById, force = true)
                }
            },
        )
        else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "No preview", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SearchCardLikeButton(liked: Boolean, onToggleLike: () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier
            .padding(8.dp)
            .size(48.dp)
            .semantics {
                contentDescription = if (liked) "Unlike post" else "Like post"
                selected = liked
                stateDescription = if (liked) "Liked" else "Not liked"
            }
            .clickable(role = Role.Checkbox, onClick = onToggleLike),
        contentAlignment = Alignment.TopStart,
    ) {
        Surface(
            modifier = Modifier
                .size(30.dp)
                .testTag("Search like visual"),
            color = Color.Black.copy(alpha = 0.55f),
            shape = CircleShape,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (liked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    tint = if (liked) MaterialTheme.colorScheme.error else Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchCardPreviewContext(
    metadataLabel: String?,
    watchedDescription: String?,
    showSourceBadge: Boolean,
    source: SourceKey,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (metadataLabel != null) PreviewMetadataLabel(metadataLabel)
        if (watchedDescription != null) PreviewWatchedBadge(watchedDescription)
        if (showSourceBadge) SourceBadge(source = source)
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

@Composable
private fun PreviewMetadataLabel(label: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(6.dp),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** Marks a previously watched post; when it was watched is left to screen readers and Recents. */
@Composable
private fun PreviewWatchedBadge(description: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Visibility,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.padding(4.dp).size(14.dp),
        )
    }
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

@Composable
private fun SourceBadge(
    source: SourceKey,
    modifier: Modifier = Modifier,
) {
    val labelHeight = with(LocalDensity.current) {
        MaterialTheme.typography.labelSmall.lineHeight.toDp()
    }
    Surface(
        modifier = modifier.height(labelHeight + 8.dp),
        color = if (source.isRule34Family()) {
            Color.Transparent
        } else {
            Color.Black.copy(alpha = 0.55f)
        },
        shape = RoundedCornerShape(6.dp),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            SourceLogo(
                source = source,
                size = if (source.isRule34Family()) 0.dp else 12.dp,
            )
        }
    }
}

internal fun previewAspectRatio(post: Post): Float {
    val width = post.width ?: return 1f
    val height = post.height ?: return 1f
    if (width <= 0 || height <= 0) return 1f
    return width.toFloat() / height.toFloat()
}
