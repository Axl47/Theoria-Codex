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

/** Settings and providers the Viewer renders with. The app builds one from its settings; a change replaces it. */
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
    /** Galleries whose reading mode the user chose; the rest follow [invertMultiImageScrollDirection]. */
    val galleryReadingModes: Map<PostId, GalleryReadingMode> = emptyMap(),
    val playbackDiagnosticsEnabled: Boolean = false,
    /**
     * The session's [ViewerSession.series], null for any session that is not a series'. A series' entries are never
     * collected: the top bar names the series and entry in place of the source, Like and Save are hidden, and the info
     * sheet holds only Start over. [ViewerSeries.Reading] adds the chapter rules (the series' reading mode, the
     * chapter strip, reading on into the next chapter); [ViewerSeries.Watching] the episode rules.
     */
    val series: ViewerSeries? = null,
)

/** What the Viewer asks of the app: navigation, persistence and platform behavior. The app builds one per shell. */
data class ViewerScreenCallbacks(
    val onPlaybackModeSelected: (ViewerPlaybackMode) -> Unit = {},
    /** A quality picked from the playback menu, which becomes the default video quality. */
    val onVideoQualitySelected: (VideoQuality) -> Unit = {},
    val onCommentOrderSelected: (CommentOrder) -> Unit = {},
    val onGalleryReadingModeSelected: (PostId, GalleryReadingMode) -> Unit = { _, _ -> },
    val onVisiblePostChanged: suspend (Post, Int, ViewerSession) -> Unit = { _, _, _ -> },
    val onVisibleMediaChanged: suspend (Post, Int, ViewerSession) -> Unit = { _, _, _ -> },
    /** An episode's playback position in a [ViewerSeries.Watching] session, for its series' progress. */
    val onPlaybackProgress: suspend (Post, ViewerPlaybackProgress, ViewerSession) -> Unit = { _, _, _ -> },
    val onOpenUrl: (String) -> Unit = {},
    val onGoToSearch: () -> Unit,
    val onDismissRelatedPosts: (ViewerStreamSource) -> Unit = {},
    val onRetryRelatedPosts: (ViewerStreamSource) -> Unit = {},
    val onShowRelatedPosts: (ViewerStreamSource, Post) -> Unit = { _, _ -> },
    val onRemoveFromCodex: (codexId: String, Post) -> Unit = { _, _ -> },
    val onOpenCreatorFallback: ((Post) -> Unit)? = null,
)

/**
 * The host's side of one Viewer screen: the owner's [onAction] and the callbacks bound to that owner. The route
 * remembers one per owner, so recomposing the route hands the screen the same instance.
 */
@Immutable
class ViewerScreenHost(
    val onAction: (ViewerAction) -> Unit,
    val onGoToSearch: () -> Unit = {},
    val onOpenCreatorFallback: ((Post) -> Unit)? = null,
    val onGalleryReadingModeSelected: (PostId, GalleryReadingMode) -> Unit = { _, _ -> },
    val onRequestMediaRecovery: ((Post, ImageRef) -> Unit)? = null,
    val onVisiblePostChanged: ((Post, Int) -> Unit)? = null,
    val onVisibleMediaChanged: ((Post, Int) -> Unit)? = null,
    /**
     * Where an episode's playback is, in a [ViewerSeries.Watching] session: its player reports here while it plays,
     * often enough that a killed process resumes close to where it stopped, and once more when it lets the episode go.
     * Other sessions never report.
     */
    val onPlaybackProgress: ((Post, ViewerPlaybackProgress) -> Unit)? = null,
    val onVideoQualitySelected: (VideoQuality) -> Unit = {},
    val durations: ViewerPostDurations = ViewerPostDurations.None,
)
