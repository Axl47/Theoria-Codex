# 0217. Auto-advance holds every post for a minimum time, at a play-through's end, and stops on a post reached backwards

Status: Accepted

## Context

In Play Next mode and in a slideshow, a clip advanced the moment it ended, so a one-second clip or GIF was gone after a second, while still images hold `VIEWER_SLIDESHOW_IMAGE_MS`. Swiping back to rewatch a post was undone as soon as it ended.

## Decision

- Outside episode watching, every post stays at least `VIEWER_SLIDESHOW_IMAGE_MS` (`ViewerDwell.elapsed`). A clip that ends sooner is restarted through the ordinary restart request instead of advancing, and the queue only moves on at the end of a play-through, never mid-loop. Every renderer already reports completion (video, GIF, ugoira frames, animated WebP), so the rule lives in the reducer rather than in each player; animated WebP now honours restart requests like the others, on its active page and only once it has stopped. Episodes (`ViewerSeries.Watching`) keep advancing when they end, since replaying a whole episode is never wanted (`dwellApplies`). Clips at least that long advance when they end, as before. Clips show no countdown.
- A video never takes that restart round trip when it can be foreseen: while the reducer would restart it at its end (held, a finger down, or the dwell not yet passed; `restartsAtEnd`), the Viewer gives the player `REPEAT_MODE_ONE` (`ViewerRendererControls.repeatsAtEnd`), so ExoPlayer loops from the bytes it already has. A seek back to 0 after the end always passes through `STATE_BUFFERING`, which showed the loading spinner on every loop, while neighbouring posts started from a preloaded first frame. The frame carries the flag as a `State` (`ViewerFrame.videoRepeats`) and turns it off once the dwell passes, so the next end completes and advances as before; the restart request remains for a completion that still arrives. GIFs and animated WebP keep the restart request, since their repeat count is part of the image request.
- The dwell's start is reducer state set by `ViewerAction.RestartDwell`, which `ViewerDwellEffect` dispatches with the time whenever `viewerActivity` changes: the same signal the idle screen-sleep clock follows (touches, including their release, interactions, and the selected post and page), and with whether a finger is down: nothing advances while one is (`ViewerDwell.interacting`), and letting go starts the dwell again. Completions carry their own time, so the reducer stays pure and is tested on virtual time. The dwell's time and touch state change on every touch, so the Viewer frame ignores them as it ignores chrome visibility (`ignoringTransientControls`), keeping pages and overlays from recomposing; the slideshow image timer reads the start time from `ViewerScreenState.dwellStartedAtMs`.
- A move the user makes backwards (an earlier post or page) holds auto-advance; any forward move re-arms it from there (`movedByUser`). Automatic advances never change the hold. Positions are compared in one queue order, the one on screen before the move or else the one after it; a move that no single order holds (between a related branch and the feed) leaves the hold as it was. A touch reaches the reducer straight from the pointer handler, so a finger going down blocks advancing at once; a slideshow image's timer carries the dwell start it waited from and is ignored once the dwell has restarted. Pressing the media still stops a slideshow, so in a slideshow the hold comes from the strip and other taps. While held, clips loop, slideshow images wait (their timer also restarts with the dwell), and the playback settings button, its Next choice and the slideshow button carry a pause badge (`ViewerPausedBadge`), with no text.

## Consequences

A short GIF or animated WebP restarts from its first frame on each loop rather than looping seamlessly in its decoder; a video loops in the player. Changing the minimum means changing the slideshow image time with it.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerSlideshow.kt`: `ViewerDwell`, `autoAdvanceHeld`, `slideshowImageHold`, `restartsAtEnd`, `repeatsVideo`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerStateReducer.kt`: `completePlayback`, `movedByUser`, `queuePosition`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerFrameEffects.kt`: `ViewerDwellEffect`, `viewerActivity`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScreenState.kt`: `ignoringTransientControls`, `dwellStartedAtMs`, `rememberViewerVideoRepeats`, `ViewerFrame.videoRepeats`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerVideoPlayer.kt`: `ViewerVideoCommands`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPausedBadge.kt`: `ViewerPausedBadge`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerAnimatedWebPPlayer.kt`: `ViewerAnimatedWebPPlayer`

## Guarded by

- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/state/ViewerClipDwellTest.kt`: `a one second clip replays until five seconds and moves on at a loop's end (ADR 0217)`, `a six second clip moves on when it first ends (ADR 0217)`, `touching restarts the dwell (ADR 0217)`, `a clip never moves on while a finger is down, and letting go restarts the dwell (ADR 0217)`, `a post reached by swiping back never moves on, and a forward swipe re-arms (ADR 0217)`, `the paused badge shows only while auto-advance is held (ADR 0217)`, `a slideshow image waits while auto-advance is held (ADR 0217)`, `a slideshow image timer from before a dwell restart is ignored (ADR 0217)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerPlaybackSettingsTest.kt`: `the settings button carries a pause badge only while auto-advance is held (ADR 0217)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerChromeToggleSkipTest.kt`: `a dwell restart in Play Next leaves the pages and the overlay host alone (ADR 0217)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerFrameTest.kt`: `a held or still-dwelling Play Next video repeats in the player until the dwell has passed (ADR 0217)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/VideoStreamPlaybackTest.kt`: `a repeating post starts over on its own, without buffering or asking the server again (ADR 0217)`
- Left to prose: that each player replays from the start on a restart request is checked on a device, since host tests do not decode media.
