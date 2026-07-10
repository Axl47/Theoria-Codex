package com.theoriacodex.app.viewer.state

import com.theoriacodex.app.media.PostMediaKind
import com.theoriacodex.app.media.isAnimatedImageMediaRef
import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.isPixivUgoiraMedia
import com.theoriacodex.app.media.mediaKind
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

internal fun createViewerUiState(
    session: ViewerSessionIdentity,
    posts: List<Post>,
    initialPageIndex: Int = 0,
    requiresResolution: (Post) -> Boolean = { false },
): ViewerUiState {
    val pages = posts.map { post ->
        post.toViewerPageState(
            resolution = ViewerResolutionState(
                status = if (requiresResolution(post)) {
                    ViewerResolutionStatus.IDLE
                } else {
                    ViewerResolutionStatus.NOT_REQUIRED
                },
            ),
        )
    }
    val currentPageIndex = initialPageIndex.coerceInPages(pages)
    val currentPage = pages.getOrNull(currentPageIndex)
    return ViewerUiState(
        session = session,
        pages = pages,
        currentPageIndex = currentPageIndex,
        controls = ViewerControlsState(
            playback = playbackControlsFor(currentPage?.selectedMedia),
        ),
        overview = overviewFor(currentPage),
    )
}

internal fun Post.toViewerPageState(
    selectedMediaIndex: Int = 0,
    resolution: ViewerResolutionState = ViewerResolutionState(),
): ViewerPageState {
    val mappedMedia = postMediaItems(this).mapIndexed { index, ref ->
        ref.toViewerMediaState(post = this, mediaIndex = index)
    }
    val safeMediaIndex = selectedMediaIndex.coerceInMedia(mappedMedia)
    return ViewerPageState(
        post = copy(
            media = media.toList(),
            canonicalTags = canonicalTags.toList(),
            rawTags = rawTags.toList(),
            taxonomy = taxonomy.toList(),
            creatorProfiles = creatorProfiles.toList(),
        ),
        media = mappedMedia,
        selectedMediaIndex = safeMediaIndex,
        resolution = resolution,
        metadata = ViewerMetadataState(
            title = title,
            authorName = authorName,
            creators = creatorProfiles.toList(),
            taxonomy = taxonomy.toList(),
            width = width,
            height = height,
            durationMs = durationMs,
            pageUrl = pageUrl,
        ),
    )
}

internal fun ImageRef.toViewerMediaState(post: Post, mediaIndex: Int): ViewerMediaState {
    val kind = viewerMediaKind(post, this)
    val location = bestViewerLocation()
    return ViewerMediaState(
        key = ViewerMediaKey(postId = post.id, mediaIndex = mediaIndex),
        ref = copy(progressiveUrls = progressiveUrls.toList()),
        kind = kind,
        displayLocation = location,
        downloadable = !url.isNullOrBlank() || !localPath.isNullOrBlank(),
        shareable = location != null || !post.pageUrl.isNullOrBlank(),
    )
}

internal fun viewerMediaKind(post: Post, media: ImageRef): ViewerMediaKind {
    if (isPixivUgoiraMedia(post, media)) return ViewerMediaKind.UGOIRA
    return when (mediaKind(media)) {
        PostMediaKind.VIDEO -> ViewerMediaKind.VIDEO
        PostMediaKind.UGOIRA -> ViewerMediaKind.UGOIRA
        PostMediaKind.UNKNOWN -> ViewerMediaKind.UNKNOWN
        PostMediaKind.IMAGE -> when {
            isGifMediaRef(media) -> ViewerMediaKind.GIF
            isAnimatedImageMediaRef(media) && media.isWebP() -> ViewerMediaKind.ANIMATED_WEBP
            isAnimatedImageMediaRef(media) -> ViewerMediaKind.ANIMATED_IMAGE
            else -> ViewerMediaKind.IMAGE
        }
    }
}

internal fun reduceViewerState(state: ViewerUiState, action: ViewerAction): ViewerReduction {
    if (action is ViewerAction.ReplaceSession) {
        return ViewerReduction(
            createViewerUiState(
                session = action.session,
                posts = action.posts,
                initialPageIndex = action.initialPageIndex,
                requiresResolution = { post -> post.id in action.resolutionRequiredPostIds },
            ),
        )
    }

    val session = state.session ?: return ViewerReduction(state)
    return when (action) {
        is ViewerAction.ReplaceSession -> error("Handled above")
        is ViewerAction.SelectPage -> state.selectPage(action.pageIndex)
        is ViewerAction.SelectMedia -> state.selectMedia(action.mediaIndex)
        is ViewerAction.SelectOverviewMedia -> state.selectMedia(action.mediaIndex)
        ViewerAction.ToggleChrome -> ViewerReduction(state.toggleChrome())
        ViewerAction.ToggleOverview -> ViewerReduction(
            state.copy(
                overview = state.overview.copy(
                    visible = state.overview.available && !state.overview.visible,
                ),
            ),
        )
        ViewerAction.ShowMetadata -> ViewerReduction(
            state.copy(controls = state.controls.copy(metadataVisible = true)),
        )
        ViewerAction.HideMetadata -> ViewerReduction(
            state.copy(controls = state.controls.copy(metadataVisible = false)),
        )
        ViewerAction.ShowActionsMenu -> ViewerReduction(
            state.copy(controls = state.controls.copy(actionsMenuVisible = true)),
        )
        ViewerAction.HideActionsMenu -> ViewerReduction(
            state.copy(controls = state.controls.copy(actionsMenuVisible = false)),
        )
        ViewerAction.ShowPlaybackSettings -> ViewerReduction(
            state.copy(
                controls = state.controls.copy(
                    playbackSettingsVisible = state.controls.playback.available,
                ),
            ),
        )
        ViewerAction.HidePlaybackSettings -> ViewerReduction(
            state.copy(controls = state.controls.copy(playbackSettingsVisible = false)),
        )
        ViewerAction.Play -> ViewerReduction(state.setPlaying(true))
        ViewerAction.Pause -> ViewerReduction(state.setPlaying(false))
        ViewerAction.TogglePlayback -> ViewerReduction(
            state.setPlaying(!state.controls.playback.playing),
        )
        ViewerAction.RestartPlayback -> ViewerReduction(state.restartPlayback())
        is ViewerAction.SetPlaybackRate -> ViewerReduction(state.setPlaybackRate(action.rate))
        is ViewerAction.TimelineProgressChanged -> ViewerReduction(
            state.updateTimelineProgress(action.positionMs, action.durationMs),
        )
        is ViewerAction.FrameProgressChanged -> ViewerReduction(
            state.updateFrameProgress(action.frameIndex, action.frameCount),
        )
        ViewerAction.RequestCurrentPageResolution -> state.requestResolution(session)
        is ViewerAction.ResolutionStarted -> state.ifCurrentSession(action.session) {
            updateResolution(action.postId) { current ->
                current.copy(
                    status = ViewerResolutionStatus.RESOLVING,
                    attempt = current.attempt + 1,
                    message = null,
                )
            }
        }
        is ViewerAction.ResolutionCompleted -> state.ifCurrentSession(action.session) {
            replaceResolvedPost(action.post)
        }
        is ViewerAction.ResolutionFailed -> state.ifCurrentSession(action.session) {
            resolutionFailed(action)
        }
        is ViewerAction.QueuePrefetch -> state.queuePrefetch(session, action.mediaKeys)
        is ViewerAction.PrefetchStarted -> state.ifCurrentSession(action.session) {
            copy(
                prefetch = prefetch.copy(
                    queued = prefetch.queued - action.mediaKey,
                    inFlight = prefetch.inFlight + action.mediaKey,
                ),
            )
        }
        is ViewerAction.PrefetchCompleted -> state.ifCurrentSession(action.session) {
            copy(
                prefetch = prefetch.copy(
                    queued = prefetch.queued - action.mediaKey,
                    inFlight = prefetch.inFlight - action.mediaKey,
                    ready = if (action.available) prefetch.ready + action.mediaKey else prefetch.ready,
                    unavailable = if (action.available) {
                        prefetch.unavailable - action.mediaKey
                    } else {
                        prefetch.unavailable + action.mediaKey
                    },
                ),
            )
        }
        is ViewerAction.MediaFailed -> state.ifCurrentSession(action.session) {
            val failedMediaKey = action.error.mediaKey
            if (failedMediaKey != null && failedMediaKey != currentMedia?.key) {
                this
            } else {
                copy(
                    mediaError = action.error,
                    controls = controls.copy(
                        playback = controls.playback.copy(playing = false),
                    ),
                )
            }
        }
        ViewerAction.ClearMediaError -> ViewerReduction(state.copy(mediaError = null))
        ViewerAction.RetryMedia -> state.retryMedia(session)
        ViewerAction.Save -> state.currentPageEffect { page, _ ->
            ViewerEffect.SavePost(session = session, postId = page.post.id)
        }
        ViewerAction.Share -> state.currentPageEffect { page, media ->
            ViewerEffect.ShareMedia(session = session, postId = page.post.id, mediaKey = media.key)
        }
        ViewerAction.Download -> state.currentPageEffect { page, media ->
            ViewerEffect.DownloadMedia(session = session, postId = page.post.id, mediaKey = media.key)
        }
        ViewerAction.ToggleLike -> state.currentPageEffect { page, _ ->
            ViewerEffect.SetLiked(session = session, postId = page.post.id)
        }
        is ViewerAction.OpenCreator -> ViewerReduction(
            state,
            listOf(ViewerEffect.OpenCreatorProfile(session, action.creator)),
        )
        is ViewerAction.IncludeTag -> ViewerReduction(
            state,
            listOf(ViewerEffect.ApplyTag(session, action.term, excluded = false)),
        )
        is ViewerAction.ExcludeTag -> ViewerReduction(
            state,
            listOf(ViewerEffect.ApplyTag(session, action.term, excluded = true)),
        )
        ViewerAction.LoadMore -> ViewerReduction(state, listOf(ViewerEffect.LoadMore(session)))
        ViewerAction.Dismiss -> ViewerReduction(state, listOf(ViewerEffect.Dismiss(session)))
    }
}

private fun ViewerUiState.selectPage(pageIndex: Int): ViewerReduction {
    val target = pageIndex.coerceInPages(pages)
    val page = pages.getOrNull(target)
    return ViewerReduction(
        copy(
            currentPageIndex = target,
            controls = controls.copy(
                actionsMenuVisible = false,
                playbackSettingsVisible = false,
                metadataVisible = false,
                playback = playbackControlsFor(
                    media = page?.selectedMedia,
                    playbackRate = controls.playback.playbackRate,
                ),
            ),
            overview = overviewFor(page),
            mediaError = null,
        ),
    )
}

private fun ViewerUiState.selectMedia(mediaIndex: Int): ViewerReduction {
    val page = currentPage ?: return ViewerReduction(this)
    val selected = mediaIndex.coerceInMedia(page.media)
    val updatedPage = page.copy(selectedMediaIndex = selected)
    val updatedPages = pages.toMutableList().also { values -> values[currentPageIndex] = updatedPage }
    return ViewerReduction(
        copy(
            pages = updatedPages,
            controls = controls.copy(
                actionsMenuVisible = false,
                playbackSettingsVisible = false,
                playback = playbackControlsFor(
                    media = updatedPage.selectedMedia,
                    playbackRate = controls.playback.playbackRate,
                ),
            ),
            overview = overviewFor(updatedPage),
            mediaError = null,
        ),
    )
}

private fun ViewerUiState.toggleChrome(): ViewerUiState {
    val visible = !controls.chromeVisible
    return copy(
        controls = controls.copy(
            chromeVisible = visible,
            actionsMenuVisible = controls.actionsMenuVisible && visible,
            playbackSettingsVisible = controls.playbackSettingsVisible && visible,
        ),
    )
}

private fun ViewerUiState.setPlaying(playing: Boolean): ViewerUiState {
    if (!controls.playback.available) return this
    return copy(
        controls = controls.copy(
            playback = controls.playback.copy(playing = playing),
        ),
    )
}

private fun ViewerUiState.restartPlayback(): ViewerUiState {
    val playback = controls.playback
    if (!playback.available) return this
    val resetProgress = when (val progress = playback.progress) {
        ViewerPlaybackProgress.None -> ViewerPlaybackProgress.None
        is ViewerPlaybackProgress.Timeline -> progress.copy(positionMs = 0L)
        is ViewerPlaybackProgress.Frames -> progress.copy(frameIndex = 0)
    }
    return copy(
        controls = controls.copy(
            playback = playback.copy(
                playing = true,
                restartRequest = playback.restartRequest + 1L,
                progress = resetProgress,
            ),
        ),
    )
}

private fun ViewerUiState.setPlaybackRate(rate: Float): ViewerUiState {
    if (!controls.playback.available || rate <= 0f) return this
    return copy(
        controls = controls.copy(playback = controls.playback.copy(playbackRate = rate)),
    )
}

private fun ViewerUiState.updateTimelineProgress(positionMs: Long, durationMs: Long?): ViewerUiState {
    if (controls.playback.progress !is ViewerPlaybackProgress.Timeline) return this
    val safeDuration = durationMs?.coerceAtLeast(0L)
    val safePosition = positionMs.coerceAtLeast(0L).let { value ->
        safeDuration?.let(value::coerceAtMost) ?: value
    }
    return copy(
        controls = controls.copy(
            playback = controls.playback.copy(
                progress = ViewerPlaybackProgress.Timeline(safePosition, safeDuration),
            ),
        ),
    )
}

private fun ViewerUiState.updateFrameProgress(frameIndex: Int, frameCount: Int): ViewerUiState {
    if (controls.playback.progress !is ViewerPlaybackProgress.Frames) return this
    val safeCount = frameCount.coerceAtLeast(0)
    val safeIndex = if (safeCount == 0) 0 else frameIndex.coerceIn(0, safeCount - 1)
    return copy(
        controls = controls.copy(
            playback = controls.playback.copy(
                progress = ViewerPlaybackProgress.Frames(safeIndex, safeCount),
            ),
        ),
    )
}

private fun ViewerUiState.requestResolution(session: ViewerSessionIdentity): ViewerReduction {
    val page = currentPage ?: return ViewerReduction(this)
    if (page.resolution.status !in setOf(ViewerResolutionStatus.IDLE, ViewerResolutionStatus.FAILED)) {
        return ViewerReduction(this)
    }
    val updated = updateResolution(page.post.id) { current ->
        current.copy(status = ViewerResolutionStatus.REQUESTED, message = null)
    }
    return ViewerReduction(
        state = updated,
        effects = listOf(ViewerEffect.ResolvePost(session, page.post.id)),
    )
}

private fun ViewerUiState.replaceResolvedPost(post: Post): ViewerUiState {
    val pageIndex = pages.indexOfFirst { page -> page.post.id == post.id }
    if (pageIndex < 0) return this
    val previous = pages[pageIndex]
    val replacement = post.toViewerPageState(
        selectedMediaIndex = previous.selectedMediaIndex,
        resolution = previous.resolution.copy(
            status = ViewerResolutionStatus.RESOLVED,
            message = null,
        ),
    )
    val updatedPages = pages.toMutableList().also { values -> values[pageIndex] = replacement }
    if (pageIndex != currentPageIndex) return copy(pages = updatedPages)
    return copy(
        pages = updatedPages,
        controls = controls.copy(
            playback = playbackControlsFor(
                media = replacement.selectedMedia,
                playbackRate = controls.playback.playbackRate,
            ),
        ),
        overview = overviewFor(replacement, visible = overview.visible),
        mediaError = null,
    )
}

private fun ViewerUiState.resolutionFailed(action: ViewerAction.ResolutionFailed): ViewerUiState {
    val updated = updateResolution(action.postId) { current ->
        current.copy(status = ViewerResolutionStatus.FAILED, message = action.message)
    }
    if (action.postId != currentPage?.post?.id) return updated

    val failedPage = updated.pages.firstOrNull { page -> page.post.id == action.postId }
    val mediaKey = failedPage?.selectedMedia?.key
    val error = if (action.recoverable && mediaKey != null) {
        ViewerMediaError.Recoverable(
            mediaKey = mediaKey,
            message = action.message,
            retryCount = failedPage.resolution.attempt,
        )
    } else {
        ViewerMediaError.Fatal(mediaKey = mediaKey, message = action.message)
    }
    return updated.copy(
        mediaError = error,
        controls = updated.controls.copy(
            playback = updated.controls.playback.copy(playing = false),
        ),
    )
}

private fun ViewerUiState.updateResolution(
    postId: PostId,
    update: (ViewerResolutionState) -> ViewerResolutionState,
): ViewerUiState {
    val pageIndex = pages.indexOfFirst { page -> page.post.id == postId }
    if (pageIndex < 0) return this
    val updatedPages = pages.toMutableList()
    val page = updatedPages[pageIndex]
    updatedPages[pageIndex] = page.copy(resolution = update(page.resolution))
    return copy(pages = updatedPages)
}

private fun ViewerUiState.queuePrefetch(
    session: ViewerSessionIdentity,
    requested: List<ViewerMediaKey>,
): ViewerReduction {
    val known = pages.flatMap { page -> page.media.map(ViewerMediaState::key) }.toSet()
    val pending = requested.asSequence()
        .filter { key -> key in known }
        .filterNot { key -> key in prefetch.queued || key in prefetch.inFlight || key in prefetch.ready }
        .distinct()
        .toList()
    if (pending.isEmpty()) return ViewerReduction(this)
    return ViewerReduction(
        state = copy(prefetch = prefetch.copy(queued = prefetch.queued + pending)),
        effects = listOf(ViewerEffect.PrefetchMedia(session, pending)),
    )
}

private fun ViewerUiState.retryMedia(session: ViewerSessionIdentity): ViewerReduction {
    val recoverable = mediaError as? ViewerMediaError.Recoverable ?: return ViewerReduction(this)
    return ViewerReduction(
        state = copy(
            mediaError = recoverable.copy(retryCount = recoverable.retryCount + 1),
        ),
        effects = listOf(ViewerEffect.RetryMedia(session, recoverable.mediaKey)),
    )
}

private inline fun ViewerUiState.currentPageEffect(
    effect: (ViewerPageState, ViewerMediaState) -> ViewerEffect,
): ViewerReduction {
    val page = currentPage ?: return ViewerReduction(this)
    val media = page.selectedMedia ?: return ViewerReduction(this)
    return ViewerReduction(this, listOf(effect(page, media)))
}

private inline fun ViewerUiState.ifCurrentSession(
    eventSession: ViewerSessionIdentity,
    update: ViewerUiState.() -> ViewerUiState,
): ViewerReduction {
    return if (eventSession == session) ViewerReduction(update()) else ViewerReduction(this)
}

private fun playbackControlsFor(
    media: ViewerMediaState?,
    playbackRate: Float = 1f,
): ViewerPlaybackControlsState {
    val progress = when (media?.kind) {
        ViewerMediaKind.ANIMATED_WEBP -> ViewerPlaybackProgress.Frames()
        ViewerMediaKind.VIDEO,
        ViewerMediaKind.GIF,
        ViewerMediaKind.ANIMATED_IMAGE,
        ViewerMediaKind.UGOIRA,
        -> ViewerPlaybackProgress.Timeline()
        ViewerMediaKind.IMAGE,
        ViewerMediaKind.UNKNOWN,
        null,
        -> ViewerPlaybackProgress.None
    }
    val available = progress !is ViewerPlaybackProgress.None
    return ViewerPlaybackControlsState(
        available = available,
        playing = available,
        playbackRate = playbackRate,
        progress = progress,
    )
}

private fun overviewFor(
    page: ViewerPageState?,
    visible: Boolean = false,
): ViewerOverviewState {
    if (page == null) return ViewerOverviewState()
    val items = page.media.map { media ->
        ViewerOverviewItemState(
            mediaKey = media.key,
            kind = media.kind,
            posterLocation = when (media.kind) {
                ViewerMediaKind.IMAGE,
                ViewerMediaKind.ANIMATED_WEBP,
                ViewerMediaKind.GIF,
                ViewerMediaKind.ANIMATED_IMAGE,
                -> media.displayLocation
                ViewerMediaKind.VIDEO,
                ViewerMediaKind.UGOIRA,
                ViewerMediaKind.UNKNOWN,
                -> page.post.preview.bestViewerLocation()
            },
            selected = media.key.mediaIndex == page.selectedMediaIndex,
        )
    }
    return ViewerOverviewState(visible = visible && items.size > 1, items = items)
}

private fun ImageRef.bestViewerLocation(): String? {
    return localPath?.takeIf(String::isNotBlank)
        ?: progressiveUrls.firstOrNull(String::isNotBlank)
        ?: url?.takeIf(String::isNotBlank)
}

private fun ImageRef.isWebP(): Boolean {
    if (mime?.substringBefore(';')?.trim()?.equals("image/webp", ignoreCase = true) == true) return true
    return buildList {
        localPath?.let(::add)
        addAll(progressiveUrls)
        url?.let(::add)
    }.any { location ->
        location.substringBefore('?').substringBefore('#').endsWith(".webp", ignoreCase = true)
    }
}

private fun Int.coerceInPages(pages: List<ViewerPageState>): Int {
    return if (pages.isEmpty()) 0 else coerceIn(0, pages.lastIndex)
}

private fun Int.coerceInMedia(media: List<ViewerMediaState>): Int {
    return if (media.isEmpty()) 0 else coerceIn(0, media.lastIndex)
}
