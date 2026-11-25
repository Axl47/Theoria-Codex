package com.theoriacodex.app.viewer

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.ui.state.AppRouteSavedStateKeys
import com.theoriacodex.app.viewer.ViewerOwnerTestData.image
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import com.theoriacodex.app.viewer.ViewerOwnerTestData.queue
import com.theoriacodex.app.viewer.ViewerOwnerTestData.session
import com.theoriacodex.app.viewer.ViewerOwnerTestData.video
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerEffect
import com.theoriacodex.app.viewer.state.ViewerMediaError
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.ViewerPrefetchResult
import com.theoriacodex.app.viewer.state.ViewerResolutionStatus
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModelTest {
    @Test
    fun `cold restoration keeps original order and resumes the anchor of transient recommendations`() = runTest {
        val originals = listOf(post("seed"), post("next"))
        val related = post("related")
        val handle = SavedStateHandle()
        val owner = testViewerOwner(handle, scopeOverride = this)
        owner.replaceSession(session("branch", originals))
        owner.includeRelatedPosts(originals.first().id, listOf(related))
        owner.onAction(ViewerAction.SelectPage(2))
        val restored = testViewerOwner(handle, scopeOverride = this).restorationRequest
        assertEquals(originals.map { it.id }, restored?.orderedPostIds)
        assertEquals(originals.first().id, restored?.selectedPostId)
        owner.clearSession()
    }

    @Test
    fun `new videos use the device play mode without persisting speed`() = runTest {
        var preference = com.theoriacodex.app.viewer.state.ViewerPlaybackMode.ONCE
        val owner = testViewerOwner(SavedStateHandle(), scopeOverride = this, playbackModeProvider = { preference })
        owner.replaceSession(session("first", listOf(post("first", media = listOf(video("first.mp4"))))))
        assertEquals(preference, owner.state.value.controls.playbackMode)
        owner.onAction(ViewerAction.SetPlaybackRate(2f))
        owner.onAction(ViewerAction.SetPlaybackMode(com.theoriacodex.app.viewer.state.ViewerPlaybackMode.NEXT))
        owner.appendSessionPosts(requireNotNull(owner.session.value), owner.state.value.pages, listOf(post("more")))
        assertEquals(com.theoriacodex.app.viewer.state.ViewerPlaybackMode.NEXT, owner.state.value.controls.playbackMode)
        preference = com.theoriacodex.app.viewer.state.ViewerPlaybackMode.NEXT
        owner.clearSession()
        owner.replaceSession(session("second", listOf(post("second", media = listOf(video("second.mp4"))))))
        assertEquals(preference, owner.state.value.controls.playbackMode)
        assertEquals(1f, owner.state.value.controls.playback.playbackRate)
        owner.clearSession()
    }

    @Test
    fun `exit cancels work and clears authority while retaining paused selected media`() = runTest {
        val handle = SavedStateHandle()
        var cancelled = false
        val gallery = post("gallery", media = listOf(video("one.mp4"), video("two.mp4")))
        val owner = testViewerOwner(handle, postResolver = ViewerPostResolver { _, _ ->
            try { awaitCancellation() } finally { cancelled = true }
        }, scopeOverride = this)
        owner.replaceSession(session("exit", listOf(gallery, post("next", SourceKey.HITOMI, emptyList()))))
        owner.onAction(ViewerAction.SelectMedia(1))
        advanceTimeBy(VIEWER_PREFETCH_READY_WAIT_MS)
        runCurrent()
        val previous = owner.state.value

        owner.clearSession(retainPresentation = true)
        runCurrent()

        assertTrue(cancelled)
        assertNull(owner.session.value)
        assertNull(owner.state.value.session)
        assertNull(owner.restorationRequest)
        assertTrue(handle.keys().isEmpty())
        assertEquals(previous.pages, owner.state.value.pages)
        assertEquals(previous.currentMedia?.key, owner.state.value.currentMedia?.key)
        assertFalse(owner.state.value.controls.playback.playing)
        assertFalse(owner.state.value.controls.chromeVisible)
        val exiting = owner.state.value
        owner.onAction(ViewerAction.ResolutionCompleted(requireNotNull(previous.session), gallery))
        owner.onAction(ViewerAction.Play)
        assertEquals(exiting, owner.state.value)
        owner.clearSession()
        assertEquals(ViewerUiState.Empty, owner.state.value)
    }

    @Test
    fun `lookahead resolves only the next sparse post once the current media shows and swipe reuses active resolution`() = runTest {
        val started = mutableListOf<PostId>()
        val release = CompletableDeferred<Unit>()
        val posts = listOf(post("first"), post("next", SourceKey.HITOMI, emptyList()),
            post("third", SourceKey.HITOMI, emptyList()))
        val owner = testViewerOwner(SavedStateHandle(), postResolver = ViewerPostResolver { _, id ->
            started += id
            if (id == posts[1].id) release.await()
            post(id.sourcePostId, id.source)
        }, scopeOverride = this)
        owner.replaceSession(session("lookahead", posts))
        runCurrent()
        assertTrue(started.isEmpty())
        owner.onAction(ViewerAction.MediaReady(requireNotNull(owner.state.value.session), requireNotNull(owner.state.value.currentMedia).key, 0L))
        runCurrent()
        assertEquals(listOf(posts[1].id), started)
        owner.onAction(ViewerAction.SelectPage(1))
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, started.count { it == posts[1].id })
        assertEquals(ViewerResolutionStatus.RESOLVED, owner.state.value.currentPage?.resolution?.status)
        assertEquals(1, owner.state.value.currentPage?.media?.size)
        assertEquals(posts[2].id, started.last())
    }

    @Test
    fun `a series chapter's neighbours wait for its own pages, not its cover (ADR 0170)`() = runTest {
        val started = mutableListOf<PostId>()
        val release = CompletableDeferred<Unit>()
        fun chapter(id: String) = post(id, SourceKey.HITOMI, emptyList()).copy(full = null, galleryLengthUnknown = true)
        val chapters = listOf(chapter("one"), chapter("two"), chapter("three"))
        val owner = testViewerOwner(SavedStateHandle(), postResolver = ViewerPostResolver { _, id ->
            started += id
            if (id == chapters[1].id) release.await()
            post(id.sourcePostId, id.source)
        }, scopeOverride = this)
        owner.replaceSession(session("chapters", chapters, startIndex = 1).copy(series = ViewerSeries.Reading(GalleryReadingMode.VERTICAL)))
        runCurrent()
        val session = requireNotNull(owner.state.value.session)
        val cover = requireNotNull(owner.state.value.currentMedia)
        fun showCurrent() = requireNotNull(owner.state.value.currentMedia).let { owner.onAction(ViewerAction.MediaReady(session, it.key, it.loadGeneration)) }

        showCurrent()
        advanceTimeBy(VIEWER_PREFETCH_READY_WAIT_MS * 4)
        runCurrent()
        assertEquals(listOf(chapters[1].id), started)

        release.complete(Unit)
        runCurrent()
        owner.onAction(ViewerAction.MediaReady(session, cover.key, cover.loadGeneration))
        runCurrent()
        assertEquals("a late cover callback counted as page one", listOf(chapters[1].id), started)

        showCurrent()
        runCurrent()
        assertEquals(setOf(chapters[0].id, chapters[2].id), started.drop(1).toSet())
    }

    @Test
    fun `a series chapter whose first page never shows frees its neighbours after the chapter wait (ADR 0170)`() = runTest {
        val started = mutableListOf<PostId>()
        fun chapter(id: String) = post(id, SourceKey.HITOMI, emptyList()).copy(full = null, galleryLengthUnknown = true)
        val chapters = listOf(chapter("one"), chapter("two"), chapter("three"))
        val owner = testViewerOwner(SavedStateHandle(), postResolver = ViewerPostResolver { _, id ->
            started += id
            post(id.sourcePostId, id.source)
        }, scopeOverride = this)
        owner.replaceSession(session("stalled", chapters, startIndex = 1).copy(series = ViewerSeries.Reading(GalleryReadingMode.VERTICAL)))
        runCurrent()

        advanceTimeBy(VIEWER_CHAPTER_READY_WAIT_MS - 1)
        runCurrent()
        assertEquals(listOf(chapters[1].id), started)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(setOf(chapters[0].id, chapters[2].id), started.drop(1).toSet())
    }

    @Test
    fun `lookahead resolves the previous sparse post after the next and plans its prefetch`() = runTest {
        val started = mutableListOf<PostId>()
        val posts = listOf(post("prev", SourceKey.HITOMI, emptyList()), post("current"), post("next", SourceKey.HITOMI, emptyList()))
        val owner = testViewerOwner(SavedStateHandle(), postResolver = ViewerPostResolver { _, id ->
            started += id
            post(id.sourcePostId, id.source, listOf(video("${id.sourcePostId}.mp4")))
        }, scopeOverride = this)
        owner.replaceSession(session("previous", posts, startIndex = 1))
        advanceUntilIdle()

        assertEquals(listOf(posts[2].id, posts[0].id), started)
        val state = owner.state.value
        val previousKey = requireNotNull(state.pages[0].media.firstOrNull()).key
        assertTrue(previousKey in viewerPrefetchAction(state.pages, state.currentMedia?.key, null).requests.map { it.key })
        owner.clearSession()
    }

    @Test
    fun `a failing current page resolves once and retries only when the user returns to it`() = runTest {
        val attempts = AtomicInteger()
        val posts = listOf(post("sparse", SourceKey.HITOMI, emptyList()), post("next"))
        val owner = testViewerOwner(SavedStateHandle(), postResolver = ViewerPostResolver { _, _ ->
            attempts.incrementAndGet()
            error("Provider unavailable")
        }, scopeOverride = this)
        owner.replaceSession(session("failing", posts))
        advanceUntilIdle()
        assertEquals(1, attempts.get())
        assertEquals(ViewerResolutionStatus.FAILED, owner.state.value.currentPage?.resolution?.status)

        owner.onAction(ViewerAction.SelectPage(1))
        owner.onAction(ViewerAction.SelectPage(0))
        advanceUntilIdle()
        assertEquals(2, attempts.get())
        owner.clearSession()
    }

    @Test
    fun `including related posts keeps every page's playback, readiness and work`() = runTest {
        val seed = post("seed", media = listOf(video("seed.mp4")))
        val owner = testViewerOwner(SavedStateHandle(), scopeOverride = this)
        owner.replaceSession(session("related", listOf(seed, post("next"))))
        val identity = requireNotNull(owner.state.value.session)
        owner.onAction(ViewerAction.MediaReady(identity, requireNotNull(owner.state.value.currentMedia).key, 0L))
        owner.onAction(ViewerAction.SetPlaybackRate(2f))
        owner.onAction(ViewerAction.Pause)
        val before = owner.state.value.pages

        owner.includeRelatedPosts(seed.id, listOf(post("next"), post("r1", media = listOf(video("r1.mp4")))))

        val state = owner.state.value
        assertEquals(listOf("seed", "next", "r1"), state.pages.map { it.post.id.sourcePostId })
        before.forEachIndexed { index, page -> assertSame(page, state.pages[index]) }
        assertEquals(2f, state.controls.playback.playbackRate)
        assertFalse(state.controls.playback.playing)
        assertEquals(true, state.currentMedia?.ready)
        owner.clearSession()
    }

    @Test
    fun `jump cancels obsolete resolution without showing a timeout error`() = runTest {
        val cancelled = mutableListOf<PostId>()
        val posts = (0..3).map { post("$it", SourceKey.HITOMI, emptyList()) }
        val owner = testViewerOwner(SavedStateHandle(), postResolver = ViewerPostResolver { _, id ->
            try { awaitCancellation() } finally { cancelled += id }
        }, scopeOverride = this)
        owner.replaceSession(session("jump", posts))
        advanceTimeBy(VIEWER_PREFETCH_READY_WAIT_MS)
        runCurrent()
        owner.onAction(ViewerAction.SelectPage(3))
        runCurrent()
        assertEquals(listOf(posts[0].id, posts[1].id), cancelled)
        assertNull(owner.state.value.mediaError)
        assertEquals(ViewerResolutionStatus.IDLE, owner.state.value.pages[1].resolution.status)
        owner.clearSession()
    }

    @Test
    fun `equivalent reconstruction identity retains existing saved values`() = runTest {
        val handle = SavedStateHandle()
        val owner = testViewerOwner(handle, scopeOverride = this)
        val original = session("retained", listOf(post("first")))
        owner.replaceSession(original)
        val savedSessionId = handle.get<String>(AppRouteSavedStateKeys.VIEWER_SESSION_ID)
        val savedQueryHash = handle.get<String>(ViewerSavedStateKeys.QUERY_HASH)
        val equivalent = session(String(original.sessionId.toCharArray()), original.posts)
        assertNotSame(original.sessionId, equivalent.sessionId)
        assertNotSame(original.context.queryHash, equivalent.context.queryHash)

        owner.replaceSession(equivalent)

        assertSame(savedSessionId, handle.get<String>(AppRouteSavedStateKeys.VIEWER_SESSION_ID))
        assertSame(savedQueryHash, handle.get<String>(ViewerSavedStateKeys.QUERY_HASH))
        owner.replaceSession(session("changed", original.posts))
        assertEquals("changed", handle.get<String>(AppRouteSavedStateKeys.VIEWER_SESSION_ID))
    }

    @Test
    fun `session replacement resets route state and persists only new reconstruction identity`() = runTest {
        val handle = SavedStateHandle()
        val owner = testViewerOwner(handle, scopeOverride = this)
        owner.replaceSession(session("old", listOf(post("old", media = listOf(video("old.mp4"))))))
        owner.onAction(ViewerAction.Pause)

        val replacement = session("new", listOf(post("new")))
        owner.replaceSession(replacement)

        assertEquals(replacement.toViewerSessionIdentity(), owner.state.value.session)
        assertEquals("new", owner.state.value.currentPage?.post?.id?.sourcePostId)
        assertNull(owner.state.value.mediaError)
        assertEquals("new", handle[AppRouteSavedStateKeys.VIEWER_SESSION_ID])
    }

    @Test
    fun `saved state keeps compact keys and a restored session starts where it was saved`() = runTest {
        val handle = SavedStateHandle()
        val session = session(
            id = "restore",
            posts = listOf(
                post("first"),
                post("second", media = listOf(image("a.jpg"), video("b.mp4"))),
            ),
        )
        val firstOwner = testViewerOwner(handle, scopeOverride = this)
        firstOwner.replaceSession(session)
        firstOwner.onAction(ViewerAction.SelectPage(1))
        firstOwner.onAction(ViewerAction.SelectMedia(1))

        assertEquals(
            setOf(
                AppRouteSavedStateKeys.VIEWER_SESSION_ID,
                ViewerSavedStateKeys.QUERY_HASH,
                ViewerSavedStateKeys.STREAM_KEY,
                ViewerSavedStateKeys.MEDIA_INDEX,
                ViewerSavedStateKeys.SELECTED_POST,
                ViewerSavedStateKeys.ORDERED_POSTS,
                ViewerSavedStateKeys.RECENTS_SECTION,
            ),
            handle.keys(),
        )

        val recreated = testViewerOwner(
            handle,
            scopeOverride = this,
            playbackModeProvider = { com.theoriacodex.app.viewer.state.ViewerPlaybackMode.NEXT },
        )
        assertEquals(session.toViewerSessionIdentity(), recreated.pendingRestoration)
        assertTrue(recreated.state.value.pages.isEmpty())
        val request = requireNotNull(recreated.restorationRequest)
        assertEquals(session.posts[1].id, request.selectedPostId)
        assertEquals(1, request.selectedMediaIndex)

        recreated.replaceSession(
            session.copy(
                context = session.context.copy(startIndex = 1),
                initialMediaIndices = mapOf(session.posts[1].id to 1),
            ),
        )

        assertNull(recreated.pendingRestoration)
        assertEquals(1, recreated.state.value.currentPageIndex)
        assertEquals(com.theoriacodex.app.viewer.state.ViewerPlaybackMode.NEXT, recreated.state.value.controls.playbackMode)
        assertEquals(1, recreated.state.value.currentPage?.selectedMediaIndex)
        assertEquals("second", recreated.state.value.currentPage?.post?.id?.sourcePostId)
    }

    @Test
    fun `cold restoration selects canonical post when missing neighbors change its index`() = runTest {
        val handle = SavedStateHandle()
        val original = session("filtered-oldest", listOf(post("oldest"), post("removed"), post("selected")))
        val first = testViewerOwner(handle, scopeOverride = this)
        first.replaceSession(original)
        first.onAction(ViewerAction.SelectPage(2))

        val recreatedHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val recreated = testViewerOwner(recreatedHandle, scopeOverride = this)
        assertEquals(original.posts[2].id, recreated.restorationRequest?.selectedPostId)
        assertEquals(original.posts.map(Post::id), recreated.restorationRequest?.orderedPostIds)
        recreated.replaceSession(original.copy(posts = listOf(original.posts[2], original.posts[0])))

        assertEquals("selected", recreated.state.value.currentPage?.post?.id?.sourcePostId)
        assertEquals(0, recreated.state.value.currentPageIndex)
    }

    @Test
    fun `linked gallery page survives loading from a cover-only post`() = runTest {
        val resolved = CompletableDeferred<Post?>()
        val cover = post("552559", SourceKey.NHENTAI, listOf(image("cover.jpg"))).copy(mediaCount = 3)
        val gallery = cover.copy(media = listOf(image("1.jpg"), image("2.jpg"), image("3.jpg")))
        val handle = SavedStateHandle()
        val owner = testViewerOwner(
            handle,
            postResolver = ViewerPostResolver { _, _ -> resolved.await() },
            scopeOverride = this,
        )
        owner.replaceSession(session("deeplink", listOf(cover)).copy(initialMediaIndices = mapOf(cover.id to 2)))
        runCurrent()
        assertEquals(0, owner.state.value.currentPage?.selectedMediaIndex)
        assertEquals(2, handle.get<Int>(ViewerSavedStateKeys.MEDIA_INDEX))
        owner.appendSessionPosts(requireNotNull(owner.session.value), owner.state.value.pages, listOf(post("more")))
        resolved.complete(gallery)
        advanceUntilIdle()
        assertEquals(2, owner.state.value.currentPage?.selectedMediaIndex)
        owner.onAction(ViewerAction.SelectMedia(1))
        owner.applyResolvedPost(gallery)
        assertEquals(1, owner.state.value.currentPage?.selectedMediaIndex)
    }

    @Test
    fun `new recent session opens the last media without changing another session selection`() = runTest {
        val owner = testViewerOwner(SavedStateHandle(), scopeOverride = this)
        val gallery = post("gallery", media = listOf(image("1.jpg"), image("2.jpg"), image("3.jpg")))
        owner.replaceSession(session("recent", listOf(gallery)).copy(initialMediaIndices = mapOf(gallery.id to 2)))
        assertEquals(2, owner.state.value.currentPage?.selectedMediaIndex)
        owner.replaceSession(session("new", listOf(gallery)))
        assertEquals(0, owner.state.value.currentPage?.selectedMediaIndex)
    }

    @Test
    fun `restoration window remains bounded and retains the selected identity near either end`() = runTest {
        val posts = (0..199).map { post("$it") }
        val handle = SavedStateHandle()
        val owner = testViewerOwner(handle, scopeOverride = this)
        owner.replaceSession(session("large", posts))
        owner.onAction(ViewerAction.SelectPage(199))
        val request = requireNotNull(handle.get<ArrayList<String>>(ViewerSavedStateKeys.ORDERED_POSTS))
            .mapNotNull(::decodeViewerPostId)
        assertEquals(64, request.size)
        assertEquals(posts.last().id, request.last())
    }

    @Test
    fun `resolved and appended pages keep the selection and leave the launch payload as it was`() = runTest {
        val owner = testViewerOwner(SavedStateHandle(), scopeOverride = this)
        val initial = session(
            "owned",
            listOf(post("first"), post("second", media = listOf(image("second-a.jpg"), video("second-b.mp4")))),
        )
        owner.replaceSession(initial)
        owner.onAction(ViewerAction.SelectPage(1))
        owner.onAction(ViewerAction.SelectMedia(1))

        val resolved = initial.posts[1].copy(title = "Resolved")
        owner.applyResolvedPost(resolved)
        owner.appendSessionPosts(initial, owner.state.value.pages, listOf(post("third")))

        assertEquals(listOf("first", "second", "third"), owner.state.value.pages.map { it.post.id.sourcePostId })
        assertEquals("Resolved", owner.state.value.pages[1].post.title)
        assertEquals(1, owner.state.value.currentPageIndex)
        assertEquals(1, owner.state.value.currentPage?.selectedMediaIndex)
        assertSame(initial, owner.session.value)

        owner.clearSession()
        assertNull(owner.session.value)
        assertEquals(ViewerUiState.Empty, owner.state.value)
    }

    @Test
    fun `late resolution and media events cannot mutate a replacement or current media`() = runTest {
        val owner = testViewerOwner(SavedStateHandle(), scopeOverride = this)
        val oldSession = session("old", listOf(post("old")))
        owner.replaceSession(oldSession)
        val oldIdentity = oldSession.toViewerSessionIdentity()
        val oldPost = oldSession.posts.single()

        val currentSession = session(
            "current",
            listOf(post("current", media = listOf(video("first.mp4"), video("second.mp4")))),
        )
        owner.replaceSession(currentSession)
        val firstMediaKey = requireNotNull(owner.state.value.currentMedia?.key)
        owner.onAction(ViewerAction.SelectMedia(1))
        val selected = owner.state.value

        owner.onAction(ViewerAction.ResolutionCompleted(oldIdentity, oldPost.copy(title = "stale")))
        owner.onAction(
            ViewerAction.MediaFailed(
                currentSession.toViewerSessionIdentity(),
                ViewerMediaError.Recoverable(firstMediaKey, "late media failure"),
            )
        )

        assertEquals(selected, owner.state.value)
        assertTrue(owner.state.value.controls.playback.playing)
    }

    @Test
    fun `actions emit buffered host effects while resolution and prefetch stay route owned`() = runTest {
        val resolved = post("lazy", source = SourceKey.HITOMI, title = "Resolved")
        val warmed = mutableListOf<com.theoriacodex.app.viewer.state.ViewerMediaKey>()
        val owner = testViewerOwner(
            savedStateHandle = SavedStateHandle(),
            postResolver = ViewerPostResolver { _, _ -> resolved },
            mediaPrefetcher = ViewerMediaPrefetcher { _, media, _ ->
                warmed += media.key
                ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED)
            },
            scopeOverride = this,
        )
        owner.replaceSession(
            session(
                id = "effects",
                posts = listOf(post("lazy", source = SourceKey.HITOMI, media = emptyList())),
            )
        )

        advanceUntilIdle()
        assertEquals(ViewerResolutionStatus.RESOLVED, owner.state.value.currentPage?.resolution?.status)
        assertEquals("Resolved", owner.state.value.currentPage?.post?.title)

        val mediaKey = requireNotNull(owner.state.value.currentMedia?.key)
        owner.onAction(queue(mediaKey))
        advanceUntilIdle()
        assertEquals(listOf(mediaKey), warmed)

        owner.onAction(ViewerAction.Save)
        val effect = owner.effects.first()
        assertEquals(
            ViewerEffect.SavePost(requireNotNull(owner.state.value.session), resolved.id),
            effect,
        )
    }

    @Test
    fun `missing resolved post becomes a terminal deleted post state`() = runTest {
        val owner = testViewerOwner(
            savedStateHandle = SavedStateHandle(),
            postResolver = ViewerPostResolver { _, _ -> null },
            scopeOverride = this,
        )
        owner.replaceSession(
            session(
                id = "deleted",
                posts = listOf(
                    post(
                        "deleted",
                        source = SourceKey.GELBOORU,
                        media = listOf(video("deleted.mp4")),
                    ),
                ),
            )
        )

        advanceUntilIdle()

        val resolution = requireNotNull(owner.state.value.currentPage?.resolution)
        assertEquals(ViewerResolutionStatus.FAILED, resolution.status)
        assertEquals("Post was deleted", resolution.message)
        assertFalse(resolution.recoverable)
        val error = owner.state.value.mediaError as ViewerMediaError.Fatal
        assertEquals("Post was deleted", error.message)
    }

    @Test
    fun `resolution returning a different post fails instead of staying requested`() = runTest {
        val owner = testViewerOwner(
            savedStateHandle = SavedStateHandle(),
            postResolver = ViewerPostResolver { _, _ -> post("newest", source = SourceKey.GELBOORU) },
            scopeOverride = this,
        )
        owner.replaceSession(session("mismatch", listOf(post("asked", SourceKey.GELBOORU, listOf(video("asked.mp4"))))))

        advanceUntilIdle()

        val resolution = requireNotNull(owner.state.value.currentPage?.resolution)
        assertEquals(ViewerResolutionStatus.FAILED, resolution.status)
        assertFalse(resolution.recoverable)
        assertEquals("asked", owner.state.value.currentPage?.post?.id?.sourcePostId)
    }

    @Test
    fun `provider resolution failure remains recoverable and distinct from deletion`() = runTest {
        val owner = testViewerOwner(
            savedStateHandle = SavedStateHandle(),
            postResolver = ViewerPostResolver { _, _ -> error("Provider unavailable") },
            scopeOverride = this,
        )
        owner.replaceSession(
            session(
                id = "provider-failure",
                posts = listOf(
                    post(
                        "provider-failure",
                        source = SourceKey.GELBOORU,
                        media = listOf(video("provider-failure.mp4")),
                    ),
                ),
            )
        )

        advanceUntilIdle()

        val resolution = requireNotNull(owner.state.value.currentPage?.resolution)
        assertEquals(ViewerResolutionStatus.FAILED, resolution.status)
        assertEquals("Provider unavailable", resolution.message)
        assertTrue(resolution.recoverable)
    }

    @Test
    fun `session replacement cancels owned resolution work without publishing failure`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val owner = testViewerOwner(
            savedStateHandle = SavedStateHandle(),
            postResolver = ViewerPostResolver { _, _ ->
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
            scopeOverride = this,
        )
        owner.replaceSession(
            session("blocking", listOf(post("lazy", source = SourceKey.HITOMI, media = emptyList())))
        )
        runCurrent()
        assertTrue(started.isCompleted)

        owner.replaceSession(session("replacement", listOf(post("replacement"))))
        runCurrent()

        assertTrue(cancelled.isCompleted)
        assertEquals("replacement", owner.state.value.currentPage?.post?.id?.sourcePostId)
        assertNull(owner.state.value.mediaError)
        assertFalse(owner.state.value.pages.any { page -> page.resolution.status == ViewerResolutionStatus.FAILED })
    }

    @Test
    fun `known stale media stays mounted while its provider refresh runs`() = runTest {
        val refreshStarted = CompletableDeferred<Unit>()
        val stale = post(
            id = "stale-video",
            source = SourceKey.GELBOORU,
            media = listOf(video("stale-video.mp4")),
        )
        val codexSession = session("optimistic", listOf(stale)).let { current ->
            current.copy(context = current.context.copy(streamSource = ViewerStreamSource.CODEX))
        }
        val owner = testViewerOwner(
            savedStateHandle = SavedStateHandle(),
            postResolver = ViewerPostResolver { _, _ ->
                refreshStarted.complete(Unit)
                awaitCancellation()
            },
            scopeOverride = this,
        )

        owner.replaceSession(codexSession)
        runCurrent()

        assertTrue(refreshStarted.isCompleted)
        assertEquals("stale-video", owner.state.value.currentPage?.post?.id?.sourcePostId)
        assertTrue(owner.state.value.currentMedia?.displayLocation?.endsWith("stale-video.mp4") == true)
        owner.clearSession()
    }
}
