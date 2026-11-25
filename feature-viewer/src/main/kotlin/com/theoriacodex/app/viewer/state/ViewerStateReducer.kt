package com.theoriacodex.app.viewer.state

import com.theoriacodex.app.media.PostMediaKind
import com.theoriacodex.app.viewer.ViewerSeries
import com.theoriacodex.app.viewer.hasPendingGalleryMedia
import com.theoriacodex.app.media.isAnimatedImageMediaRef
import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.isPixivUgoiraMedia
import com.theoriacodex.app.media.mediaKind
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

fun createViewerUiState(
    session: ViewerSessionIdentity,
    posts: List<Post>,
    initialPageIndex: Int = 0,
    requiresResolution: (Post) -> Boolean = { false },
    initialMediaIndices: Map<PostId, Int> = emptyMap(),
    series: ViewerSeries? = null,
): ViewerUiState {
    val pages = posts.map { post -> post.toNewViewerPageState(requiresResolution, initialMediaIndices) }
    val currentPageIndex = initialPageIndex.coerceInPages(pages)
    val currentPage = pages.getOrNull(currentPageIndex)
    return ViewerUiState(
        session = session,
        series = series,
        pages = pages,
        currentPageIndex = currentPageIndex,
        controls = ViewerControlsState(
            playback = playbackControlsFor(currentPage?.selectedMedia),
        ),
        overview = overviewFor(currentPage),
    )
}

private fun Post.toNewViewerPageState(
    requiresResolution: (Post) -> Boolean,
    initialMediaIndices: Map<PostId, Int>,
): ViewerPageState {
    val requested = initialMediaIndices[id] ?: 0
    val resolves = requiresResolution(this)
    val page = toViewerPageState(
        selectedMediaIndex = requested,
        resolution = ViewerResolutionState(
            status = if (resolves) ViewerResolutionStatus.IDLE else ViewerResolutionStatus.NOT_REQUIRED,
        ),
    )
    return if (resolves && requested > page.media.lastIndex) page.copy(pendingMediaIndex = requested) else page
}

fun Post.toViewerPageState(
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
        pendingMediaIndex = selectedMediaIndex.takeIf {
            it > mappedMedia.lastIndex && resolution.status != ViewerResolutionStatus.NOT_REQUIRED &&
                resolution.status != ViewerResolutionStatus.RESOLVED
        },
        resolution = resolution,
    )
}

fun ImageRef.toViewerMediaState(post: Post, mediaIndex: Int): ViewerMediaState {
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

fun reduceViewerState(state: ViewerUiState, action: ViewerAction): ViewerReduction {
    if (action is ViewerAction.ReplaceSession) return state.replaceSession(action)

    val session = state.session ?: return ViewerReduction(state)
    return when (action) {
        is ViewerAction.ReplaceSession -> error("Handled above")
        is ViewerAction.AppendPosts -> state.ifCurrentSession(action.session) { appendPosts(action) }
        is ViewerAction.IncludeRelatedPosts -> state.ifCurrentSession(action.session) { includeRelatedPosts(action) }
        is ViewerAction.SelectPage -> state.selectPage(action.pageIndex)
        is ViewerAction.SelectMedia -> state.selectMedia(action.mediaIndex)
        is ViewerAction.Navigate -> state.navigate(action.pageIndex, action.mediaIndex)
        ViewerAction.ToggleChrome -> ViewerReduction(state.toggleChrome())
        is ViewerAction.SetChromeVisible ->
            ViewerReduction(if (action.visible == state.controls.chromeVisible) state else state.toggleChrome())
        ViewerAction.ToggleOverview -> state.reduceToggleOverview()
        is ViewerAction.ShowPanel -> ViewerReduction(state.showPanel(action.panel))
        is ViewerAction.HidePanel -> ViewerReduction(state.hidePanel(action.panel))
        is ViewerAction.SetPlaybackMode -> state.withControls(state.controls.copy(playbackMode = action.mode))
        is ViewerAction.PlaybackCompleted -> state.completePlayback(action)
        is ViewerAction.SetSlideshow -> ViewerReduction(state.setSlideshow(action.running))
        is ViewerAction.SlideshowAdvance -> state.advanceSlideshow(action)
        ViewerAction.Play -> ViewerReduction(state.setPlaying(true))
        ViewerAction.Pause -> ViewerReduction(state.setPlaying(false))
        ViewerAction.TogglePlayback -> ViewerReduction(state.setPlaying(!state.controls.playback.playing))
        ViewerAction.RestartPlayback -> ViewerReduction(state.restartPlayback())
        is ViewerAction.SetPlaybackRate -> ViewerReduction(state.setPlaybackRate(action.rate))
        is ViewerAction.ChooseStream,
        is ViewerAction.FallBackToStream,
        is ViewerAction.ChooseSubtitle,
        is ViewerAction.ChooseAudio,
        is ViewerAction.SetLocked,
        ViewerAction.ToggleFillScreen -> ViewerReduction(state.reduceEpisodeAction(action))
        is ViewerAction.RequestPageResolution -> state.requestResolution(session, action.postId)
        is ViewerAction.ResolutionCancelled,
        is ViewerAction.ResolutionStarted,
        is ViewerAction.ResolutionCompleted,
        is ViewerAction.ResolutionFailed -> state.reduceResolution(action)
        is ViewerAction.QueuePrefetch -> ViewerReduction(state)
        is ViewerAction.MediaFailed -> state.reduceMediaFailed(action)
        is ViewerAction.MediaReady -> state.reduceMediaReady(action)
        ViewerAction.RetryMedia -> state.retryMedia(session)
        ViewerAction.Save -> state.currentPageEffect { page, _ ->
            ViewerEffect.SavePost(session = session, postId = page.post.id)
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
        is ViewerAction.IncludeTag -> state.reduceIncludeTag(session, action)
        is ViewerAction.ExcludeTag -> state.reduceExcludeTag(session, action)
        ViewerAction.LoadMore -> ViewerReduction(state, listOf(ViewerEffect.LoadMore(session)))
        ViewerAction.PrepareDismiss -> ViewerReduction(state.prepareDismiss())
        ViewerAction.Dismiss -> ViewerReduction(state, listOf(ViewerEffect.Dismiss(session)))
    }
}

private fun ViewerUiState.withControls(controls: ViewerControlsState): ViewerReduction =
    ViewerReduction(copy(controls = controls))

private fun ViewerUiState.reduceEpisodeAction(action: ViewerAction): ViewerUiState = when (action) {
    is ViewerAction.ChooseStream -> chooseStream(action.label)
    is ViewerAction.FallBackToStream -> copy(controls = controls.copy(
        episode = controls.episode.let { it.copy(episodeStreams = it.episodeStreams + (action.media to ViewerEpisodeStream(action.label, pickedByHand = false))) },
    ))
    is ViewerAction.ChooseSubtitle -> chooseEpisodeTracks { copy(subtitle = action.choice) }
    is ViewerAction.ChooseAudio -> chooseEpisodeTracks { copy(audioLabel = action.label) }
    is ViewerAction.SetLocked -> setLocked(action.locked)
    ViewerAction.ToggleFillScreen -> if (series is ViewerSeries.Watching) {
        copy(controls = controls.copy(fillScreen = !controls.fillScreen))
    } else this
    else -> error("Not an episode action: $action")
}

private fun ViewerUiState.chooseEpisodeTracks(change: ViewerEpisodeChoice.() -> ViewerEpisodeChoice): ViewerUiState =
    copy(controls = controls.copy(episode = controls.episode.change(), panel = controls.panel?.takeUnless(ViewerPanel::inChrome)))

private fun ViewerUiState.setLocked(locked: Boolean): ViewerUiState {
    if (locked && series !is ViewerSeries.Watching) return this
    return if (locked) {
        copy(controls = controls.copy(locked = true, panel = controls.panelOutsideChrome()))
    } else {
        copy(controls = controls.copy(locked = false, chromeVisible = true))
    }
}

private fun ViewerUiState.chooseStream(label: String): ViewerUiState {
    val picked = currentMedia?.key?.let { mapOf(it to ViewerEpisodeStream(label, pickedByHand = true)) }.orEmpty()
    val chosen = chooseEpisodeTracks { copy(streamLabel = label, episodeStreams = picked) }
    val failedHere = mediaError != null && mediaError.mediaKey.let { it == null || it == currentMedia?.key }
    return chosen.copy(
        mediaError = mediaError.takeUnless { failedHere },
        controls = chosen.controls.copy(playback = chosen.controls.playback.copy(playing = chosen.controls.playback.available)),
    )
}

private fun ViewerUiState.replaceSession(action: ViewerAction.ReplaceSession): ViewerReduction {
    return ViewerReduction(
        createViewerUiState(
            session = action.session,
            posts = action.posts,
            initialPageIndex = action.initialPageIndex,
            requiresResolution = { post -> post.id in action.resolutionRequiredPostIds },
            series = action.series,
            initialMediaIndices = action.initialMediaIndices + if (session == action.session) {
                pages.associate { it.post.id to it.targetMediaIndex }
            } else {
                emptyMap()
            },
        ).let { replacement ->
            replacement.copy(
                controls = replacement.controls.copy(
                    playbackMode = controls.playbackMode,
                    chromeVisible = if (session == action.session) controls.chromeVisible else replacement.controls.chromeVisible,
                    slideshow = session == action.session && controls.slideshow,
                    locked = session == action.session && controls.locked && action.series is ViewerSeries.Watching,
                    fillScreen = session == action.session && controls.fillScreen && action.series is ViewerSeries.Watching,
                    episode = if (session == action.session) controls.episode else ViewerEpisodeChoice(),
                ),
                related = if (session == action.session) related else ViewerRelatedBranch(),
            )
        },
    )
}

private fun ViewerUiState.appendPosts(action: ViewerAction.AppendPosts): ViewerUiState = copy(
    pages = pages + action.posts.map { post ->
        post.toNewViewerPageState({ it.id in action.resolutionRequiredPostIds }, action.initialMediaIndices)
    },
)

private fun ViewerUiState.includeRelatedPosts(action: ViewerAction.IncludeRelatedPosts): ViewerUiState {
    val originalIds = pages.mapNotNullTo(mutableSetOf()) { page -> page.post.id.takeUnless { it in related.extraPostIds } }
    val navigation = related.navigation
    val anchor = action.seedId.takeIf { it in originalIds } ?: navigation?.anchor ?: return this
    val prefix = navigation?.postIds.orEmpty().let { ids ->
        if (action.seedId in originalIds) emptyList() else ids.take(ids.indexOf(action.seedId) + 1)
    }
    val branchIds = (prefix + action.posts.map { it.id }).filterNot { it == anchor }.distinct()
    val added = com.theoriacodex.app.viewer.newViewerPosts(pages.map { it.post }, action.posts)
    return copy(
        pages = pages + added.map { post ->
            post.toNewViewerPageState({ it.id in action.resolutionRequiredPostIds }, action.initialMediaIndices)
        },
        related = ViewerRelatedBranch(
            navigation = ViewerRelatedNavigation(anchor, branchIds),
            extraPostIds = related.extraPostIds + (action.posts.mapTo(mutableSetOf(), Post::id) - originalIds),
        ),
    )
}

private fun ViewerUiState.reduceToggleOverview(): ViewerReduction =
    ViewerReduction(
        this.copy(
            controls = controls.copy(panel = controls.panelOutsideChrome()),
            overview = this.overview.copy(
                visible = this.overview.available && !this.overview.visible,
            ),
        ),
    )

private fun ViewerUiState.showPanel(panel: ViewerPanel): ViewerUiState {
    if (panel == ViewerPanel.PLAYBACK_SETTINGS && !controls.playback.available) return hidePanel(panel)
    return copy(controls = controls.copy(panel = panel))
}

private fun ViewerUiState.hidePanel(panel: ViewerPanel): ViewerUiState =
    if (controls.panel == panel) copy(controls = controls.copy(panel = null)) else this

private fun ViewerControlsState.panelOutsideChrome(): ViewerPanel? = panel?.takeUnless(ViewerPanel::inChrome)

private fun ViewerUiState.prepareDismiss(): ViewerUiState {
    val paused = setPlaying(false)
    return paused.copy(controls = paused.controls.copy(panel = null, locked = false), overview = overview.copy(visible = false))
}

private fun ViewerUiState.reduceResolution(action: ViewerAction): ViewerReduction = when (action) {
    is ViewerAction.ResolutionCancelled -> this.ifCurrentSession(action.session) {
        updateResolution(action.postId) { it.copy(status = ViewerResolutionStatus.IDLE) }
    }
    is ViewerAction.ResolutionStarted -> this.reduceResolutionStarted(action)
    is ViewerAction.ResolutionCompleted -> this.ifCurrentSession(action.session) {
        replaceResolvedPost(action.post, action.resolvedAtMs, action.recovered)
    }
    is ViewerAction.ResolutionFailed -> this.ifCurrentSession(action.session) {
        resolutionFailed(action)
    }
    else -> error("Not a resolution action: $action")
}

private fun ViewerUiState.reduceResolutionStarted(action: ViewerAction.ResolutionStarted): ViewerReduction =
    this.ifCurrentSession(action.session) {
        updateResolution(action.postId) { current ->
            current.copy(
                status = ViewerResolutionStatus.RESOLVING,
                attempt = current.attempt + 1,
                message = null,
            )
        }
    }

private fun ViewerUiState.reduceMediaFailed(action: ViewerAction.MediaFailed): ViewerReduction =
    this.ifCurrentSession(action.session) {
        val failedMediaKey = action.error.mediaKey
        if ((failedMediaKey != null && failedMediaKey != currentMedia?.key) ||
            (action.loadGeneration != null && action.loadGeneration != currentMedia?.loadGeneration)
        ) {
            this
        } else {
            withCurrentMediaReadiness(false).copy(
                mediaError = action.error,
                controls = controls.copy(
                    playback = controls.playback.copy(playing = false),
                ),
            )
        }
    }

private fun ViewerUiState.reduceMediaReady(action: ViewerAction.MediaReady): ViewerReduction =
    this.ifCurrentSession(action.session) {
        if (currentMedia?.key == action.key && currentMedia?.loadGeneration == action.loadGeneration) {
            withCurrentMediaReadiness(true).copy(mediaError = null)
        } else this
    }

private fun ViewerUiState.reduceIncludeTag(session: ViewerSessionIdentity, action: ViewerAction.IncludeTag): ViewerReduction =
    this.currentPageEffect { page, _ ->
        ViewerEffect.ApplyTag(
            session = session,
            postId = page.post.id,
            term = action.term,
            excluded = false,
        )
    }

private fun ViewerUiState.reduceExcludeTag(session: ViewerSessionIdentity, action: ViewerAction.ExcludeTag): ViewerReduction =
    this.currentPageEffect { page, _ ->
        ViewerEffect.ApplyTag(
            session = session,
            postId = page.post.id,
            term = action.term,
            excluded = true,
        )
    }

private fun ViewerUiState.selectPage(pageIndex: Int): ViewerReduction {
    val target = pageIndex.coerceInPages(pages)
    val page = pages.getOrNull(target)
    return ViewerReduction(
        copy(
            currentPageIndex = target,
            controls = controls.copy(
                panel = null,
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

private fun ViewerUiState.navigate(pageIndex: Int, mediaIndex: Int): ViewerReduction {
    val onPage = if (pageIndex.coerceInPages(pages) == currentPageIndex) this else selectPage(pageIndex).state
    return onPage.selectMedia(mediaIndex)
}

private fun ViewerUiState.selectMedia(mediaIndex: Int): ViewerReduction {
    val page = currentPage ?: return ViewerReduction(this)
    val selected = mediaIndex.coerceInMedia(page.media)
    val updatedPage = page.copy(
        selectedMediaIndex = selected,
        pendingMediaIndex = mediaIndex.takeIf {
            it > page.media.lastIndex && page.resolution.status != ViewerResolutionStatus.NOT_REQUIRED &&
                page.resolution.status != ViewerResolutionStatus.RESOLVED
        },
    )
    val updatedPages = pages.toMutableList().also { values -> values[currentPageIndex] = updatedPage }
    return ViewerReduction(
        copy(
            pages = updatedPages,
            controls = controls.copy(
                panel = controls.panelOutsideChrome(),
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
            panel = if (visible) controls.panel else controls.panelOutsideChrome(),
        ),
    )
}

private fun ViewerUiState.completePlayback(action: ViewerAction.PlaybackCompleted): ViewerReduction {
    if (session != action.session || currentMedia?.key != action.key ||
        currentMedia?.loadGeneration != action.loadGeneration || !controls.playback.playing ||
        loopsPlayback) return ViewerReduction(this)
    if (controls.playbackMode == ViewerPlaybackMode.NEXT || controls.slideshow) advanceInQueue()?.let { return it }
    return ViewerReduction(copy(controls = controls.copy(
        playback = controls.playback.copy(playing = false, completed = true),
        slideshow = false,
    )))
}

private fun ViewerUiState.advanceInQueue(): ViewerReduction? {
    val page = currentPage ?: return null
    if (page.selectedMediaIndex < page.media.lastIndex) return selectMedia(page.selectedMediaIndex + 1)
    val navigation = com.theoriacodex.app.viewer.viewerPostNavigation(this)
    val nextIndex = navigation.sourceIndices.getOrNull(navigation.currentIndex + 1) ?: return null
    return selectPage(nextIndex).state.selectMedia(0)
}

private fun ViewerUiState.setSlideshow(running: Boolean): ViewerUiState {
    if (!running) return copy(controls = controls.copy(slideshow = false))
    val started = setPlaying(true)
    return started.copy(controls = started.controls.copy(
        slideshow = true, chromeVisible = false, panel = started.controls.panelOutsideChrome(),
    ))
}

private fun ViewerUiState.advanceSlideshow(action: ViewerAction.SlideshowAdvance): ViewerReduction {
    if (!controls.slideshow || session != action.session || currentMedia?.key != action.key) return ViewerReduction(this)
    return advanceInQueue() ?: ViewerReduction(setSlideshow(false))
}

private fun ViewerUiState.setPlaying(playing: Boolean): ViewerUiState {
    if (!controls.playback.available) return this
    if (playing && controls.playback.completed) return restartPlayback()
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
                completed = false,
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

private fun ViewerUiState.requestResolution(session: ViewerSessionIdentity, postId: PostId?): ViewerReduction {
    val page = pages.firstOrNull { it.post.id == postId } ?: return ViewerReduction(this)
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

private fun ViewerUiState.replaceResolvedPost(post: Post, resolvedAtMs: Long?, recovered: Boolean): ViewerUiState {
    val pageIndex = pages.indexOfFirst { page -> page.post.id == post.id }
    if (pageIndex < 0) return this
    val previous = pages[pageIndex]
    val resolved = post.toViewerPageState(
        selectedMediaIndex = previous.targetMediaIndex,
        resolution = previous.resolution.copy(
            status = ViewerResolutionStatus.RESOLVED,
            message = null,
            recoverable = true,
            resolvedAtMs = resolvedAtMs,
        ),
    )
    val replacement = when {
        recovered -> resolved.withLoadGenerationsAfter(previous, step = 0L)
        previous.hasPendingGalleryMedia() -> resolved.withLoadGenerationsAfter(previous, step = 1L)
        else -> resolved
    }
    val updatedPages = pages.toMutableList().also { values -> values[pageIndex] = replacement }
    if (pageIndex != currentPageIndex) return copy(pages = updatedPages)
    val playback = playbackControlsFor(media = replacement.selectedMedia, playbackRate = controls.playback.playbackRate)
    return copy(
        pages = updatedPages,
        controls = controls.copy(
            playback = if (recovered && playback.available) controls.playback.copy(available = true) else playback,
        ),
        overview = overviewFor(replacement, visible = overview.visible),
        mediaError = null,
    )
}

private fun ViewerPageState.withLoadGenerationsAfter(previous: ViewerPageState, step: Long): ViewerPageState = copy(
    media = media.map { media ->
        previous.media.firstOrNull { it.key == media.key }?.let { media.copy(loadGeneration = it.loadGeneration + step) } ?: media
    },
)

private fun ViewerUiState.withCurrentMediaReadiness(ready: Boolean): ViewerUiState {
    val page = currentPage ?: return this
    val media = currentMedia ?: return this
    if (media.ready == ready) return this
    val updatedMedia = page.media.toMutableList().also { it[page.selectedMediaIndex] = media.copy(ready = ready) }
    return copy(pages = pages.toMutableList().also { it[currentPageIndex] = page.copy(media = updatedMedia) })
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
    if (action.recoverable && failedPage?.selectedMedia?.ready == true) return updated
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
            playback = updated.controls.playback.copy(
                playing = action.recoverable && updated.controls.playback.playing,
            ),
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

private fun ViewerUiState.retryMedia(session: ViewerSessionIdentity): ViewerReduction {
    val recoverable = mediaError as? ViewerMediaError.Recoverable ?: return ViewerReduction(this)
    val pageIndex = pages.indexOfFirst { page -> page.post.id == recoverable.mediaKey.postId }
    if (pageIndex < 0) return ViewerReduction(this)
    val page = pages[pageIndex]
    val mediaIndex = page.media.indexOfFirst { media -> media.key == recoverable.mediaKey }
    if (mediaIndex < 0) return ViewerReduction(this)
    val retriedMedia = page.media[mediaIndex].copy(
        loadGeneration = page.media[mediaIndex].loadGeneration + 1L,
        ready = false,
    )
    val updatedMedia = page.media.toMutableList().also { media -> media[mediaIndex] = retriedMedia }
    val updatedPages = pages.toMutableList().also { currentPages ->
        currentPages[pageIndex] = page.copy(media = updatedMedia)
    }
    val retried = copy(
        pages = updatedPages,
        mediaError = null,
        controls = controls.copy(playback = controls.playback.copy(playing = controls.playback.available)),
    )
    if (page.resolution.status == ViewerResolutionStatus.FAILED && page.resolution.recoverable) {
        return retried.requestResolution(session, page.post.id)
    }
    if (retriedMedia.ref.streams.isNotEmpty()) return ViewerReduction(retried)
    return ViewerReduction(retried, listOf(ViewerEffect.RetryMedia(session, recoverable.mediaKey)))
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
        ?: fallbackUrls.firstOrNull(String::isNotBlank)
}

private fun ImageRef.isWebP(): Boolean {
    if (mime?.substringBefore(';')?.trim()?.equals("image/webp", ignoreCase = true) == true) return true
    return buildList {
        localPath?.let(::add)
        addAll(progressiveUrls)
        url?.let(::add)
        addAll(fallbackUrls)
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
