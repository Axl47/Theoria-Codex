# 0181. Viewer video pages pick their stream once, preload only cache-backed streams, and render subtitles outside the video frame

Status: Accepted

## Context
Viewer video pages sit on a pooled ExoPlayer, an Android PlayerView and Picture-in-Picture, each with constraints that only show on device: system calls on every recomposition, preloading streams nothing would keep, subtitle clipping in zoomed video, and a letterboxed PiP transition.

## Decision
- `ViewerVideoPlayer` builds `viewerPlayback` inside a `remember` keyed on media, source, quality, quality height and stream label, because the metered-network check (`isMediaNetworkMetered`) is a system call and must run when the page picks its stream, not on every recomposition.
- Two quality values apply: the quality preference (`rememberViewerVideoQuality`) starts from the saved default and a quality picked in the playback menu becomes the new default; the picked rendition height (`qualityHeight`) belongs to the current media only and resets when the selected media changes.
- A page that is not active renders only its loading preview unless it is preloading and `playback.readsAheadFreely()`. A stream the shared cache does not keep (an episode, any HLS or DASH rendition) does not load ahead of a swipe, because nothing would keep what it read for the page to play and an episode's links expire.
- A video page's loading preview, the post's poster and usually the provider's first-frame thumbnail, stands in only for a page that starts at 0, and only until its player renders a frame, active or not. A page that resumes partway (`ViewerVideoPositions.position` above 0) shows black until its frame renders, because a poster there reads as a frame-0 flash followed by a jump. `ViewerVideoPositions` holds snapshot state so a page redraws once its position is saved. A swipe destination stays preloaded (`ViewerSwipeController.revealTarget`) until the settle finishes, even when the swipe is cancelled, so the recoiling neighbour keeps its frame. Its timeline starts at the start position: `ViewerVideoState` is seeded with it, `ViewerPendingTimeline` takes the position, and the player listener updates position together with duration (`ViewerVideoState.updateProgress`).
- Playback commands apply at once, using `owner.lifecycle.currentState.isAtLeast(STARTED)`, because adding the lifecycle observer replays ON_START/ON_RESUME only while the host is started.
- Picture-in-Picture binds with `state.view.takeUnless { fillScreen }`: in Fill mode the PlayerView's bounds can differ from the video's frame (which sets the PiP aspect ratio), so no `sourceRectHint` is passed, to stop Android letterboxing the transition.
- `bindViewerSubtitleOverlay`/`attachViewerSubtitleView` hide the PlayerView's built-in subtitle view and attach a Viewer-owned `SubtitleView` outside `exo_content_frame`, so `RESIZE_MODE_ZOOM` never clips cues off-screen. Cues go through `sanitizeViewerSubtitleCue`, which strips embedded colours and background boxes but keeps placement, emphasis and bitmap cues.

## Consequences
Metered checks and cache decisions are cheap and stable per page; episodes and adaptive streams start loading only when selected. Any new preloading must go through `readsAheadFreely` rather than a list of sources.

## Applies to
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerVideoPlayer.kt`: `ViewerVideoPlayer`, `ViewerVideoContent`, `ViewerVideoState.updateProgress`, `ViewerVideoSystemBindings`, `bindViewerSubtitleOverlay`, `attachViewerSubtitleView`, `sanitizeViewerSubtitleCue`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerVideoPositions.kt`: `ViewerVideoPositions`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerSwipeController.kt`: `ViewerSwipeController.revealTarget`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/viewer/MediaTimelineBar.kt`: `ViewerPendingTimeline`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerDockBinding.kt`: `rememberViewerVideoQuality`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScreen.kt`: `qualityHeight`

## Guarded by

- `app/src/androidTest/java/com/theoriacodex/app/journeys/ViewerVideoResumeJourneyTest.kt`: `aSwipedToVideoShowsItsFrameAtItsResumePointInsteadOfThePoster_adr_0181`
- Left to prose: the stream choice, metered check, quality defaults, lifecycle replay, Picture-in-Picture source hint and subtitle overlay rules depend on device system state no host or journey check here reproduces.
