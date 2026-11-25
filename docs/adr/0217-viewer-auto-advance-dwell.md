# 0217. Auto-advance holds every post for a minimum time, at a play-through's end, and stops on a post reached backwards

Status: Accepted

## Context

In Play Next mode and in a slideshow, a clip advanced the moment it ended, so a one-second clip or GIF was gone after a second, while still images hold `VIEWER_SLIDESHOW_IMAGE_MS`. Swiping back to rewatch a post was undone as soon as it ended.

## Decision

- Every post stays at least `VIEWER_SLIDESHOW_IMAGE_MS` (`ViewerDwell.elapsed`). A clip that ends sooner is restarted through the ordinary restart request instead of advancing, and the queue only moves on at the end of a play-through, never mid-loop. Every renderer already reports completion (video, GIF, ugoira frames, animated WebP), so the rule lives in the reducer rather than in each player; animated WebP now honours restart requests like the others. Clips at least that long advance when they end, as before. Clips show no countdown.
- The dwell's start is reducer state set by `ViewerAction.RestartDwell`, which `ViewerDwellEffect` dispatches with the time whenever `viewerActivity` changes: the same signal the idle screen-sleep clock follows (touches, including their release, interactions, and the selected post and page). Completions carry their own time, so the reducer stays pure and is tested on virtual time.
- A move the user makes backwards (an earlier post or page) holds auto-advance; any forward move re-arms it from there (`movedByUser`). Automatic advances never change the hold. While held, clips loop, slideshow images wait, and the playback settings button, its Next choice and the slideshow button carry a pause badge (`ViewerPausedBadge`), with no text.

## Consequences

A short clip restarts from its first frame on each loop rather than looping seamlessly in the player. Changing the minimum means changing the slideshow image time with it.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerSlideshow.kt`: `ViewerDwell`, `autoAdvanceHeld`, `slideshowImageHold`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/state/ViewerStateReducer.kt`: `completePlayback`, `movedByUser`, `queuePosition`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerFrameEffects.kt`: `ViewerDwellEffect`, `viewerActivity`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPausedBadge.kt`: `ViewerPausedBadge`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerAnimatedWebPPlayer.kt`: `ViewerAnimatedWebPPlayer`

## Guarded by

- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/state/ViewerClipDwellTest.kt`: `a one second clip replays until five seconds and moves on at a loop's end (ADR 0217)`, `a six second clip moves on when it first ends (ADR 0217)`, `touching restarts the dwell (ADR 0217)`, `a post reached by swiping back never moves on, and a forward swipe re-arms (ADR 0217)`, `the paused badge shows only while auto-advance is held (ADR 0217)`, `a slideshow image waits while auto-advance is held (ADR 0217)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerPlaybackSettingsTest.kt`: `the settings button carries a pause badge only while auto-advance is held (ADR 0217)`
- Left to prose: that each player replays from the start on a restart request is checked on a device, since host tests do not decode media.
