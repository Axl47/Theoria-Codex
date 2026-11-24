# 0174. The Viewer rotates once on opening, shares one rotation across trail levels, and sizes picture-in-picture from the displayed frame

Status: Accepted

## Context

Wide or tall videos gain space when rotated, but rotating again on every swipe or trail level would fight the user. Posts may report no dimensions, or dimensions that disagree with the stream. The Activity can pause before Android reports its automatic picture-in-picture transition.

## Decision

- `ViewerRotationState` is owned by the Activity so the shell and the mounted video can both reach it and every level of a trail shares one rotation; the shell calls `release()` once the last Viewer level leaves, which also lets the next Viewer opening rotate again. `openingFor` claims the opening media only for the first level since release; trail levels never open.
- `rememberViewerRotation` rotates once, when the Viewer opens on a clearly wide or tall video, then holds that orientation while swiping and through trail levels. Videos qualify even when their stream has not resolved (`mediaIndex == 0 && post.awaitsMotion`). The automatic rotation is dropped when the media is not the opening one, auto-rotate is off, or the Viewer is in picture-in-picture. Otherwise it waits, because a post without dimensions learns its size once the video decodes (`ReportViewerVideoShape` reports what decoded frames fill). A user's rotate choice wins over an automatic rotation still waiting. The rotate button always turns to the other orientation and is not offered in picture-in-picture.
- The rotate button shows with the controls and also for `VIEWER_ROTATE_HINT_MS` (3 s) whenever rotating starts to fit a post, since swiping to a post leaves the controls hidden.
- Picture-in-picture: only the mounted Viewer video can arm automatic PiP (`ViewerAutoPictureInPicture.bind`); feed players never bind. `keepPlayingOnPause` is true while an eligible player is bound because the Activity can pause before Android reports the transition. Params are shaped like the bound video's displayed frame; post metadata (which can disagree with the stream) is only the fallback until the player knows its frame. The aspect ratio is the frame's (or the post's) width over height, 16:9 when neither is known, clamped to Android's 1:2.39 through 2.39:1. The source rect hint is the video view's visible on-screen bounds, omitted when the view is absent or not visible and in Fill mode, where the view's bounds can differ from the video's frame. Both must match the video's own frame or the window letterboxes it with black bars.

## Consequences

Rotation policy (which media qualifies) lives in the shared orientation policy; this decision covers when it is applied and who owns the state.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerRotation.kt`: `ViewerRotationState`, `rememberViewerRotation`, `ViewerRotationControl`, `ReportViewerVideoShape`, `VIEWER_ROTATE_HINT_MS`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerAutoPictureInPicture.kt`: `ViewerAutoPictureInPicture`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPictureInPicture.kt`: `viewerPictureInPictureRatio`, `viewerPictureInPictureParams`
