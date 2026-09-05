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
                    warmed = if (action.result.outcome == ViewerPrefetchOutcome.WARMED) {
                        prefetch.warmed + (action.mediaKey to action.result.bytesCached)
                    } else prefetch.warmed,
                    skipped = if (action.result.outcome == ViewerPrefetchOutcome.SKIPPED) {
                        prefetch.skipped + action.mediaKey
                    } else prefetch.skipped,
                    failed = if (action.result.outcome == ViewerPrefetchOutcome.FAILED) {
                        prefetch.failed + action.mediaKey
                    } else prefetch.failed,
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
        is ViewerAction.IncludeTag -> state.currentPageEffect { page, _ ->
            ViewerEffect.ApplyTag(
                session = session,
                postId = page.post.id,
                term = action.term,
                excluded = false,
            )
        }
        is ViewerAction.ExcludeTag -> state.currentPageEffect { page, _ ->
            ViewerEffect.ApplyTag(
                session = session,
                postId = page.post.id,
                term = action.term,
                excluded = true,
            )
        }
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
    return copy(
        controls = controls.copy(
            playback = playback.copy(
                playing = true,
                restartRequest = playback.restartRequest + 1L,
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

private fun ViewerUiState.requestResolution(session: ViewerSessionIdentity): ViewerReduction {
    val page = currentPage ?: return ViewerReduction(this)
    if (page.resolution.status !in setOf(ViewerResolutionStatus.IDLE, ViewerResolutionStatus.FAILED)) {
        return ViewerReduction(this)
    }
    val updated = updateResolution(page.post.id) { current ->
        current.copy(
            status = ViewerResolutionStatus.REQUESTED,
            message = null,
            recoverable = true,
        )
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
            recoverable = true,
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
        current.copy(
            status = ViewerResolutionStatus.FAILED,
            message = action.message,
            recoverable = action.recoverable,
        )
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
    val terminal = prefetch.warmed.keys + prefetch.skipped + prefetch.failed
    val desired = requested.asSequence()
        .filter { key -> key in known }
        .filterNot { key -> key in terminal }
        .distinct()
        .toList()
    return ViewerReduction(
        state = copy(
            prefetch = prefetch.copy(
                queued = desired.toSet() - prefetch.inFlight,
                inFlight = prefetch.inFlight.intersect(desired.toSet()),
            ),
        ),
        effects = listOf(ViewerEffect.PrefetchMedia(session, desired)),
    )
}

private fun ViewerUiState.retryMedia(session: ViewerSessionIdentity): ViewerReduction {
    val recoverable = mediaError as? ViewerMediaError.Recoverable ?: return ViewerReduction(this)
    val pageIndex = pages.indexOfFirst { page -> page.post.id == recoverable.mediaKey.postId }
    if (pageIndex < 0) return ViewerReduction(this)
    val page = pages[pageIndex]
    val mediaIndex = page.media.indexOfFirst { media -> media.key == recoverable.mediaKey }
    if (mediaIndex < 0) return ViewerReduction(this)
    val retriedMedia = page.media[mediaIndex].copy(
        loadGeneration = page.media[mediaIndex].loadGeneration + 1L,
    )
    val updatedMedia = page.media.toMutableList().also { media -> media[mediaIndex] = retriedMedia }
    val updatedPages = pages.toMutableList().also { currentPages ->
        currentPages[pageIndex] = page.copy(media = updatedMedia)
    }
    return ViewerReduction(
        state = copy(
            pages = updatedPages,
            mediaError = null,
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
    val available = when (media?.kind) {
        ViewerMediaKind.ANIMATED_WEBP,
        ViewerMediaKind.VIDEO,
        ViewerMediaKind.GIF,
        ViewerMediaKind.ANIMATED_IMAGE,
        ViewerMediaKind.UGOIRA,
        -> true
        ViewerMediaKind.IMAGE,
        ViewerMediaKind.UNKNOWN,
        null,
        -> false
    }
    return ViewerPlaybackControlsState(
        available = available,
        playing = available,
        playbackRate = playbackRate,
    )
}

private fun overviewFor(
    page: ViewerPageState?,
    visible: Boolean = false,
): ViewerOverviewState {
    val available = (page?.media?.size ?: 0) > 1
    return ViewerOverviewState(visible = visible && available, available = available)
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
