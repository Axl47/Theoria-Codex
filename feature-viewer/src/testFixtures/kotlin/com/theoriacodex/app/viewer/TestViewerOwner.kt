package com.theoriacodex.app.viewer

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.comments.CommentsLoading
import com.theoriacodex.app.comments.UnsupportedCommentsLoader
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.ViewerPrefetchResult
import com.theoriacodex.domain.model.ViewerPlaybackMode
import kotlinx.coroutines.CoroutineScope

fun testViewerOwner(
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
    postResolver: ViewerPostResolver = ViewerPostResolver { _, _ -> null },
    mediaPrefetcher: ViewerMediaPrefetcher = ViewerMediaPrefetcher { _, _, _ ->
        ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED)
    },
    scopeOverride: CoroutineScope? = null,
    playbackModeProvider: () -> ViewerPlaybackMode = { ViewerPlaybackMode.LOOP },
    commentsLoader: CommentsLoading = UnsupportedCommentsLoader,
): ViewerViewModel = ViewerViewModel(
    savedStateHandle = savedStateHandle,
    postResolver = postResolver,
    mediaPrefetcher = mediaPrefetcher,
    playbackModeProvider = playbackModeProvider,
    scopeOverride = scopeOverride,
    commentsLoader = commentsLoader,
)
