package com.theoriacodex.app.viewer.state

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boundaries and guards of [reduceViewerState]: which page or media a request lands on, and what is left alone. */
class ViewerStateBoundaryTest {
    private val session = ViewerSessionIdentity(value = "boundary", queryHash = "query-boundary")

    @Test
    fun `media kind comes from mime then extension and Pixiv ugoira is decided by the post`() {
        fun kind(ref: ImageRef, post: Post = post("p", listOf(ref))) = ref.toViewerMediaState(post, 0).kind
        val ugoiraFull = ref(PIXIV_UGOIRA_MIME, "u.zip")
        val ugoiraPost = post("u", listOf(ref("image/jpeg", "frame.jpg"))).copy(full = ugoiraFull)
        assertEquals(ViewerMediaKind.UGOIRA, kind(ref("image/jpeg", "frame.jpg"), ugoiraPost))
        assertEquals(
            ViewerMediaKind.IMAGE,
            kind(ref("image/jpeg", "frame.jpg"), ugoiraPost.copy(id = PostId(SourceKey.IWARA, "u"))),
        )
        assertEquals(ViewerMediaKind.UNKNOWN, kind(ref(null, "blob.bin")))
        assertEquals(ViewerMediaKind.GIF, kind(ref("image/gif", "a.bin", animated = true)))
        assertEquals(ViewerMediaKind.GIF, kind(ref(null, "a.gif")))
        assertEquals(ViewerMediaKind.ANIMATED_IMAGE, kind(ref("image/png", "a.png", animated = true)))
    }

    @Test
    fun `animated WebP is recognised from the mime or from the path of any rendition`() {
        fun kind(ref: ImageRef) = ref.toViewerMediaState(post("p", listOf(ref)), 0).kind
        val animated = ref("image/avif", "x.php", animated = true)
        assertEquals(ViewerMediaKind.ANIMATED_WEBP, kind(ref("image/webp; charset=binary", "x.php", animated = true)))
        assertEquals(ViewerMediaKind.ANIMATED_WEBP, kind(ref(" IMAGE/WEBP ", "x.php", animated = true)))
        assertEquals(ViewerMediaKind.ANIMATED_WEBP, kind(ref(null, "a.WEBP?sig=1", animated = true)))
        assertEquals(ViewerMediaKind.ANIMATED_WEBP, kind(animated.copy(localPath = "/data/a.webp")))
        assertEquals(ViewerMediaKind.ANIMATED_WEBP, kind(animated.copy(progressiveUrls = listOf("s.webp#frame"))))
        assertEquals(ViewerMediaKind.ANIMATED_WEBP, kind(animated.copy(fallbackUrls = listOf("m.webp?sig=1"))))
        assertEquals(ViewerMediaKind.ANIMATED_IMAGE, kind(ref("image/avif", "x.jpg?name=a.webp", animated = true)))
        assertEquals(ViewerMediaKind.ANIMATED_IMAGE, kind(animated))
    }

    @Test
    fun `display location prefers a local file, then renditions, then the url, skipping blanks`() {
        fun location(ref: ImageRef) = ref.toViewerMediaState(post("p", listOf(ref)), 0).displayLocation
        val full = ImageRef("u.jpg", "/l.jpg", "image/jpeg", progressiveUrls = listOf(" ", "s.jpg"), fallbackUrls = listOf("m.jpg"))
        assertEquals("/l.jpg", location(full))
        assertEquals("s.jpg", location(full.copy(localPath = " ")))
        assertEquals("u.jpg", location(full.copy(localPath = null, progressiveUrls = listOf(" "))))
        assertEquals("m.jpg", location(full.copy(localPath = null, progressiveUrls = emptyList(), url = " ")))
        assertNull(location(full.copy(localPath = null, progressiveUrls = emptyList(), url = null, fallbackUrls = listOf(" "))))
    }

    @Test
    fun `a launch index is clamped and kept as a pending request only while the gallery may still grow`() {
        val one = post("one", listOf(ref("image/jpeg", "1.jpg")))
        fun page(index: Int, status: ViewerResolutionStatus) =
            one.toViewerPageState(index, ViewerResolutionState(status = status))
        assertNull(page(0, ViewerResolutionStatus.IDLE).pendingMediaIndex)
        assertEquals(1, page(1, ViewerResolutionStatus.IDLE).pendingMediaIndex)
        assertEquals(0, page(1, ViewerResolutionStatus.IDLE).selectedMediaIndex)
        assertNull(page(-3, ViewerResolutionStatus.IDLE).pendingMediaIndex)
        assertEquals(0, page(-3, ViewerResolutionStatus.IDLE).selectedMediaIndex)
        assertEquals(4, page(4, ViewerResolutionStatus.FAILED).pendingMediaIndex)
        assertNull(page(4, ViewerResolutionStatus.NOT_REQUIRED).pendingMediaIndex)
        assertNull(page(4, ViewerResolutionStatus.RESOLVED).pendingMediaIndex)
    }

    @Test
    fun `selecting a page clamps the index, closes transient panels and keeps the playback rate`() {
        var state = created(video("a"), still("b"), video("c"))
        state = state.act(ViewerAction.SetPlaybackRate(2f))
            .act(ViewerAction.ShowActionsMenu).act(ViewerAction.ShowMetadata).act(ViewerAction.ShowPlaybackSettings)
            .let { it.copy(mediaError = ViewerMediaError.Fatal(null, "broken")) }
        val last = state.act(ViewerAction.SelectPage(99))
        assertEquals(2, last.currentPageIndex)
        assertEquals(ViewerControlsState(playback = last.controls.playback), last.controls)
        assertTrue(last.controls.playback.available)
        assertEquals(2f, last.controls.playback.playbackRate)
        assertNull(last.mediaError)
        val first = last.act(ViewerAction.SelectPage(-4))
        assertEquals(0, first.currentPageIndex)
        assertFalse(first.act(ViewerAction.SelectPage(1)).controls.playback.available)
    }

    @Test
    fun `selecting media clamps, remembers a request past the loaded gallery and ignores an empty viewer`() {
        val gallery = post("g", listOf(ref("image/jpeg", "1.jpg"), ref("image/jpeg", "2.jpg")))
        val loading = createViewerUiState(session, listOf(gallery), requiresResolution = { true })
        val loaded = createViewerUiState(session, listOf(gallery))
        assertNull(loading.act(ViewerAction.SelectMedia(1)).currentPage?.pendingMediaIndex)
        assertEquals(2, loading.act(ViewerAction.SelectMedia(2)).currentPage?.pendingMediaIndex)
        assertNull(loaded.act(ViewerAction.SelectMedia(9)).currentPage?.pendingMediaIndex)
        assertEquals(created(), created().act(ViewerAction.SelectMedia(1)))
    }

    @Test
    fun `hiding the chrome closes its menus and showing it never reopens one that was closed`() {
        val video = created(video("a"))
        val open = video.act(ViewerAction.ShowActionsMenu).act(ViewerAction.ShowPlaybackSettings)
        val hidden = open.act(ViewerAction.ToggleChrome)
        assertFalse(hidden.controls.chromeVisible)
        assertFalse(hidden.controls.actionsMenuVisible)
        assertTrue(open.controls.actionsMenuVisible && open.controls.playbackSettingsVisible)
        assertFalse(hidden.act(ViewerAction.ToggleChrome).controls.actionsMenuVisible)
    }

    @Test
    fun `panel actions toggle only their own flag and an empty viewer ignores every action`() {
        val state = created(video("a"))
        val panels = listOf(
            ViewerAction.ShowMetadata to ViewerAction.HideMetadata, ViewerAction.ShowActionsMenu to ViewerAction.HideActionsMenu,
            ViewerAction.ShowPlaybackSettings to ViewerAction.HidePlaybackSettings,
        )
        for ((show, hide) in panels) {
            val shown = state.act(show)
            assertEquals(1, listOf(shown.controls.metadataVisible, shown.controls.actionsMenuVisible, shown.controls.playbackSettingsVisible).count { it })
            assertEquals(state.controls, shown.act(hide).controls)
        }
        assertEquals(ViewerUiState.Empty, ViewerUiState.Empty.act(ViewerAction.ToggleChrome))
        val errored = created().act(ViewerAction.MediaFailed(session, ViewerMediaError.Fatal(null, "no pages")))
        assertEquals("no pages", errored.mediaError?.message)
        assertNull(errored.act(ViewerAction.ClearMediaError).mediaError)
        val term = PostTaxonomyTerm("tag")
        for (action in listOf(ViewerAction.Save, ViewerAction.ToggleLike, ViewerAction.IncludeTag(term))) {
            assertTrue(reduceViewerState(created(), action).effects.isEmpty())
        }
        assertEquals(listOf(ViewerEffect.SetLiked(session, state.currentPage!!.post.id)), reduceViewerState(state, ViewerAction.ToggleLike).effects)
        assertEquals(listOf(ViewerEffect.LoadMore(session)), reduceViewerState(state, ViewerAction.LoadMore).effects)
        assertEquals(listOf(ViewerEffect.Dismiss(session)), reduceViewerState(state, ViewerAction.Dismiss).effects)
    }

    @Test
    fun `playback controls act only on playable media and reject a rate that is not positive`() {
        val image = created(still("a"))
        val actions = listOf(ViewerAction.Play, ViewerAction.TogglePlayback, ViewerAction.RestartPlayback, ViewerAction.SetPlaybackRate(2f), ViewerAction.ShowPlaybackSettings)
        assertTrue(actions.all { image.act(it) == image })
        val video = created(video("a"))
        assertEquals(1f, video.act(ViewerAction.SetPlaybackRate(0f)).controls.playback.playbackRate)
        assertEquals(1f, video.act(ViewerAction.SetPlaybackRate(-1f)).controls.playback.playbackRate)
        assertEquals(0.25f, video.act(ViewerAction.SetPlaybackRate(0.25f)).controls.playback.playbackRate)
        assertFalse(video.act(ViewerAction.TogglePlayback).controls.playback.playing)
        assertTrue(video.act(ViewerAction.TogglePlayback).act(ViewerAction.TogglePlayback).controls.playback.playing)
    }

    @Test
    fun `playback availability and the overview follow the media of the selected page`() {
        fun controls(vararg refs: ImageRef) = createViewerUiState(session, listOf(post("p", refs.toList())))
        assertTrue(controls(ref("image/gif", "a.gif")).controls.playback.available)
        assertTrue(controls(ref("image/png", "a.png", animated = true)).controls.playback.available)
        assertFalse(controls(ref(null, "a.bin")).controls.playback.available)
        val single = controls(ref("image/jpeg", "1.jpg"))
        assertEquals(single, single.act(ViewerAction.ToggleOverview))
        val pair = controls(ref("image/jpeg", "1.jpg"), ref("image/jpeg", "2.jpg"))
        assertEquals(ViewerOverviewState(visible = true, available = true), pair.act(ViewerAction.ToggleOverview).overview)
        assertFalse(pair.act(ViewerAction.ToggleOverview).act(ViewerAction.ToggleOverview).overview.visible)
    }

    @Test
    fun `a slideshow timer for media that is no longer showing does not advance`() {
        val state = created(still("a"), still("b")).act(ViewerAction.SetSlideshow(true))
        assertEquals(state, state.act(ViewerAction.SlideshowAdvance(session, ViewerMediaKey(PostId(SourceKey.PIXIV, "b"), 0))))
        assertEquals(1, state.act(ViewerAction.SlideshowAdvance(session, state.currentMedia!!.key)).currentPageIndex)
    }

    @Test
    fun `resolution starts only from idle or failed, once, and clears the previous failure`() {
        val a = still("a")
        var state = createViewerUiState(session, listOf(a, still("b")), requiresResolution = { it.id == a.id })
        val requested = reduceViewerState(state, ViewerAction.RequestCurrentPageResolution)
        assertEquals(listOf(ViewerEffect.ResolvePost(session, a.id)), requested.effects)
        assertEquals(ViewerResolutionStatus.REQUESTED, requested.state.currentPage?.resolution?.status)
        assertTrue(reduceViewerState(requested.state, ViewerAction.RequestCurrentPageResolution).effects.isEmpty())
        state = requested.state.act(ViewerAction.ResolutionStarted(session, a.id))
        assertTrue(reduceViewerState(state, ViewerAction.RequestPageResolution(a.id)).effects.isEmpty())
        state = state.act(ViewerAction.ResolutionFailed(session, a.id, "offline", recoverable = false))
        val retried = reduceViewerState(state, ViewerAction.RequestPageResolution(a.id))
        assertEquals(listOf(ViewerEffect.ResolvePost(session, a.id)), retried.effects)
        assertEquals(ViewerResolutionState(ViewerResolutionStatus.REQUESTED, attempt = 1, message = null, recoverable = true), retried.state.currentPage?.resolution)
        val done = retried.state.act(ViewerAction.ResolutionCompleted(session, a))
        assertTrue(reduceViewerState(done, ViewerAction.RequestPageResolution(a.id)).effects.isEmpty())
        assertTrue(reduceViewerState(state, ViewerAction.RequestPageResolution(PostId(SourceKey.PIXIV, "nope"))).effects.isEmpty())
        assertTrue(reduceViewerState(created(), ViewerAction.RequestCurrentPageResolution).effects.isEmpty())
    }

    @Test
    fun `resolution progress counts attempts and touches only the named page of the current session`() {
        val a = still("a")
        val other = session.copy(value = "other")
        val state = createViewerUiState(session, listOf(a), requiresResolution = { true })
            .act(ViewerAction.ResolutionStarted(session, a.id))
            .act(ViewerAction.ResolutionStarted(session, a.id))
        assertEquals(ViewerResolutionState(ViewerResolutionStatus.RESOLVING, attempt = 2), state.currentPage?.resolution)
        assertEquals(ViewerResolutionStatus.IDLE, state.act(ViewerAction.ResolutionCancelled(session, a.id)).currentPage?.resolution?.status)
        assertEquals(state, state.act(ViewerAction.ResolutionCancelled(session, PostId(SourceKey.PIXIV, "nope"))))
        assertEquals(state, state.act(ViewerAction.ResolutionCancelled(other, a.id)))
        assertEquals(state, state.act(ViewerAction.ResolutionFailed(other, a.id, "late", recoverable = true)))
    }

    @Test
    fun `a failed resolution of the current page is an error unless working media is already showing`() {
        val a = video("a")
        val started = createViewerUiState(session, listOf(a), requiresResolution = { true })
            .act(ViewerAction.ResolutionStarted(session, a.id))
        val key = requireNotNull(started.currentMedia).key
        fun failed(state: ViewerUiState, recoverable: Boolean) =
            state.act(ViewerAction.ResolutionFailed(session, a.id, "no data", recoverable))
        val recoverable = failed(started, recoverable = true)
        assertEquals(ViewerMediaError.Recoverable(key, "no data", retryCount = 1), recoverable.mediaError)
        val ready = started.act(ViewerAction.MediaReady(session, key, 0L))
        val whileShowing = failed(ready, recoverable = true)
        assertNull(whileShowing.mediaError)
        val fatal = failed(ready, recoverable = false)
        assertEquals(ViewerMediaError.Fatal(key, "no data"), fatal.mediaError)
        assertFalse(fatal.controls.playback.playing)
        assertFalse(fatal.currentPage!!.resolution.recoverable)
    }

    @Test
    fun `a resolved post keeps the requested position and only its own page and prefetch state change`() {
        val a = post("a", listOf(ref("image/jpeg", "1.jpg"), ref("image/jpeg", "2.jpg")))
        val b = post("b", listOf(ref("image/jpeg", "b1.jpg")))
        fun keys(post: Post) = (0..1).map { ViewerMediaKey(post.id, it) }
        val (a0, a1) = keys(a)
        val (b0, b1) = keys(b)
        val prefetch = ViewerPrefetchState(
            queued = setOf(a0, b0), inFlight = setOf(a1, b1), warmed = mapOf(a0 to 1L, b0 to 2L),
            decoded = setOf(a0, b0), skipped = setOf(a1, b1), failed = setOf(a0, b1),
        )
        val loading = createViewerUiState(
            session, listOf(a, b), requiresResolution = { true }, initialMediaIndices = mapOf(a.id to 3),
        ).let { it.copy(prefetch = prefetch, mediaError = ViewerMediaError.Fatal(null, "old")) }
            .act(ViewerAction.ToggleOverview)
        assertEquals(3, loading.pages[0].pendingMediaIndex)
        val resolvedB = loading.act(ViewerAction.ResolutionCompleted(session, b.copy(media = b.media + b.media)))
        assertEquals(2, resolvedB.pages[1].media.size)
        assertEquals(loading.pages[0], resolvedB.pages[0])
        assertEquals(loading.controls, resolvedB.controls)
        assertEquals(loading.mediaError, resolvedB.mediaError)
        assertEquals(setOf(a0), resolvedB.prefetch.queued)
        assertEquals(setOf(a1), resolvedB.prefetch.inFlight)
        assertEquals(mapOf(a0 to 1L), resolvedB.prefetch.warmed)
        assertEquals(setOf(a0), resolvedB.prefetch.decoded)
        assertEquals(setOf(a1), resolvedB.prefetch.skipped)
        assertEquals(setOf(a0), resolvedB.prefetch.failed)
        val grown = a.copy(media = a.media + ref("image/jpeg", "3.jpg") + ref("video/mp4", "4.mp4"))
        val resolvedA = loading.act(ViewerAction.ResolutionCompleted(session, grown))
        assertEquals(3, resolvedA.currentPage?.selectedMediaIndex)
        assertNull(resolvedA.currentPage?.pendingMediaIndex)
        assertEquals(ViewerMediaKind.VIDEO, resolvedA.currentMedia?.kind)
        assertTrue(resolvedA.controls.playback.playing)
        assertTrue(resolvedA.overview.visible)
        assertNull(resolvedA.mediaError)
        assertEquals(loading, loading.act(ViewerAction.ResolutionCompleted(session, still("unknown"))))
    }

    @Test
    fun `media readiness follows the load generation and a retry invalidates it`() {
        val gallery = post("g", listOf(ref("video/mp4", "1.mp4"), ref("video/mp4", "2.mp4")))
        val state = createViewerUiState(session, listOf(gallery))
        val key = requireNotNull(state.currentMedia).key
        val other = ViewerMediaKey(gallery.id, 1)
        val ready = state.act(ViewerAction.MediaReady(session, key, 0L))
        assertTrue(ready.currentMedia!!.ready)
        assertEquals(ready, ready.act(ViewerAction.MediaReady(session, other, 0L)))
        val failed = ready.act(ViewerAction.MediaFailed(session, ViewerMediaError.Recoverable(key, "stall"), loadGeneration = 0L))
        assertFalse(failed.currentMedia!!.ready)
        assertEquals(ready, ready.act(ViewerAction.MediaFailed(session, ViewerMediaError.Recoverable(key, "late"), loadGeneration = 5L)))
        val retried = reduceViewerState(failed, ViewerAction.RetryMedia).state
        assertEquals(1L, retried.currentMedia?.loadGeneration)
        assertFalse(retried.currentMedia!!.ready)
        assertTrue(retried.controls.playback.playing)
        assertTrue(retried.act(ViewerAction.MediaReady(session, key, 1L)).currentMedia!!.ready)
    }

    @Test
    fun `retry needs a recoverable error for media still on a page and bumps only that media`() {
        val gallery = post("g", listOf(ref("image/jpeg", "1.jpg"), ref("image/jpeg", "2.jpg")))
        val state = createViewerUiState(session, listOf(gallery))
        val second = ViewerMediaKey(gallery.id, 1)
        fun retry(error: ViewerMediaError?) = reduceViewerState(state.copy(mediaError = error), ViewerAction.RetryMedia)
        assertEquals(ViewerReduction(state), retry(null))
        assertEquals(ViewerReduction(state.copy(mediaError = ViewerMediaError.Fatal(second, "x"))), retry(ViewerMediaError.Fatal(second, "x")))
        val missingPost = ViewerMediaError.Recoverable(ViewerMediaKey(PostId(SourceKey.PIXIV, "gone"), 0), "x")
        assertEquals(ViewerReduction(state.copy(mediaError = missingPost)), retry(missingPost))
        val missing = ViewerMediaError.Recoverable(ViewerMediaKey(gallery.id, 9), "x")
        assertEquals(ViewerReduction(state.copy(mediaError = missing)), retry(missing))
        val retried = retry(ViewerMediaError.Recoverable(second, "x"))
        assertEquals(listOf(ViewerEffect.RetryMedia(session, second)), retried.effects)
        assertEquals(listOf(0L, 1L), retried.state.pages[0].media.map { it.loadGeneration })
        assertFalse(retried.state.controls.playback.playing)
    }

    @Test
    fun `prefetch requests drop unknown and duplicate keys and keep a download that is still wanted`() {
        val gallery = post("g", (1..3).map { ref("image/jpeg", "$it.jpg") })
        val (k0, k1, k2) = (0..2).map { ViewerMediaKey(gallery.id, it) }
        val state = createViewerUiState(session, listOf(gallery))
            .copy(prefetch = ViewerPrefetchState(inFlight = setOf(k1)))
        val unknownPost = ViewerMediaKey(PostId(SourceKey.PIXIV, "gone"), 0)
        val unknownIndex = ViewerMediaKey(gallery.id, 7)
        val queued = reduceViewerState(state, ViewerAction.QueuePrefetch(listOf(k0, unknownPost, k0, k1, unknownIndex, k2)))
        assertEquals(listOf(ViewerEffect.PrefetchMedia(session, listOf(k0, k1, k2))), queued.effects)
        assertEquals(setOf(k0, k2), queued.state.prefetch.queued)
        assertEquals(emptySet<ViewerMediaKey>(), reduceViewerState(queued.state, ViewerAction.QueuePrefetch(listOf(k0))).state.prefetch.inFlight)
    }

    @Test
    fun `a live update keeps the user's mode and gallery position while appended posts seed their own`() {
        val a = post("a", listOf(ref("image/jpeg", "1.jpg")))
        val c = post("c", listOf(ref("image/jpeg", "c1.jpg"), ref("image/jpeg", "c2.jpg")))
        val loading = createViewerUiState(session, listOf(a), requiresResolution = { true }, initialMediaIndices = mapOf(a.id to 2))
            .act(ViewerAction.SetPlaybackMode(ViewerPlaybackMode.NEXT))
        fun replace(identity: ViewerSessionIdentity) = loading.act(
            ViewerAction.ReplaceSession(identity, listOf(a), resolutionRequiredPostIds = setOf(a.id)),
        )
        assertEquals(2, replace(session).currentPage?.pendingMediaIndex)
        assertNull(replace(session.copy(value = "other")).currentPage?.pendingMediaIndex)
        assertEquals(ViewerPlaybackMode.NEXT, replace(session).controls.playbackMode)
        val appended = loading.act(ViewerAction.AppendPosts(session, listOf(c), initialMediaIndices = mapOf(c.id to 1)))
        assertEquals(loading.pages[0], appended.pages[0])
        assertEquals(1, appended.pages[1].selectedMediaIndex)
        assertEquals(loading, loading.act(ViewerAction.AppendPosts(session.copy(value = "other"), listOf(c))))
    }

    private fun ViewerUiState.act(action: ViewerAction) = reduceViewerState(this, action).state

    private fun created(vararg posts: Post) = createViewerUiState(session, posts.toList())

    private fun ref(mime: String?, location: String, animated: Boolean = false) =
        ImageRef(url = "https://media.example/$location", localPath = null, mime = mime, isAnimated = animated)

    private fun still(id: String) = post(id, listOf(ref("image/jpeg", "$id.jpg")))

    private fun video(id: String) = post(id, listOf(ref("video/mp4", "$id.mp4")))

    private fun post(id: String, media: List<ImageRef>) = Post(
        id = PostId(SourceKey.PIXIV, id), preview = media.first(), full = media.first(), media = media, pageUrl = null,
        width = 100, height = 200, canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = 1L,
    )
}
