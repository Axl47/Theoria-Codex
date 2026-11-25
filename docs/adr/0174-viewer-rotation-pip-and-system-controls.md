# 0174. How the phone is held decides the Viewer's orientation; content only offers a rotate chip, and picture-in-picture is sized from the displayed frame

Status: Accepted

## Context

Wide or tall media gains space when rotated, but rotating on its own (on opening, per post, per trail level) fought people who chose how to hold the phone, especially with system auto-rotate off in bed. Posts may report no dimensions, or dimensions that disagree with the stream. The Activity can pause before Android reports its automatic picture-in-picture transition.

## Decision

- Nothing rotates the Viewer because of its content. With nothing pinned it asks for `SCREEN_ORIENTATION_USER`, so it follows the sensor only when system auto-rotate is on and otherwise stays put (`viewerScreenOrientation`).
- Two things pin an orientation (`viewerPinnedOrientation`), the lock first:
  - The rotation lock (`ViewerSettings.orientationLock`, persisted like playback mode) pins the orientation the screen had when it was locked, overriding the sensor, until unlocked. Locking or unlocking drops any chip hold. Rotating while locked (the episode row's rotate) moves the lock to the other orientation.
  - A rotate-chip tap (`ViewerRotationState.held`, owned by the Activity so every trail level shares it) rotates once to the other orientation and holds it. With auto-rotate on, an `OrientationEventListener` ends the hold once the phone is physically turned to match (`viewerPhysicalOrientation`, `viewerHoldEnds`), and the sensor leads again. With auto-rotate off the hold stays, since staying put is what following means there. The shell releases the hold when the last Viewer level leaves.
- The chip is offered only when the media clearly fits the other orientation and nothing is locked (`viewerOffersRotation`); `ReportViewerVideoShape` reports what decoded frames fill for posts without dimensions. It shows with the controls and for `VIEWER_ROTATE_HINT_MS` (3 s) when such media settles, and never rotates by itself.
- Placement: in portrait the lock floats where rotate used to, with the chip beside it; in landscape (outside episodes) the chip takes the strip row's start slot and the lock sits beside slideshow at the end (ADR 0166). Episodes keep their own lock, which locks the controls against touches, not the orientation; it is a separate state.
- Picture-in-picture: only the mounted Viewer video can arm automatic PiP (`ViewerAutoPictureInPicture.bind`); feed players never bind. `keepPlayingOnPause` is true while an eligible player is bound because the Activity can pause before Android reports the transition. Params are shaped like the bound video's displayed frame; post metadata (which can disagree with the stream) is only the fallback until the player knows its frame. The aspect ratio is the frame's (or the post's) width over height, 16:9 when neither is known, clamped to Android's 1:2.39 through 2.39:1. The source rect hint is the video view's visible on-screen bounds, omitted when the view is absent or not visible and in Fill mode, where the view's bounds can differ from the video's frame. Both must match the video's own frame or the window letterboxes it with black bars.

## Consequences

Rotation policy (when the chip is offered, how the sensor reading is classified) lives in the shared orientation policy; this decision covers who pins the orientation and who owns that state.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerRotation.kt`: `ViewerRotationState`, `rememberViewerRotation`, `ViewerRotationChip`, `ViewerRotationLockButton`, `ReportViewerVideoShape`, `VIEWER_ROTATE_HINT_MS`
- `app-logic/src/main/kotlin/com/theoriacodex/app/viewer/ViewerOrientationPolicy.kt`: `viewerOffersRotation`, `viewerPhysicalOrientation`, `viewerHoldEnds`, `viewerPinnedOrientation`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaAppPlatformEffects.kt`: `TheoriaAppPlatformEffects`, `viewerScreenOrientation`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerAutoPictureInPicture.kt`: `ViewerAutoPictureInPicture`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPictureInPicture.kt`: `viewerPictureInPictureRatio`, `viewerPictureInPictureParams`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/viewer/ViewerOrientationPolicyTest.kt`: `the chip is offered when media fits the other orientation, never while locked (ADR 0174)`, `a chip rotation holds until the phone is turned to match, and only while auto-rotate is on (ADR 0174)`, `the lock pins the orientation over any hold, and with neither the sensor decides (ADR 0174)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerRotationTest.kt`: `swiping across wide and tall posts never rotates on its own (ADR 0174)`, `with the controls hidden the chip shows for a few seconds on a post that fits the other way, then fades (ADR 0174)`, `a chip tap rotates once and the hold survives swiping (ADR 0174)`, `the lock pins the current orientation, drops any hold, and hides the chip until unlocked (ADR 0174)`
- `app/src/test/java/com/theoriacodex/app/ui/ViewerScreenOrientationTest.kt`: `with nothing pinned the Viewer follows the system rotation setting, and a pin holds one orientation (ADR 0174)`
- Left to prose: the picture-in-picture rules predate this section and are covered by untagged tests; that the listener only runs while a hold is active holds by construction.
