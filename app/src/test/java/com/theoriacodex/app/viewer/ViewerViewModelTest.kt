package com.theoriacodex.app.viewer

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.ui.state.AppRouteSavedStateKeys
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerEffect
import com.theoriacodex.app.viewer.state.ViewerMediaError
import com.theoriacodex.app.viewer.state.ViewerResolutionStatus
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModelTest {
    @Test
    fun `session replacement resets route state and persists only new reconstruction identity`() = runTest {
        val handle = SavedStateHandle()
        val owner = ViewerViewModel(handle, scopeOverride = this)
        owner.replaceSession(session("old", listOf(post("old", media = listOf(video("old.mp4"))))))
        owner.onAction(ViewerAction.Pause)

        val replacement = session("new", listOf(post("new")))
        owner.replaceSession(replacement)

        assertEquals(replacement.toViewerSessionIdentity(), owner.state.value.session)
        assertEquals("new", owner.state.value.currentPage?.post?.id?.sourcePostId)
        assertNull(owner.state.value.mediaError)
        assertTrue(owner.state.value.prefetch.ready.isEmpty())
        assertEquals("new", handle[AppRouteSavedStateKeys.VIEWER_SESSION_ID])
    }

    @Test
    fun `saved state keeps compact keys and restores page and media after payload reconstruction`() = runTest {
        val handle = SavedStateHandle()
        val session = session(
            id = "restore",
            posts = listOf(
                post("first"),
                post("second", media = listOf(image("a.jpg"), video("b.mp4"))),
            ),
        )
        val firstOwner = ViewerViewModel(handle, scopeOverride = this)
        firstOwner.replaceSession(session)
        firstOwner.onAction(ViewerAction.SelectPage(1))
        firstOwner.onAction(ViewerAction.SelectMedia(1))

        assertEquals(
            setOf(
                AppRouteSavedStateKeys.VIEWER_SESSION_ID,
                ViewerSavedStateKeys.QUERY_HASH,
                ViewerSavedStateKeys.STREAM_KEY,
                ViewerSavedStateKeys.PAGE_INDEX,
                ViewerSavedStateKeys.MEDIA_INDEX,
            ),
            handle.keys(),
        )

        val recreated = ViewerViewModel(handle, scopeOverride = this)
        assertEquals(session.toViewerSessionIdentity(), recreated.pendingRestoration)
        assertTrue(recreated.state.value.pages.isEmpty())

        recreated.replaceSession(session)

        assertNull(recreated.pendingRestoration)
        assertEquals(1, recreated.state.value.currentPageIndex)
        assertEquals(1, recreated.state.value.currentPage?.selectedMediaIndex)
        assertEquals("second", recreated.state.value.currentPage?.post?.id?.sourcePostId)
    }

    @Test
    fun `route owner keeps transient session metadata synchronized with resolved and appended pages`() = runTest {
        val owner = ViewerViewModel(SavedStateHandle(), scopeOverride = this)
        val initial = session(
            "owned",
            listOf(post("first"), post("second", media = listOf(image("second-a.jpg"), video("second-b.mp4")))),
        )
        owner.replaceSession(initial)
        owner.onAction(ViewerAction.SelectPage(1))
        owner.onAction(ViewerAction.SelectMedia(1))

        val resolved = initial.posts[1].copy(title = "Resolved")
        owner.applyResolvedPost(resolved)
        owner.updateSession { current -> current.copy(posts = current.posts + post("third")) }

        assertEquals(listOf("first", "second", "third"), owner.session.value?.posts?.map { it.id.sourcePostId })
        assertEquals("Resolved", owner.session.value?.posts?.get(1)?.title)
        assertEquals("Resolved", owner.state.value.pages[1].post.title)
        assertEquals(1, owner.state.value.currentPageIndex)
        assertEquals(1, owner.state.value.currentPage?.selectedMediaIndex)
        assertEquals(1, owner.session.value?.context?.startIndex)

        owner.clearSession()
        assertNull(owner.session.value)
        assertEquals(ViewerUiState.Empty, owner.state.value)
    }

    @Test
    fun `late resolution and media events cannot mutate a replacement or current media`() = runTest {
        val owner = ViewerViewModel(SavedStateHandle(), scopeOverride = this)
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
        val owner = ViewerViewModel(
            savedStateHandle = SavedStateHandle(),
            postResolver = ViewerPostResolver { _, _ -> resolved },
            mediaPrefetcher = ViewerMediaPrefetcher { _, _ -> true },
            scopeOverride = this,
        )
        owner.replaceSession(
            session(
                id = "effects",
                posts = listOf(post("lazy", source = SourceKey.HITOMI, media = emptyList())),
            )
        )

        owner.onAction(ViewerAction.RequestCurrentPageResolution)
        advanceUntilIdle()
        assertEquals(ViewerResolutionStatus.RESOLVED, owner.state.value.currentPage?.resolution?.status)
        assertEquals("Resolved", owner.state.value.currentPage?.post?.title)
        assertEquals("Resolved", owner.session.value?.posts?.single()?.title)

        val mediaKey = requireNotNull(owner.state.value.currentMedia?.key)
        owner.onAction(ViewerAction.QueuePrefetch(listOf(mediaKey)))
        advanceUntilIdle()
        assertTrue(mediaKey in owner.state.value.prefetch.ready)

        owner.onAction(ViewerAction.Save)
        val effect = owner.effects.first()
        assertEquals(
            ViewerEffect.SavePost(requireNotNull(owner.state.value.session), resolved.id),
            effect,
        )
    }

    @Test
    fun `missing resolved post becomes a terminal deleted post state`() = runTest {
        val owner = ViewerViewModel(
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

        owner.onAction(ViewerAction.RequestCurrentPageResolution)
        advanceUntilIdle()

        val resolution = requireNotNull(owner.state.value.currentPage?.resolution)
        assertEquals(ViewerResolutionStatus.FAILED, resolution.status)
        assertEquals("Post was deleted", resolution.message)
        assertFalse(resolution.recoverable)
        val error = owner.state.value.mediaError as ViewerMediaError.Fatal
        assertEquals("Post was deleted", error.message)
    }

    @Test
    fun `provider resolution failure remains recoverable and distinct from deletion`() = runTest {
        val owner = ViewerViewModel(
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

        owner.onAction(ViewerAction.RequestCurrentPageResolution)
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
        val owner = ViewerViewModel(
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
        owner.onAction(ViewerAction.RequestCurrentPageResolution)
        runCurrent()
        assertTrue(started.isCompleted)

        owner.replaceSession(session("replacement", listOf(post("replacement"))))
        runCurrent()

        assertTrue(cancelled.isCompleted)
        assertEquals("replacement", owner.state.value.currentPage?.post?.id?.sourcePostId)
        assertNull(owner.state.value.mediaError)
        assertFalse(owner.state.value.pages.any { page -> page.resolution.status == ViewerResolutionStatus.FAILED })
    }

    private fun session(id: String, posts: List<Post>): ViewerSession {
        return ViewerSession(
            posts = posts,
            context = ViewerLaunchContext(
                queryHash = "query:$id",
                startIndex = 0,
                streamSource = ViewerStreamSource.SEARCH,
                scrollOffsetHint = 0,
            ),
            sessionId = id,
        )
    }

    private fun post(
        id: String,
        source: SourceKey = SourceKey.PIXIV,
        media: List<ImageRef> = listOf(image("$id.jpg")),
        title: String? = null,
    ): Post {
        val preview = media.firstOrNull() ?: image("$id-preview.jpg")
        return Post(
            id = PostId(source, id),
            preview = preview,
            full = media.firstOrNull(),
            media = media,
            pageUrl = "https://example.test/$id",
            width = 100,
            height = 100,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            title = title,
        )
    }

    private fun image(path: String) = ImageRef(
        url = "https://example.test/$path",
        localPath = null,
        mime = "image/jpeg",
    )

    private fun video(path: String) = ImageRef(
        url = "https://example.test/$path",
        localPath = null,
        mime = "video/mp4",
    )
}
