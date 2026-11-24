# 0175. The Viewer's Save button saves to the top suggestion, never to a stale post's target, and cleans up its fan with the dock

Status: Accepted

## Context

The dock's Save button saves in one tap, offers Undo, and opens a hold-and-slide fan of other Codices. Suggestions are scored by walking the library, so they arrive after the post changes, and the dock can disappear (tap on media, picture-in-picture) mid-gesture.

## Decision

- Suggestions load for the settled post after `SUGGESTION_SETTLE_MS` (250 ms), and the previous post's stay until the new ones arrive, since scoring walks the library. They are requested with one more entry than the fan shows because the button saves to the top one and the fan shows the next ones (`SAVE_MENU_CODICES + 1`, `drop(1)`). The fan lists suggestions only when they are this post's; otherwise only New and More.
- `viewerSaveButtonState`: a save of this post within `VIEWER_SAVED_UNDO_MS` (4 s) shows Saved (a clock read just before the save landed counts too). Until the suggestions are this post's (`ready`), the button shows the previous post's target so it does not flicker, but a tap opens the picker instead of saving, and whether the post is in every Codex is the previous post's answer until this one's arrive. Suggestions never include Likes, so with no target and the post in no Codex the profile has no Codex yet, and a tap names a new one.
- Undo targets the record the button shows, not whichever save landed last, and runs undispatched so the removal is under way before the dock can leave and cancel its scope. A failed quick save falls back to the picker, which reports its own errors.
- `viewerSaveGestures` is keyed to the post, so paging mid-hold cancels the gesture and closes the fan instead of saving the next post to a target ranked for this one. The fan state is cleared when the button leaves composition or the gesture is cancelled, so nothing the dock opened outlives it. The button owns its pointer input, leaving the media's gestures untouched.
- `ViewerSaveFanState` lives in `ViewerScreenState` outside the dock, so hiding and showing the controls does not reload suggestions and the chrome auto-hide can see `holdsChrome` (fan open or Saved/Undo showing).
- The dock's test tag sits inside the cleared semantics, or clearing would drop it.

## Consequences

Any new save surface in the Viewer should key its gestures to the post and read suggestions through the `postId` check.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerSaveButton.kt`: `ViewerSaveButton`, `ViewerSaveFanState`, `rememberSettledSaveSuggestions`, `viewerSaveGestures`, `ViewerSaveHold`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerSaveButtonModel.kt`: `viewerSaveButtonState`, `tapCommand`, `viewerSaveFanTargets`, `viewerSaveFanRelease`, `VIEWER_SAVED_UNDO_MS`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScreenState.kt`: `ViewerScreenState.saveFan`
