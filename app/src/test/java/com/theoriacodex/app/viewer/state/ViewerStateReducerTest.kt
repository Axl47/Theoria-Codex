package com.theoriacodex.app.viewer.state

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
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
        assertTrue(videoState.controls.playback.progress is ViewerPlaybackProgress.Timeline)
        assertEquals(ViewerMediaKind.ANIMATED_WEBP, webPState.currentMedia?.kind)
        assertTrue(webPState.controls.playback.progress is ViewerPlaybackProgress.Frames)
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
        assertTrue(replaced.prefetch.warmed.isEmpty())
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

        val requested = reduceViewerState(initial, ViewerAction.RequestCurrentPageResolution)
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
        assertEquals("Resolved title", completed.currentMetadata?.title)
        assertEquals(2, completed.currentPage?.media?.size)
        assertEquals(0, completed.currentPage?.selectedMediaIndex)
    }

    @Test
    fun `video controls implement pause play restart rate and timeline progress`() {
        val state = createViewerUiState(
            session("video-controls"),
            listOf(samplePost("video", media = listOf(media("video/mp4", "video.mp4")))),
        )

        val paused = reduceViewerState(state, ViewerAction.Pause).state
        val played = reduceViewerState(paused, ViewerAction.Play).state
        val progressed = reduceViewerState(
            played,
            ViewerAction.TimelineProgressChanged(positionMs = 800L, durationMs = 1_000L),
        ).state
        val rated = reduceViewerState(progressed, ViewerAction.SetPlaybackRate(1.5f)).state
        val restarted = reduceViewerState(rated, ViewerAction.RestartPlayback).state

        assertFalse(paused.controls.playback.playing)
        assertTrue(played.controls.playback.playing)
        assertEquals(ViewerPlaybackProgress.Timeline(800L, 1_000L), progressed.controls.playback.progress)
        assertEquals(1.5f, restarted.controls.playback.playbackRate)
        assertEquals(1L, restarted.controls.playback.restartRequest)
        assertEquals(ViewerPlaybackProgress.Timeline(0L, 1_000L), restarted.controls.playback.progress)
    }

    @Test
    fun `animated WebP frame progress is bounded and restart returns to first frame`() {
        val state = createViewerUiState(
            session("webp-controls"),
            listOf(
                samplePost(
                    "webp",
                    media = listOf(media("image/webp", "animated.webp", animated = true)),
                ),
            ),
        )

        val progressed = reduceViewerState(
            state,
            ViewerAction.FrameProgressChanged(frameIndex = 99, frameCount = 8),
        ).state
        val progress = progressed.controls.playback.progress as ViewerPlaybackProgress.Frames
        val restarted = reduceViewerState(progressed, ViewerAction.RestartPlayback).state
        val reset = restarted.controls.playback.progress as ViewerPlaybackProgress.Frames

        assertEquals(7, progress.frameIndex)
        assertEquals(8, progress.frameCount)
        assertEquals(1f, progress.fraction)
        assertEquals(0, reset.frameIndex)
        assertEquals(8, reset.frameCount)
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
        val selected = reduceViewerState(opened, ViewerAction.SelectOverviewMedia(2)).state

        assertTrue(opened.overview.visible)
        assertTrue(opened.overview.available)
        assertEquals(ViewerMediaKind.ANIMATED_WEBP, selected.currentMedia?.kind)
        assertEquals(2, selected.currentPage?.selectedMediaIndex)
        assertFalse(selected.overview.visible)
        assertEquals(2, selected.overview.items.single { it.selected }.mediaKey.mediaIndex)
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
    fun `prefetch queue tracks readiness and ignores completion from a replaced session`() {
        val oldSession = session("prefetch-old")
        val newSession = session("prefetch-new")
        val oldState = createViewerUiState(oldSession, listOf(samplePost("old")))
        val mediaKey = requireNotNull(oldState.currentMedia?.key)

        val queued = reduceViewerState(oldState, ViewerAction.QueuePrefetch(listOf(mediaKey)))
        val started = reduceViewerState(
            queued.state,
            ViewerAction.PrefetchStarted(oldSession, mediaKey),
        ).state
        val replaced = reduceViewerState(
            started,
            ViewerAction.ReplaceSession(newSession, listOf(samplePost("new"))),
        ).state
        val staleCompletion = reduceViewerState(
            replaced,
            ViewerAction.PrefetchCompleted(
                oldSession,
                mediaKey,
                ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED, bytesCached = 4_096L),
            ),
        ).state

        assertEquals(ViewerEffect.PrefetchMedia(oldSession, listOf(mediaKey)), queued.effects.single())
        assertTrue(mediaKey in started.prefetch.inFlight)
        assertTrue(staleCompletion.prefetch.warmed.isEmpty())
        assertTrue(staleCompletion.prefetch.inFlight.isEmpty())
    }

    @Test
    fun `prefetch terminal outcomes remain truthful and cannot be queued again`() {
        val session = session("prefetch-outcomes")
        val state = createViewerUiState(
            session,
            listOf(
                samplePost(
                    "gallery",
                    media = listOf(
                        media("image/jpeg", "warm.jpg"),
                        media("image/jpeg", "skip.jpg"),
                        media("image/jpeg", "fail.jpg"),
                    ),
                ),
            ),
        )
        val keys = state.currentPage?.media.orEmpty().map { media -> media.key }
        val warmed = reduceViewerState(
            state,
            ViewerAction.PrefetchCompleted(
                session,
                keys[0],
                ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED, bytesCached = 8_192L),
            ),
        ).state
        val skipped = reduceViewerState(
            warmed,
            ViewerAction.PrefetchCompleted(
                session,
                keys[1],
                ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED),
            ),
        ).state
        val failed = reduceViewerState(
            skipped,
            ViewerAction.PrefetchCompleted(
                session,
                keys[2],
                ViewerPrefetchResult(ViewerPrefetchOutcome.FAILED),
            ),
        ).state
        val requeued = reduceViewerState(failed, ViewerAction.QueuePrefetch(keys))

        assertEquals(8_192L, failed.prefetch.warmed[keys[0]])
        assertEquals(setOf(keys[1]), failed.prefetch.skipped)
        assertEquals(setOf(keys[2]), failed.prefetch.failed)
        assertTrue(requeued.state.prefetch.queued.isEmpty())
        assertEquals(ViewerEffect.PrefetchMedia(session, emptyList()), requeued.effects.single())
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
            ViewerEffect.ShareMedia(session, postId, mediaKey),
            reduceViewerState(state, ViewerAction.Share).effects.single(),
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
