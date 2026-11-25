package com.theoriacodex.app.viewer.state

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerStateReducerTest {
    @Test
    fun `live session updates retain visited positions and seed newly appended galleries`() {
        val first = samplePost("first", media = (1..4).map { media("image/jpeg", "$it.jpg") })
        val second = first.copy(id = PostId(SourceKey.PIXIV, "second"))
        val identity = session("live-resume")
        var state = reduceViewerState(ViewerUiState.Empty, ViewerAction.ReplaceSession(
            identity, listOf(first), initialMediaIndices = mapOf(first.id to 3, second.id to 2),
        )).state
        state = reduceViewerState(state, ViewerAction.SelectMedia(1)).state
        state = reduceViewerState(state, ViewerAction.ReplaceSession(
            identity, listOf(first, second), initialMediaIndices = mapOf(first.id to 3, second.id to 2),
        )).state
        assertEquals(1, state.pages[0].selectedMediaIndex)
        assertEquals(2, state.pages[1].selectedMediaIndex)
        state = reduceViewerState(state, ViewerAction.SelectPage(1)).state
        assertEquals(2, state.currentPage?.selectedMediaIndex)
    }

    @Test
    fun `live session updates keep chrome hidden and a new session shows it`() {
        val post = samplePost("first")
        val identity = session("live-chrome")
        var state = reduceViewerState(ViewerUiState.Empty, ViewerAction.ReplaceSession(identity, listOf(post))).state
        state = reduceViewerState(state, ViewerAction.ToggleChrome).state
        state = reduceViewerState(state, ViewerAction.ReplaceSession(identity, listOf(post))).state
        assertEquals(false, state.controls.chromeVisible)
        state = reduceViewerState(state, ViewerAction.ReplaceSession(session("other"), listOf(post))).state
        assertEquals(true, state.controls.chromeVisible)
    }

    @Test
    fun `setting chrome visibility is idempotent`() {
        val shown = reduceViewerState(ViewerUiState.Empty, ViewerAction.ReplaceSession(session("set-chrome"), listOf(samplePost("first")))).state
        val hidden = reduceViewerState(shown, ViewerAction.SetChromeVisible(false)).state
        assertEquals(false, hidden.controls.chromeVisible)
        assertEquals(hidden, reduceViewerState(hidden, ViewerAction.SetChromeVisible(false)).state)
        assertEquals(true, reduceViewerState(hidden, ViewerAction.SetChromeVisible(true)).state.controls.chromeVisible)
    }

    @Test
    fun `completion modes preserve order stop at queue end and reject obsolete media`() {
        val first = samplePost("first", media = listOf(media("video/mp4", "one.mp4"), media("video/mp4", "two.mp4")))
        val second = samplePost("second", media = listOf(media("video/mp4", "three.mp4")))
        var state = createViewerUiState(session("queue"), listOf(first, second))
        fun ended() = ViewerAction.PlaybackCompleted(requireNotNull(state.session), requireNotNull(state.currentMedia).key,
            requireNotNull(state.currentMedia).loadGeneration, 0L)
        assertEquals(state, reduceViewerState(state, ended()).state)
        state = reduceViewerState(state, ViewerAction.SetPlaybackMode(ViewerPlaybackMode.ONCE)).state
        state = reduceViewerState(state, ended()).state
        assertFalse(state.controls.playback.playing)
        state = reduceViewerState(state, ViewerAction.Play).state
        assertTrue(state.controls.playback.playing)
        assertEquals(1L, state.controls.playback.restartRequest)
        state = reduceViewerState(state, ViewerAction.SetPlaybackMode(ViewerPlaybackMode.NEXT)).state
        val obsolete = ended()
        state = reduceViewerState(state, obsolete).state
        assertEquals(1, state.currentPage?.selectedMediaIndex)
        assertEquals(state, reduceViewerState(state, obsolete).state)
        state = reduceViewerState(state, ended()).state
        assertEquals(1, state.currentPageIndex)
        assertEquals(ViewerPlaybackMode.NEXT, state.controls.playbackMode)
        state = reduceViewerState(state, ended()).state
        assertFalse(state.controls.playback.playing)
    }

    @Test
    fun `mapping distinguishes image video and animated WebP without platform handles`() {
        val image = samplePost("image", media = listOf(media("image/jpeg", "image.jpg")))
        val video = samplePost("video", media = listOf(media("video/mp4", "video.mp4")))
        val animatedWebP = samplePost(
            "webp",
            media = listOf(media("image/webp", "animated.webp", animated = true)),
        )

        val imageState = image.toViewerPageState()
        val videoState = createViewerUiState(session("image-video"), listOf(video))
        val webPState = createViewerUiState(session("animated-webp"), listOf(animatedWebP))

        assertEquals(ViewerMediaKind.IMAGE, imageState.selectedMedia?.kind)
        assertEquals(ViewerMediaKind.VIDEO, videoState.currentMedia?.kind)
        assertTrue(videoState.controls.playback.available)
        assertEquals(ViewerMediaKind.ANIMATED_WEBP, webPState.currentMedia?.kind)
        assertTrue(webPState.controls.playback.available)
        assertTrue(
            ViewerUiState::class.java.declaredFields.none { field ->
                field.type.name.contains("android.") ||
                    field.type.name.contains("ExoPlayer") ||
                    field.type.name.contains("Drawable")
            },
        )
    }

    @Test
    fun `session replacement resets transient state and rejects late previous-session work`() {
        val oldSession = session("old")
        val newSession = session("new")
        val oldPost = samplePost("old-post", media = listOf(media("video/mp4", "old.mp4")))
        val newPosts = listOf(samplePost("new-1"), samplePost("new-2"))
        val started = createViewerUiState(oldSession, listOf(oldPost))
        val failed = reduceViewerState(
            started,
            ViewerAction.MediaFailed(
                oldSession,
                ViewerMediaError.Fatal(started.currentMedia?.key, "old failure"),
            ),
        ).state

        val replaced = reduceViewerState(
            failed,
            ViewerAction.ReplaceSession(newSession, newPosts, initialPageIndex = 1),
        ).state
        val afterLateCompletion = reduceViewerState(
            replaced,
            ViewerAction.ResolutionCompleted(oldSession, oldPost.copy(title = "stale")),
        ).state

        assertEquals(newSession, replaced.session)
        assertEquals(1, replaced.currentPageIndex)
        assertEquals("new-2", replaced.currentPage?.post?.id?.sourcePostId)
        assertNull(replaced.mediaError)
        assertEquals(replaced, afterLateCompletion)
    }

    @Test
    fun `resolved page replacement preserves selection and exposes replacement metadata`() {
        val session = session("resolution")
        val preview = samplePost(
            "post",
            media = listOf(media("image/jpeg", "preview.jpg")),
        )
        val resolved = preview.copy(
            title = "Resolved title",
            media = listOf(
                media("image/jpeg", "first.jpg"),
                media("video/mp4", "second.mp4"),
            ),
        )
        val initial = createViewerUiState(
            session,
            listOf(preview),
            requiresResolution = { true },
        )

        val requested = reduceViewerState(initial, ViewerAction.RequestPageResolution(preview.id))
        val resolving = reduceViewerState(
            requested.state,
            ViewerAction.ResolutionStarted(session, preview.id),
        ).state
        val completed = reduceViewerState(
            resolving,
            ViewerAction.ResolutionCompleted(session, resolved),
        ).state

        assertTrue(requested.effects.single() is ViewerEffect.ResolvePost)
        assertEquals(ViewerResolutionStatus.RESOLVED, completed.currentPage?.resolution?.status)
        assertEquals("Resolved title", completed.currentPage?.post?.title)
        assertEquals(2, completed.currentPage?.media?.size)
        assertEquals(0, completed.currentPage?.selectedMediaIndex)
    }

    @Test
    fun `video controls implement pause play restart and rate`() {
        val state = createViewerUiState(
            session("video-controls"),
            listOf(samplePost("video", media = listOf(media("video/mp4", "video.mp4")))),
        )

        val paused = reduceViewerState(state, ViewerAction.Pause).state
        val played = reduceViewerState(paused, ViewerAction.Play).state
        val rated = reduceViewerState(played, ViewerAction.SetPlaybackRate(1.5f)).state
        val restarted = reduceViewerState(rated, ViewerAction.RestartPlayback).state

        assertFalse(paused.controls.playback.playing)
        assertTrue(played.controls.playback.playing)
        assertEquals(1.5f, restarted.controls.playback.playbackRate)
        assertEquals(1L, restarted.controls.playback.restartRequest)
    }

    @Test
    fun `animated WebP restart command resumes paused playback without changing media`() {
        val state = createViewerUiState(
            session("webp-controls"),
            listOf(
                samplePost(
                    "webp",
                    media = listOf(media("image/webp", "animated.webp", animated = true)),
                ),
            ),
        )

        val paused = reduceViewerState(state, ViewerAction.Pause).state
        val restarted = reduceViewerState(paused, ViewerAction.RestartPlayback).state

        assertEquals(state.currentMedia, restarted.currentMedia)
        assertEquals(1L, restarted.controls.playback.restartRequest)
        assertTrue(restarted.controls.playback.playing)
    }

    @Test
    fun `overview selection changes media kind and closes overview`() {
        val state = createViewerUiState(
            session("overview"),
            listOf(
                samplePost(
                    "mixed",
                    media = listOf(
                        media("image/jpeg", "first.jpg"),
                        media("video/mp4", "second.mp4"),
                        media("image/webp", "third.webp", animated = true),
                    ),
                ),
            ),
        )

        val opened = reduceViewerState(state, ViewerAction.ToggleOverview).state
        val selected = reduceViewerState(opened, ViewerAction.SelectMedia(2)).state

        assertTrue(opened.overview.visible)
        assertTrue(opened.overview.available)
        assertEquals(ViewerMediaKind.ANIMATED_WEBP, selected.currentMedia?.kind)
        assertEquals(2, selected.currentPage?.selectedMediaIndex)
        assertFalse(selected.overview.visible)
        assertTrue(selected.overview.available)
    }

    @Test
    fun `recoverable failure pauses playback and retry emits one session scoped effect`() {
        val session = session("recovery")
        val state = createViewerUiState(
            session,
            listOf(samplePost("video", media = listOf(media("video/mp4", "video.mp4")))),
        )
        val mediaKey = requireNotNull(state.currentMedia?.key)

        val failed = reduceViewerState(
            state,
            ViewerAction.MediaFailed(
                session,
                ViewerMediaError.Recoverable(mediaKey, "temporary media failure"),
            ),
        ).state
        val retry = reduceViewerState(failed, ViewerAction.RetryMedia)

        assertFalse(failed.controls.playback.playing)
        assertTrue(failed.mediaError is ViewerMediaError.Recoverable)
        assertEquals(ViewerEffect.RetryMedia(session, mediaKey), retry.effects.single())
        assertNull(retry.state.mediaError)
        assertEquals(1L, retry.state.currentMedia?.loadGeneration)
    }

    @Test
    fun `fresh links for failed media continue its load as it was`() {
        val session = session("fresh-links")
        fun episode(link: String) = samplePost("episode", media = listOf(media("video/mp4", "episode.mp4").copy(streams = listOf(MediaStream(link, "Server A")))))
        val state = createViewerUiState(session, listOf(episode("https://media.example/old.m3u8")))
        val mediaKey = requireNotNull(state.currentMedia?.key)
        val retried = listOf(
            ViewerAction.MediaFailed(session, ViewerMediaError.Recoverable(mediaKey, "temporary media failure")),
            ViewerAction.RetryMedia,
            ViewerAction.Pause,
        ).fold(state) { current, action -> reduceViewerState(current, action).state }

        val refreshed = reduceViewerState(
            retried,
            ViewerAction.ResolutionCompleted(session, episode("https://media.example/new.m3u8"), recovered = true),
        ).state

        assertEquals("https://media.example/new.m3u8", refreshed.currentMedia?.ref?.streams?.single()?.url)
        assertEquals(1L, refreshed.currentMedia?.loadGeneration)
        assertFalse(refreshed.controls.playback.playing)
    }

    @Test
    fun `retry after a failed lookup of the current post requests only the post again`() {
        val session = session("retry-resolution")
        val cover = samplePost("cover")
        val state = createViewerUiState(session, listOf(cover), requiresResolution = { true })

        val failed = reduceViewerState(
            state,
            ViewerAction.ResolutionFailed(session, cover.id, message = "detail request failed", recoverable = true),
        ).state
        val retry = reduceViewerState(failed, ViewerAction.RetryMedia)

        assertTrue(failed.mediaError is ViewerMediaError.Recoverable)
        assertEquals(listOf(ViewerEffect.ResolvePost(session, cover.id)), retry.effects)
        assertEquals(1L, retry.state.currentMedia?.loadGeneration)
        assertEquals(ViewerResolutionStatus.REQUESTED, retry.state.currentPage?.resolution?.status)
    }

    @Test
    fun `late failure for previously selected media cannot interrupt current media`() {
        val session = session("same-session-media")
        val initial = createViewerUiState(
            session,
            listOf(
                samplePost(
                    "mixed-video",
                    media = listOf(
                        media("video/mp4", "first.mp4"),
                        media("video/mp4", "second.mp4"),
                    ),
                ),
            ),
        )
        val previousMediaKey = requireNotNull(initial.currentMedia?.key)
        val selected = reduceViewerState(initial, ViewerAction.SelectMedia(1)).state

        val afterLateFailure = reduceViewerState(
            selected,
            ViewerAction.MediaFailed(
                session,
                ViewerMediaError.Recoverable(previousMediaKey, "late first-media failure"),
            ),
        ).state

        assertEquals(1, selected.currentMedia?.key?.mediaIndex)
        assertTrue(selected.controls.playback.playing)
        assertEquals(selected, afterLateFailure)
    }

    @Test
    fun `background page resolution failure is recorded without interrupting current playback`() {
        val session = session("background-resolution")
        val current = samplePost("current-video", media = listOf(media("video/mp4", "current.mp4")))
        val background = samplePost("background")
        val state = createViewerUiState(
            session = session,
            posts = listOf(current, background),
            requiresResolution = { post -> post.id == background.id },
        )

        val failed = reduceViewerState(
            state,
            ViewerAction.ResolutionFailed(
                session = session,
                postId = background.id,
                message = "background resolution failed",
                recoverable = true,
            ),
        ).state
        val failedPage = failed.pages.single { page -> page.post.id == background.id }

        assertEquals(ViewerResolutionStatus.FAILED, failedPage.resolution.status)
        assertEquals("background resolution failed", failedPage.resolution.message)
        assertTrue(failedPage.resolution.recoverable)
        assertNull(failed.mediaError)
        assertTrue(failed.controls.playback.playing)
        assertEquals(current.id, failed.currentPage?.post?.id)
    }

    @Test
    fun `save share download creator and tag actions emit typed one shot effects`() {
        val session = session("intents")
        val creator = CreatorProfile(SourceKey.PIXIV, "Creator", profileId = "creator")
        val term = PostTaxonomyTerm("landscape", SearchFacet.TAG)
        val state = createViewerUiState(
            session,
            listOf(samplePost("post", creators = listOf(creator))),
        )
        val postId = requireNotNull(state.currentPage?.post?.id)
        val mediaKey = requireNotNull(state.currentMedia?.key)

        assertEquals(
            ViewerEffect.SavePost(session, postId),
            reduceViewerState(state, ViewerAction.Save).effects.single(),
        )
        assertEquals(
            ViewerEffect.DownloadMedia(session, postId, mediaKey),
            reduceViewerState(state, ViewerAction.Download).effects.single(),
        )
        assertEquals(
            ViewerEffect.OpenCreatorProfile(session, creator),
            reduceViewerState(state, ViewerAction.OpenCreator(creator)).effects.single(),
        )
        assertEquals(
            ViewerEffect.ApplyTag(session, postId, term, excluded = false),
            reduceViewerState(state, ViewerAction.IncludeTag(term)).effects.single(),
        )
        assertEquals(
            ViewerEffect.ApplyTag(session, postId, term, excluded = true),
            reduceViewerState(state, ViewerAction.ExcludeTag(term)).effects.single(),
        )
    }

    private fun session(value: String) = ViewerSessionIdentity(value = value, queryHash = "query-$value")

    private fun media(mime: String, location: String, animated: Boolean = false) = ImageRef(
        url = "https://media.example/$location",
        localPath = null,
        mime = mime,
        progressiveUrls = emptyList(),
        isAnimated = animated,
    )

    private fun samplePost(
        id: String,
        media: List<ImageRef> = listOf(media("image/jpeg", "$id.jpg")),
        creators: List<CreatorProfile> = emptyList(),
    ) = Post(
        id = PostId(SourceKey.PIXIV, id),
        preview = media.first(),
        full = media.firstOrNull(),
        media = media,
        pageUrl = "https://example.test/$id",
        width = 100,
        height = 200,
        canonicalTags = listOf("landscape"),
        rawTags = listOf("landscape"),
        authorName = creators.firstOrNull()?.displayName,
        createdAtEpochMs = 1L,
        title = "Post $id",
        creatorProfile = creators.firstOrNull(),
        creatorProfiles = creators,
    )
}
