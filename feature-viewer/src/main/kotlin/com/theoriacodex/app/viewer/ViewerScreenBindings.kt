package com.theoriacodex.app.viewer

import androidx.compose.runtime.Immutable
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.CommentOrder
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality
import com.theoriacodex.domain.model.ViewerPlaybackMode

data class ViewerScreenConfig(
    val creatorBrowsingSources: Set<SourceKey> = emptySet(),
    val pixivUgoiraClient: PixivUgoiraClient? = null,
    val playbackMode: ViewerPlaybackMode = ViewerPlaybackMode.LOOP,
    val videoQuality: VideoQuality = VideoQuality.AUTO,
    val invertMultiImageScrollDirection: Boolean = false,
    val instantSeek: Boolean = false,
    val autoRotateVideos: Boolean = false,
    val commentOrder: CommentOrder = CommentOrder.OLDEST,
    val likedPostIds: Set<PostId> = emptySet(),
    val galleryReadingModes: Map<PostId, GalleryReadingMode> = emptyMap(),
    val playbackDiagnosticsEnabled: Boolean = false,
    val stripMarks: Map<PostId, ViewerStripMark> = emptyMap(),
)

data class ViewerStripMark(val badge: String? = null, val label: String? = null, val image: ImageRef? = null, val page: Int = 0)

data class ViewerScreenCallbacks(
    val onPlaybackModeSelected: (ViewerPlaybackMode) -> Unit = {},
    val onVideoQualitySelected: (VideoQuality) -> Unit = {},
    val onCommentOrderSelected: (CommentOrder) -> Unit = {},
    val onGalleryReadingModeSelected: (PostId, GalleryReadingMode) -> Unit = { _, _ -> },
    val onVisiblePostChanged: suspend (Post, Int, ViewerSession) -> Unit = { _, _, _ -> },
    val onVisibleMediaChanged: suspend (Post, Int, ViewerSession) -> Unit = { _, _, _ -> },
    val onPlaybackProgress: suspend (Post, ViewerPlaybackProgress, ViewerSession) -> Unit = { _, _, _ -> },
    val onOpenUrl: (String) -> Unit = {},
    val onGoToSearch: () -> Unit,
    val onDismissRelatedPosts: (ViewerStreamSource) -> Unit = {},
    val onRetryRelatedPosts: (ViewerStreamSource) -> Unit = {},
    val onShowRelatedPosts: (ViewerStreamSource, Post) -> Unit = { _, _ -> },
    val onRemoveFromCodex: (codexId: String, Post) -> Unit = { _, _ -> },
    val onOpenCreatorFallback: ((Post) -> Unit)? = null,
)

@Immutable
class ViewerScreenHost(
    val onAction: (ViewerAction) -> Unit,
    val onGoToSearch: () -> Unit = {},
    val onOpenCreatorFallback: ((Post) -> Unit)? = null,
    val onGalleryReadingModeSelected: (PostId, GalleryReadingMode) -> Unit = { _, _ -> },
    val onRequestMediaRecovery: ViewerMediaRecoveryRequest? = null,
    val onVisiblePostChanged: ((Post, Int) -> Unit)? = null,
    val onVisibleMediaChanged: ((Post, Int) -> Unit)? = null,
    val onPlaybackProgress: ((Post, ViewerPlaybackProgress) -> Unit)? = null,
    val onVideoQualitySelected: (VideoQuality) -> Unit = {},
    val durations: ViewerPostDurations = ViewerPostDurations.None,
)
