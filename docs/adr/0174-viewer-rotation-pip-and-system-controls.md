# 0174. How the phone is held decides the Viewer's orientation; rotate and the rotation lock are the only manual controls, and picture-in-picture is sized from the displayed frame

Status: Accepted

## Context

Wide or tall media gains space when rotated, but rotating on its own (on opening, per post, per trail level) fought people who chose how to hold the phone, especially with system auto-rotate off in bed. Posts may report no dimensions, or dimensions that disagree with the stream. The Activity can pause before Android reports its automatic picture-in-picture transition.

## Decision

- Nothing rotates the Viewer because of its content. With nothing pinned it asks for `SCREEN_ORIENTATION_USER`, so it follows the sensor only when system auto-rotate is on and otherwise stays put (`viewerScreenOrientation`).
- Two things pin an orientation (`viewerPinnedOrientation`), the lock first. A pin asks for that orientation until the screen has it, then for Android's fixed current rotation (`SCREEN_ORIENTATION_LOCKED`), so turning the phone over cannot flip it 180°:
  - The rotation lock (`ViewerSettings.orientationLock`, persisted like playback mode) pins the orientation the screen had when it was locked, overriding the sensor, until unlocked. Locking and unlocking leave the rotate hold alone, so unlocking returns to the state from before the lock: a hold chosen with rotate stays (which matters with auto-rotate off, where nothing else would keep it), and without one the sensor or the system's fixed rotation leads again.
  - A rotate tap (`ViewerRotationState.held`, owned by the Activity so every trail level shares it) rotates once to the other orientation and holds it. With auto-rotate on, an `OrientationEventListener` ends the hold once the phone is physically turned to match (`viewerPhysicalOrientation`, `viewerHoldEnds`), and the sensor leads again. With auto-rotate off the hold stays, since staying put is what following means there. The shell releases the hold when the last Viewer level leaves.
- Rotate shows whenever the controls show and nothing is locked, for every kind of content, so nobody needs system auto-rotate to turn the Viewer; while locked it hides everywhere, episodes included, and the lock is the way out. Its label names its direction ("Rotate to landscape" or "Rotate to portrait"), and it is emphasised when the media clearly fits the other orientation (`viewerFitsOtherWay`); `ReportViewerVideoShape` reports what decoded frames fill for posts without dimensions. It never rotates by itself.
- Placement: in portrait the lock floats where rotate used to, with rotate beside it; in landscape (outside episodes) rotate takes the strip row's start slot and the lock sits beside slideshow at the end (ADR 0166). Episodes show the same rotation lock beside their rotate button; their padlock locks the controls against touches, not the orientation, and is a separate state.
- Picture-in-picture: only the mounted Viewer video can arm automatic PiP (`ViewerAutoPictureInPicture.bind`); feed players never bind. `keepPlayingOnPause` is true while an eligible player is bound because the Activity can pause before Android reports the transition. Params are shaped like the bound video's displayed frame; post metadata (which can disagree with the stream) is only the fallback until the player knows its frame. The aspect ratio is the frame's (or the post's) width over height, 16:9 when neither is known, clamped to Android's 1:2.39 through 2.39:1. The source rect hint is the video view's visible on-screen bounds, omitted when the view is absent or not visible and in Fill mode, where the view's bounds can differ from the video's frame. Both must match the video's own frame or the window letterboxes it with black bars.

## Consequences

Rotation policy (when media fits the other way, how the sensor reading is classified) lives in the shared orientation policy; this decision covers who pins the orientation and who owns that state.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerRotation.kt`: `ViewerRotationState`, `rememberViewerRotation`, `ViewerRotateButton`, `ViewerRotationLockButton`, `ReportViewerVideoShape`
- `app-logic/src/main/kotlin/com/theoriacodex/app/viewer/ViewerOrientationPolicy.kt`: `viewerFitsOtherWay`, `viewerPhysicalOrientation`, `viewerHoldEnds`, `viewerPinnedOrientation`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaAppPlatformEffects.kt`: `TheoriaAppPlatformEffects`, `viewerScreenOrientation`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerAutoPictureInPicture.kt`: `ViewerAutoPictureInPicture`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPictureInPicture.kt`: `viewerPictureInPictureRatio`, `viewerPictureInPictureParams`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/viewer/ViewerOrientationPolicyTest.kt`: `media fits the other way only when it is clearly shaped for the orientation the screen lacks (ADR 0174)`, `a chip rotation holds until the phone is turned to match, and only while auto-rotate is on (ADR 0174)`, `the lock pins the orientation over any hold, and with neither the sensor decides (ADR 0174)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerRotationTest.kt`: `swiping across wide and tall posts never rotates on its own (ADR 0174)`, `the rotate button shows with the controls even when the post already fits, naming its direction (ADR 0174)`, `a tap rotates once and the hold survives swiping (ADR 0174)`, `the lock pins the current orientation and hides rotate until unlocked (ADR 0174)`, `unlocking returns to the rotation chosen before the lock (ADR 0174)`, `unlocking returns to a rotation to portrait too (ADR 0174)`
- `app/src/test/java/com/theoriacodex/app/ui/ViewerScreenOrientationTest.kt`: `with nothing pinned the Viewer follows the system rotation setting, and a pin turns once then holds that exact rotation (ADR 0174)`
- Left to prose: the picture-in-picture rules predate this section and are covered by untagged tests; that the listener only runs while a hold is active holds by construction.
